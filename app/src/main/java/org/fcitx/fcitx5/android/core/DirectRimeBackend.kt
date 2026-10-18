/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.tracing.trace
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fcitx.directrime.Native
import org.fcitx.directrime.ProfileArchive
import org.fcitx.directrime.Snapshot
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Original frontend contract implemented directly on librime's single owner thread. */
class DirectRimeBackend(context: Context) : InputEngine {
    private val context = FcitxApplication.getInstance().directBootAwareContext
    private val owner = Executors.newSingleThreadExecutor { Thread(it, "rime-main") }.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + owner)
    private val registry = FcitxLifecycleRegistry()
    override val lifecycle: FcitxLifecycle get() = registry
    override val isReady get() = registry.currentState == FcitxLifecycle.State.READY && started
    private val epoch = AtomicLong()
    private val bindingLock = Any()
    private val keyGate = InputKeyGate()
    private data class Binding(val generation: Long, val program: String, val flags: CapabilityFlags, val needsRime: Boolean)
    @Volatile private var binding: Binding? = null
    override val inputSession get() = epoch.get()
    override fun invalidateInputSession(): Long = synchronized(bindingLock) {
        epoch.incrementAndGet().also { binding = null; keyGate.invalidate(it) }
    }
    override fun prepareInputSession(generation: Long, program: String, flags: CapabilityFlags, needsRime: Boolean) {
        synchronized(bindingLock) {
            if (generation != inputSession) return
            // A ReadyEvent can re-apply TYPE_NULL metadata after the first real
            // key promoted this binding. Demand is monotonic within an editor
            // generation; only invalidating that generation may revoke it.
            val requested = needsRime || binding?.let { it.generation == generation && it.needsRime } == true
            val next = Binding(generation, program, flags, requested)
            if (next != binding) { binding = next; keyGate.prepare(generation, needsRime) }
        }
    }
    override fun requireInputSession(generation: Long): Boolean = synchronized(bindingLock) {
        val previous = binding ?: return false
        if (generation != inputSession || previous.generation != generation || previous.needsRime) return false
        binding = previous.copy(needsRime = true)
        keyGate.prepare(generation, true)
        true
    }
    override fun captureInputKey() = keyGate.capture { reason ->
        android.util.Log.w("RimeInputQueue", "key_rejected generation=$inputSession reason=$reason")
    }
    override fun canDeliverInputKey(ticket: InputKeyGate.Ticket): Boolean {
        val deliver = keyGate.canDeliver(ticket)
        if (ticket.waitedForBinding) android.util.Log.i("RimeInputQueue",
            "binding_key generation=${ticket.generation} wait_ms=${(System.nanoTime() - ticket.capturedNanos) / 1_000_000} delivered=$deliver")
        return deliver
    }
    override fun releaseInputKey(ticket: InputKeyGate.Ticket) = keyGate.release(ticket)
    private val events = Channel<List<FcitxEvent<*>>>(Channel.UNLIMITED)
    private val flow = MutableSharedFlow<FcitxEvent<*>>(extraBufferCapacity = 64)
    override val eventFlow = flow.asSharedFlow()
    private val config = DirectRimeConfig(this.context)
    @Volatile private var started = false
    @Volatile private var snapshot: Snapshot? = null
    private var program = ""
    private var activeUid = -1
    private var flags = CapabilityFlags.DefaultFlags
    private var appliedNoLearning: Boolean? = null
    private var bindingKeys = 0
    private var bindingSelections = 0
    private var pagingMode = 1
    private var preferredSchema = ""
    private var preferredAscii: Boolean? = null
    private var schemas = emptyList<Pair<String, String>>()
    private var optionRows = emptyList<List<String>>()
    private var optionsRevision = Long.MIN_VALUE
    private var publishedGeneration = Long.MIN_VALUE
    private var candidateEventCached: FcitxEvent<*>? = null
    @Volatile private var candidateVersion = 0L
    private val forwardedKeys = mutableSetOf<Pair<Int, Int>>()
    private var startupError = ""
    private var statusHint = ""
    private var statusHintJob: Job? = null
    @Volatile private var maintenance = false
    override val acceptsInput get() = keyGate.canProcess()
    @Volatile override var inputMethodEntryCached = entry(null)
        private set
    @Volatile override var statusAreaActionsCached = emptyArray<Action>()
        private set
    @Volatile override var clientPreeditCached = FormattedText.Empty
        private set
    @Volatile override var inputPanelCached = FcitxEvent.InputPanelEvent.Data()
        private set

    init {
        // Emission is independent of the native owner. A slow UI never drops commits
        // and cannot deadlock the owner by requesting a candidate page while collecting.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            for (batch in events) for (event in batch) {
                InputDiagnostics.emitting(event)
                flow.emit(event)
            }
        }
    }

    override fun start() {
        if (registry.currentState != FcitxLifecycle.State.STOPPED) return
        registry.postEvent(FcitxLifecycle.Event.ON_START)
        keyGate.starting()
        showUnavailable("正在准备中州韵…")
        engineScope.launch {
            try {
                startupStage("assets") { DataManager.sync() }
                startupStage("config") { config.load() }
                startupStage("native") { Native.start(shared.absolutePath, config.user.absolutePath) }
                started = true
                startupError = ""
                refreshSchemas()
                // The real editor owns the generation. Do not create a temporary
                // session, or invalidate keys Android already queued during startup.
            } catch (e: Exception) {
                startupError = e.message ?: "引擎启动失败"
                Timber.e(e, "Direct Rime startup failed")
                showUnavailable(startupError)
            } finally {
                keyGate.running(started)
                registry.postEvent(FcitxLifecycle.Event.ON_READY)
                events.trySend(listOf(FcitxEvent.ReadyEvent()))
            }
            if (Build.VERSION.SDK_INT >= 34) SubtypeManager.syncWith(enabledIme())
        }
    }

    override fun stop() {
        if (registry.currentState == FcitxLifecycle.State.STOPPED) return
        invalidateInputSession()
        keyGate.stop()
        runBlocking(owner) {
            registry.postEvent(FcitxLifecycle.Event.ON_STOP)
            clearStatusHint()
            if (started) Native.stop()
            started = false
            snapshot = null
            registry.postEvent(FcitxLifecycle.Event.ON_STOPPED)
        }
    }

    private val shared get() = File(DataManager.dataDir, "usr/share/rime-data")
    private inline fun <T> startupStage(name: String, crossinline block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        return try { trace("rime.direct.startup.$name") { block() } }
        finally { android.util.Log.i("RimeLifecycle", "startup phase=$name duration_ms=${SystemClock.elapsedRealtime() - start}") }
    }
    private suspend fun <T> onOwner(block: () -> T): T = withContext(owner) { block() }
    private fun nativeTiming(values: LongArray = Native.operationTiming()): String =
        "core_us=${values[0] / 1000} read_commit_us=${values[1] / 1000} read_context_us=${values[2] / 1000} read_status_us=${values[3] / 1000} input_tabs_us=${values[4] / 1000} core_cpu_us=${if (values[5] < 0) -1 else values[5] / 1000} core_minflt=${values[6]} core_majflt=${values[7]} core_nvcsw=${values[8]} core_nivcsw=${values[9]}"
    private fun refreshSchemas() {
        schemas = Native.schemaEntries().toList().chunked(2).map { it[0] to it[1] }
    }
    private fun begin(generation: Long) {
        if (!started || generation != inputSession) return
        val request = binding?.takeIf { it.generation == generation }
        if (request?.needsRime == false) { clearBinding(generation); return }
        if (request == null) prepareInputSession(generation, program, flags, true)
        val target = binding?.takeIf { it.generation == generation } ?: return
        program = target.program
        flags = target.flags
        val startedAt = SystemClock.elapsedRealtime()
        val before = Native.sessionStats()
        val beginAt = SystemClock.elapsedRealtimeNanos()
        var result = Native.beginInput(generation, program, flags.flags and CapabilityFlag.PasswordOrSensitive.flag != 0UL)
        val beginDone = SystemClock.elapsedRealtimeNanos()
        val beginTiming = Native.operationTiming()
        if (result.ready) {
            appliedNoLearning = flags.flags and CapabilityFlag.PasswordOrSensitive.flag != 0UL
            if (preferredSchema.isNotEmpty() && result.schema != preferredSchema && schemas.any { it.first == preferredSchema })
                result = Native.action(generation, result.revision, 3, 0, preferredSchema)
            preferredAscii?.let { ascii ->
                if (result.ascii != ascii) result = Native.action(generation, result.revision, 8, if (ascii) 1 else 0, "ascii_mode")
            }
        }
        val restoreDone = SystemClock.elapsedRealtimeNanos()
        publish(result)
        bindingKeys = 0
        bindingSelections = 0
        keyGate.resolve(generation, result.ready)
        val after = Native.sessionStats()
        android.util.Log.i("RimeLifecycle", "bind generation=$generation duration_ms=${SystemClock.elapsedRealtime() - startedAt} ready=${result.ready} created=${after[0] - before[0]} reused=${after[1] - before[1]} no_learning=${flags.flags and CapabilityFlag.PasswordOrSensitive.flag != 0UL} native_begin_us=${(beginDone - beginAt) / 1000} restore_us=${(restoreDone - beginDone) / 1000} ${nativeTiming(beginTiming)}")
    }
    override suspend fun startInputSession(generation: Long, program: String) = onOwner {
        if (generation != inputSession) return@onOwner
        if (binding?.generation != generation) prepareInputSession(generation, program, flags, true)
        if (snapshot?.generation == generation && snapshot?.ready == true && binding?.needsRime == true) {
            keyGate.resolve(generation, true)
            return@onOwner
        }
        forwardedKeys.clear()
        begin(generation)
    }
    private fun clearBinding(generation: Long) {
        snapshot?.let { if (started) Native.end(it.generation) }
        snapshot = null
        appliedNoLearning = null
        forwardedKeys.clear()
        clearStatusHint()
        clientPreeditCached = FormattedText.Empty
        inputPanelCached = FcitxEvent.InputPanelEvent.Data()
        candidateEventCached = null
        candidateVersion++
        publishedGeneration = Long.MIN_VALUE
        keyGate.resolve(generation, false)
        events.trySend(listOf(
            FcitxEvent.ClientPreeditEvent(FormattedText.Empty),
            FcitxEvent.InputPanelEvent(inputPanelCached),
            FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data.Empty)
        ).onEach { it.inputSession = generation })
        android.util.Log.i("RimeLifecycle", "binding_inactive generation=$generation native_create=false")
    }
    private fun act(action: Int, index: Int = 0, value: String = ""): Snapshot? {
        val previous = snapshot ?: return null
        if (!started || maintenance || previous.generation != inputSession) return null
        return Native.action(previous.generation, previous.revision, action, index, value).also(::publish)
    }
    private fun formatted(text: String, cursor: Int = text.length, underline: Boolean = false) =
        if (text.isEmpty()) FormattedText.Empty else FormattedText(arrayOf(text),
            intArrayOf(if (underline) TextFormatFlag.Underline.flag else 0), cursor.coerceIn(0, text.length))

    private fun publish(value: Snapshot) {
        if (value.generation != inputSession || value.gate == 3) return
        val previous = snapshot
        snapshot = value
        val newSession = publishedGeneration != value.generation
        val modeChanged = !newSession && previous?.ready == true && value.ready &&
            (previous.schema != value.schema || previous.ascii != value.ascii)
        if (modeChanged) {
            showStatusHint(value, if (previous!!.schema != value.schema) value.schemaName.ifEmpty { value.schema } else
                context.getString(if (value.ascii) R.string.direct_rime_ascii_mode else R.string.direct_rime_chinese_mode))
        } else if (newSession || !value.ready || value.preedit.isNotEmpty() || value.commit.isNotEmpty()) {
            clearStatusHint()
        }
        val optionsChanged = value.ready && value.optionsRevision != optionsRevision
        if (value.ready) {
            preferredSchema = value.schema
            preferredAscii = value.ascii
            if (optionsChanged) {
                optionRows = Native.options().toList().chunked(4)
                optionsRevision = value.optionsRevision
            }
        }
        val client = when (config.preeditMode) {
            "ComposingText" -> formatted(value.preedit, value.cursorUtf16, true)
            "CommitPreview" -> formatted(value.preview, underline = true)
            else -> FormattedText.Empty
        }
        val tabs = value.tabs.mapIndexed { i, text -> CandidateAction(i, text, false, "", false, false) }.toTypedArray()
        val panel = FcitxEvent.InputPanelEvent.Data(
            if (config.preeditMode == "No") formatted(value.preedit, value.cursorUtf16) else FormattedText.Empty,
            if (value.ready) formatted(statusHint) else formatted(startupError.ifEmpty { value.gateMessage }),
            FormattedText.Empty, tabs
        )
        val ime = entry(value)
        val imeChanged = ime != inputMethodEntryCached
        val statusChanged = newSession || optionsChanged || imeChanged || statusAreaActionsCached.isEmpty()
        val actions = if (statusChanged) buildActions(value) else statusAreaActionsCached
        val clientChanged = newSession || client != clientPreeditCached
        val panelChanged = newSession || panel != inputPanelCached
        clientPreeditCached = client
        inputPanelCached = panel
        inputMethodEntryCached = ime
        statusAreaActionsCached = actions
        val batch = mutableListOf<FcitxEvent<*>>()
        if (value.commit.isNotEmpty()) batch += FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data(value.commit, -1)).apply {
            commitProducedNanos = SystemClock.elapsedRealtimeNanos()
        }
        if (clientChanged) batch += FcitxEvent.ClientPreeditEvent(client)
        if (panelChanged) batch += FcitxEvent.InputPanelEvent(panel)
        val candidates = value.candidates.mapIndexed { i, text ->
            // Candidate codes are separate metadata, not a schema's visible comment.
            // Appending them would bypass Lua filters such as Wanxiang's comment switch.
            CandidateWord(value.labels.getOrElse(i) { "${(i + 1) % 10} " }, text, value.comments.getOrElse(i) { "" })
        }.toTypedArray()
        val candidateEvent = if (pagingMode != 0) FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data(
            candidates, value.highlighted, FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet, value.page > 0, !value.lastPage))
        else FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data(-1, candidates))
        // An unchanged first page can hide a changed tail of a lazy translation.
        // Keep handled keys/actions and composition changes invalidating expanded pages.
        if (newSession || value.handled || optionsChanged || previous?.preedit != value.preedit ||
            previous?.cursorBytes != value.cursorBytes || candidateEvent != candidateEventCached) {
            candidateVersion++
            batch += candidateEvent
        }
        candidateEventCached = candidateEvent
        if (imeChanged) batch += FcitxEvent.IMChangeEvent(ime)
        if (statusChanged) batch += FcitxEvent.StatusAreaEvent(FcitxEvent.StatusAreaEvent.Data(actions, ime))
        batch.forEach { it.inputSession = value.generation }
        publishedGeneration = value.generation
        if (batch.isNotEmpty()) events.trySend(batch)
    }

    private fun clearStatusHint() {
        statusHintJob?.cancel()
        statusHintJob = null
        statusHint = ""
    }

    private fun showStatusHint(value: Snapshot, text: String) {
        clearStatusHint()
        statusHint = text
        statusHintJob = engineScope.launch {
            delay(1500)
            statusHintJob = null
            statusHint = ""
            if (!started || value.generation != inputSession) return@launch
            // Expire only the presentation text, without refreshing native state or replaying commits.
            val panel = inputPanelCached.copy(auxUp = FormattedText.Empty)
            if (panel != inputPanelCached) {
                inputPanelCached = panel
                events.trySend(listOf(FcitxEvent.InputPanelEvent(panel).apply { inputSession = value.generation }))
            }
        }
    }
    private fun entry(value: Snapshot?) = InputMethodEntry("rime", context.getString(R.string.direct_rime_ime_name), "fcitx-rime", "中州韵", "㞢", "zh", "rime", true,
        InputMethodSubMode(if (value?.ascii == true) "Latin Mode" else value?.schemaName.orEmpty(),
            if (value?.ascii == true) "abc" else value?.schemaName.orEmpty(),
            if (value?.ascii == true) "fcitx_rime_latin" else "fcitx-rime:${value?.schema.orEmpty()}"))
    private fun action(id: Int, name: String, text: String = name, checked: Boolean? = null,
                       menu: Array<Action>? = null, separator: Boolean = false) =
        Action(id, separator, checked != null, checked == true, name, "", text, text, menu)
    private fun buildActions(value: Snapshot): Array<Action> {
        val menu = mutableListOf(action(10, "ascii_mode", "西文", value.ascii))
        schemas.forEachIndexed { i, (id, name) -> menu += action(100 + i, id, name, id == value.schema) }
        menu += action(9, "separator", separator = true)
        val options = buildList {
            val groups = mutableSetOf<String>()
            optionRows.forEachIndexed { i, row ->
                if (row[3].isEmpty()) {
                    // Like fcitx5-rime ToggleAction: current state → target state.
                    add(action(1000 + i, row[0], row[1]))
                } else if (groups.add(row[3])) {
                    // Like SelectAction: one button per mutually exclusive group.
                    val members = optionRows.withIndex().filter { it.value[3] == row[3] }
                    val selected = members.firstOrNull { it.value[2] == "1" } ?: members.first()
                    val children = members.map { (index, member) ->
                        action(1000 + index, member[0], member[1], member[2] == "1")
                    }.toTypedArray()
                    add(action(-1000 - i, "select-${row[0]}", selected.value[1], menu = children))
                }
            }
        }
        menu += action(11, "deploy", "重新部署")
        menu += action(12, "sync", "同步用户词典")
        val label = if (value.ascii) "西文" else value.schemaName.ifEmpty { "Rime" }
        return arrayOf(action(1, "rime", label, menu = menu.toTypedArray()), *options.toTypedArray())
    }
    private fun showUnavailable(message: String) {
        inputPanelCached = FcitxEvent.InputPanelEvent.Data(FormattedText.Empty, formatted(message), FormattedText.Empty, emptyArray())
        events.trySend(listOf(FcitxEvent.InputPanelEvent(inputPanelCached)))
    }

    override suspend fun sendKey(sym: Int, states: UInt, code: Int, up: Boolean, timestamp: Int) {
        val expected = inputSession
        if (maintenance) return
        val queuedAt = SystemClock.elapsedRealtimeNanos()
        onOwner {
            if (expected != inputSession || maintenance || !started) return@onOwner
            val ownerAt = SystemClock.elapsedRealtimeNanos()
            val bypass = flags.flags and (CapabilityFlag.Password.flag or CapabilityFlag.Disable.flag) != 0UL
            if (!up) clearStatusHint()
            val result = if (bypass) null else Native.key(expected, sym, states.toInt(), up)
            val nativeAt = SystemClock.elapsedRealtimeNanos()
            if (result != null) publish(result)
            if (!up && result?.ready == true) {
                bindingKeys++
                if (bindingKeys <= 3 || nativeAt - queuedAt >= 100_000_000L)
                    android.util.Log.i("RimeInputTiming", "key generation=$expected ordinal=$bindingKeys owner_wait_us=${(ownerAt - queuedAt) / 1000} native_us=${(nativeAt - ownerAt) / 1000} publish_us=${(SystemClock.elapsedRealtimeNanos() - nativeAt) / 1000} ${nativeTiming()}")
            }
            val keyId = sym to code
            val forward = if (up) forwardedKeys.remove(keyId) else
                (bypass || (result?.ready == true && !result.handled)).also { if (it) forwardedKeys.add(keyId) }
            if (forward) {
                // Preserve hardware down/up pairing and the frontend's virtual key fallback.
                val unicode = when {
                    sym in 0x20..0xff -> sym
                    sym and 0xff000000.toInt() == 0x01000000 -> sym and 0x00ffffff
                    sym in 0x100..0x10ffff && sym !in 0xff00..0xffff -> sym
                    else -> 0
                }
                val event = FcitxEvent.KeyEvent(FcitxEvent.KeyEvent.Data(KeySym(sym), KeyStates(states), unicode, up, timestamp))
                event.inputSession = expected
                events.trySend(listOf(event))
            }
        }
    }
    override suspend fun sendKey(c: Char, states: UInt, code: Int, up: Boolean, timestamp: Int) = sendKey(c.code, states, code, up, timestamp)
    override suspend fun sendKey(key: String, states: UInt, code: Int, up: Boolean, timestamp: Int) {
        val parsed = Key.parse(key)
        sendKey(parsed.sym, states or parsed.states.toUInt(), code, up, timestamp)
    }
    override suspend fun sendKey(sym: KeySym, states: KeyStates, code: Int, up: Boolean, timestamp: Int) = sendKey(sym.sym, states.states, code, up, timestamp)
    private suspend fun performAction(action: Int, index: Int): Snapshot? {
        val expected = snapshot ?: return null
        if (!acceptsInput || expected.generation != inputSession) return null
        val queuedAt = SystemClock.elapsedRealtimeNanos()
        return onOwner {
            if (expected.generation != inputSession || snapshot?.revision != expected.revision) {
                if (action == 1 || action == 11)
                    android.util.Log.i("RimeInputTiming", "select_rejected generation=${expected.generation} reason=stale_snapshot")
                null
            } else {
                val ownerAt = SystemClock.elapsedRealtimeNanos()
                val result = act(action, index)
                if (action == 1 || action == 11) {
                    bindingSelections++
                    if (bindingSelections <= 2 || SystemClock.elapsedRealtimeNanos() - ownerAt >= 100_000_000L)
                        android.util.Log.i("RimeInputTiming", "select generation=${expected.generation} ordinal=$bindingSelections owner_wait_us=${(ownerAt - queuedAt) / 1000} native_publish_us=${(SystemClock.elapsedRealtimeNanos() - ownerAt) / 1000} ${nativeTiming()}")
                }
                result
            }
        }
    }
    override suspend fun select(idx: Int) = idx >= 0 && performAction(if (pagingMode == 0) 11 else 1, idx)?.ready == true
    override suspend fun selectGlobal(idx: Int) = idx >= 0 && performAction(11, idx)?.ready == true
    override suspend fun isEmpty() = onOwner { snapshot?.preedit.isNullOrEmpty() }
    override suspend fun reset() { onOwner { act(0) } }
    override suspend fun moveCursor(position: Int) { onOwner {
        val text = snapshot?.preedit ?: return@onOwner
        act(9, text.take(position.coerceIn(0, text.length)).toByteArray().size)
    } }
    override suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord> {
        val expected = snapshot ?: return emptyArray()
        // Snapshot revisions also advance on unhandled key-up. Only cancel a
        // page when a candidate invalidation was actually published, otherwise
        // Paging would lose its load without receiving a replacement refresh.
        val expectedVersion = candidateVersion
        val count = limit.coerceIn(1, 256)
        if (offset < 0 || offset > Int.MAX_VALUE - count) return emptyArray()
        return readCandidatePage(offset, count) { start, size ->
            onOwner {
                if (!started || maintenance || expected.generation != inputSession ||
                    candidateVersion != expectedVersion) return@onOwner null
                trace("rime.direct.candidate_batch") {
                    Native.candidates(expected.generation, start, size).toList().chunked(2)
                        .map { CandidateWord("", it[0], it[1]) }
                }
            }
        }.toTypedArray()
    }
    override suspend fun getCandidateActions(idx: Int) = arrayOf(CandidateAction(0, "忘记此词", false, "", false, false))
    override suspend fun triggerCandidateAction(idx: Int, actionIdx: Int) { if (actionIdx == 0) performAction(5, idx) }
    override suspend fun triggerGlobalCandidateAction(idx: Int, actionIdx: Int) { if (actionIdx == 0) performAction(12, idx) }
    override suspend fun setCandidatePagingMode(mode: Int) { onOwner { pagingMode = mode; snapshot?.takeIf { it.generation == inputSession }?.let { publishWithoutCommit(it) } } }
    private fun publishWithoutCommit(value: Snapshot) {
        // Native action 9 refreshes without consuming a key; never republish a commit-bearing result.
        act(9, value.cursorBytes)
    }
    override suspend fun offsetCandidatePage(delta: Int) { onOwner { repeat(kotlin.math.abs(delta).coerceAtMost(100)) { act(2, delta) } } }
    override suspend fun triggerCandidateListTabAction(id: Int) { performAction(if (id == -1) 7 else 4, id) }

    override suspend fun availableIme() = arrayOf(inputMethodEntryCached)
    override suspend fun enabledIme() = availableIme()
    override suspend fun setEnabledIme(array: Array<String>) { require(array.contentEquals(arrayOf("rime"))) }
    override suspend fun currentIme() = inputMethodEntryCached
    private fun demandSessionForAction() {
        if (binding?.generation == inputSession && binding?.needsRime == false && requireInputSession(inputSession))
            begin(inputSession)
    }
    override suspend fun toggleIme() { onOwner { demandSessionForAction(); act(6) } }
    override suspend fun activateIme(ime: String) {
        // Android can bind its manifest/default subtype before syncWith replaces it.
        // This edition has one IM; a stale keyboard-us subtype must not crash the service.
        if (ime != "rime") Timber.d("Ignoring an unavailable input method subtype")
    }
    override suspend fun enumerateIme(forward: Boolean) = toggleIme()
    override suspend fun activate(uid: Int, pkgName: String) { onOwner { activeUid = uid; program = pkgName } }
    override suspend fun deactivate(uid: Int) { onOwner { if (uid == activeUid) {
        synchronized(bindingLock) { binding = null }
        clearBinding(inputSession)
        activeUid = -1
    } } }
    override suspend fun focus(focus: Boolean) { onOwner {
        if (!focus) {
            val generation = inputSession
            synchronized(bindingLock) { if (binding?.generation == generation) binding = null }
            clearBinding(generation)
        } else if (snapshot?.generation != inputSession || snapshot?.ready != true) {
            // A focus/show callback must not override an explicitly inactive
            // binding. Actual keys and schema/mode actions promote it first.
            if (binding?.generation == inputSession && binding?.needsRime == false) return@onOwner
            if (binding?.generation == inputSession && binding?.needsRime == true) begin(inputSession)
            else {
                val generation = invalidateInputSession()
                prepareInputSession(generation, program, flags, true)
                begin(generation)
            }
        }
    } }
    override suspend fun focusOutIn() = reset()
    override suspend fun setCapFlags(flags: CapabilityFlags) { onOwner {
        this.flags = flags
        val noLearning = flags.flags and CapabilityFlag.PasswordOrSensitive.flag != 0UL
        if (snapshot?.ready == true && appliedNoLearning != noLearning) {
            if (act(8, if (noLearning) 1 else 0, "_no_learning")?.ready == true) appliedNoLearning = noLearning
        }
    } }
    override suspend fun statusArea() = statusAreaActionsCached
    override suspend fun activateAction(id: Int) {
        when {
            id == 11 -> redeploy()
            id == 12 -> synchronize()
            else -> onOwner { demandSessionForAction(); when {
                id == 10 -> act(6)
                id in 100 until 100 + schemas.size -> act(3, value = schemas[id - 100].first)
                id in 1000 until 1000 + optionRows.size -> {
                    val row = optionRows[id - 1000]
                    if (row[3].isNotEmpty()) optionRows.filter { it[3] == row[3] && it[0] != row[0] }.forEach { act(8, 0, it[0]) }
                    act(8, if (row[3].isNotEmpty() || row[2] != "1") 1 else 0, row[0])
                }
            } }
        }
    }

    private suspend fun maintain(operation: () -> Unit) {
        maintenance = true
        keyGate.block()
        val token = invalidateInputSession()
        try { onOwner {
            showUnavailable("正在部署，请稍候…")
            operation()
            refreshSchemas()
            if (token == inputSession) begin(token)
        } } catch (e: Exception) {
            onOwner {
                if (started && token == inputSession) begin(token)
                showUnavailable(e.message ?: "操作失败，请检查方案文件")
            }
            throw e
        } finally { maintenance = false; keyGate.running(started) }
    }
    private suspend fun redeploy() = maintain { check(Native.deploy()) { "部署失败，请检查方案文件后重新部署" } }
    private suspend fun synchronize() = maintain { check(Native.sync()) { "用户词典同步失败" } }
    private fun restartNative() {
        if (started) Native.stop()
        started = false
        Native.start(shared.absolutePath, config.user.absolutePath)
        started = true
        preferredSchema = ""; preferredAscii = null
        check(Native.deploy()) { "部署失败，请检查方案文件" }
    }
    override fun setLogRule(verbose: Boolean) {}
    override fun translate(str: String, domain: String) = str
    override fun getAddonReverseDependencies(addon: String) = emptyList<Pair<String, FcitxAPI.AddonDep>>()
    override suspend fun save() { onOwner { if (started) Native.sync() } }
    override suspend fun saveNonRimeState() {}
    override suspend fun reloadConfig() = maintain { config.load(); restartNative() }
    override suspend fun getGlobalConfig() = getImConfig("rime")
    override suspend fun setGlobalConfig(config: RawConfig) = setImConfig("rime", config)
    override suspend fun getAddonConfig(addon: String) = getImConfig(addon)
    override suspend fun setAddonConfig(addon: String, config: RawConfig) = setImConfig(addon, config)
    override suspend fun getImConfig(key: String) = onOwner { config.describe() }
    override suspend fun setImConfig(key: String, config: RawConfig) { onOwner { this.config.setOptions(config); act(9, snapshot?.cursorBytes ?: 0) } }
    override suspend fun addons() = arrayOf(AddonInfo("rime", "Rime", "librime 直连引擎", AddonCategory.InputMethod, true, true, true, false))
    override suspend fun setAddonState(name: Array<String>, state: BooleanArray) { require(state.all { it }) { "Rime 引擎不可关闭" } }
    override suspend fun getAddonSubConfig(addon: String, path: String) = onOwner { when(path) {
        "profile-manager" -> config.directoryState()
        "schema-selector" -> RawConfig(arrayOf(RawConfig("Items", if (started) Native.schemaSelection() else "")))
        else -> RawConfig()
    } }
    override suspend fun setAddonSubConfig(addon: String, path: String, config: RawConfig) {
        when(path) {
            "deploy" -> redeploy()
            "sync" -> synchronize()
            "schema-selector" -> maintain {
                val ids = config.findByName("Items")?.value.orEmpty().lines().mapNotNull {
                    val row = it.split('\t', limit = 3); if (row.size == 3 && row[0] == "1") row[1] else null
                }
                check(Native.selectSchemas(ids.toTypedArray())) { "无法保存输入方案选择" }
                check(Native.deploy()) { "部署失败，请检查方案文件" }
            }
            "profile-manager" -> maintain {
                val name = config.findByName("Name")?.value.orEmpty()
                val operation = config.findByName("Action")?.value.orEmpty()
                try {
                    when (operation) {
                        "create" -> this.config.create(name, config.findByName("ExternalPath")?.value.orEmpty())
                        "switch" -> { this.config.switch(name); restartNative() }
                        "rename" -> {
                            val active = name == this.config.current
                            if (active && started) { Native.stop(); started = false }
                            try { this.config.rename(name, config.findByName("NewName")?.value.orEmpty()) }
                            finally { if (active) restartNative() }
                        }
                        "delete" -> this.config.delete(name)
                        else -> error("无效目录操作")
                    }
                    this.config.error = ""
                } catch (e: Exception) { this.config.error = e.message.orEmpty(); throw e }
            }
            "import-profile" -> maintain {
                val pathValue = config.findByName("Archive")?.value ?: error("请选择方案 ZIP")
                val archive = File(pathValue).canonicalFile
                require(archive.parentFile == File(context.cacheDir, "rime-import").canonicalFile) { "无效导入路径" }
                val name = "import-${System.currentTimeMillis()}"
                val staging = File(this.config.dataRoot, ".$name")
                archive.inputStream().use { ProfileArchive.extract(it, staging) }
                val target = File(this.config.dataRoot, name)
                check(staging.renameTo(target)) { "无法安装方案文件" }
                val previous = this.config.current
                this.config.create(name)
                try { this.config.switch(name); restartNative() }
                catch (e: Exception) {
                    this.config.switch(previous)
                    restartNative()
                    throw e
                }
            }
        }
    }
}
