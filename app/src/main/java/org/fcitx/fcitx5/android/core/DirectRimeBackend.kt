/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.tracing.trace
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.directrime.Native
import org.fcitx.directrime.ProfileArchive
import org.fcitx.directrime.Snapshot
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.engine.ResolvedRimeEngine
import org.fcitx.fcitx5.android.core.engine.RimeEngineKind
import org.fcitx.fcitx5.android.core.engine.RimeEnginePlugins
import org.fcitx.fcitx5.android.core.engine.RimeEngineSelection
import org.fcitx.fcitx5.android.core.engine.RimeEngineStore
import org.fcitx.fcitx5.android.data.WanxiangPayload
import org.fcitx.fcitx5.android.data.WanxiangUpdateTransaction
import org.fcitx.fcitx5.android.data.WanxiangAdvancedFiles
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private val EMPTY_CANDIDATE_ACTIONS = emptyArray<CandidateAction>()

/** Original frontend contract implemented directly on librime's single owner thread. */
class DirectRimeBackend(context: Context) : InputEngine {
    private val context = FcitxApplication.getInstance().directBootAwareContext
    private val directBootMode = FcitxApplication.getInstance().isDirectBootMode
    private val owner = Executors.newSingleThreadExecutor { Thread(it, "rime-main") }.asCoroutineDispatcher()
    private val interactivePriority = InteractiveThreadPriority()
    private val engineScope = CoroutineScope(SupervisorJob() + owner)
    private val registry = FcitxLifecycleRegistry()
    override val lifecycle: FcitxLifecycle get() = registry
    override val isReady get() = registry.currentState == FcitxLifecycle.State.READY && started
    private val epoch = AtomicLong()
    private val bindingLock = Any()
    private val keyGate = InputKeyGate()
    private val maintenanceMutex = Mutex()
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
            if (next != binding) { binding = next; keyGate.prepare(generation, requested) }
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
    private val events = InputEventQueue()
    private val flow = MutableSharedFlow<FcitxEvent<*>>(extraBufferCapacity = 64)
    override val eventFlow = flow.asSharedFlow()
    private val config = DirectRimeConfig(this.context)
    private val engineRepository by lazy { RimeEngineStore.repository(this.context) }
    private var activeEngineSelection = RimeEngineSelection.Builtin
    private var newEngineActivation = false
    @Volatile private var started = false
    @Volatile private var snapshot: Snapshot? = null
    private var program = ""
    private var activeUid = -1
    private var flags = CapabilityFlags.DefaultFlags
    private var appliedNoLearning: Boolean? = null
    private var bindingKeys = 0
    private var bindingSelections = 0
    private var pagingMode = 1
    private var schemas = emptyList<Pair<String, String>>()
    private var optionRows = emptyList<List<String>>()
    private var optionsRevision = Long.MIN_VALUE
    private var publishedGeneration = Long.MIN_VALUE
    private var candidateEventCached: FcitxEvent<*>? = null
    @Volatile private var candidateVersion = 0L
    override val candidateActionContext get() = CandidateActionContext(inputSession, candidateVersion)
    override val candidateTabActionContext get() = snapshot?.let {
        CandidateTabActionContext(it.generation, it.tabsRevision)
    } ?: CandidateTabActionContext(inputSession, 0)
    private val forwardedKeys = mutableSetOf<Pair<Int, Int>>()
    private var startupError = ""
    private var statusHint = ""
    private var statusHintJob: Job? = null
    private val statusHintLifetime = StatusHintLifetime()
    private var statusHintGeneration = 0L
    private val inputMethodEntryCache = DirectRimeInputMethodEntryCache()
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
            while (true) for (event in events.receive()) {
                InputDiagnostics.emitting(event)
                flow.emit(event)
            }
        }
    }

    private val runtime = EngineLifecycleController(
        scope = engineScope,
        registry = registry,
        onStarting = {
            keyGate.starting()
            showUnavailable("正在准备中州韵…")
        },
        initialize = {
            try {
                startupStage("assets") { DataManager.sync() }
                startupStage("config") { config.load() }
                newEngineActivation = startupStage("engine-selection") { configureSelectedEngine() }
                startupStage("native") { Native.start(shared.absolutePath, config.user.absolutePath, BuildConfig.VERSION_NAME,
                    fullCheck = newEngineActivation || WanxiangAdvancedFiles.needsDeploymentRepair(config.user)) }
                started = true
                startupError = ""
                refreshSchemas()
                if (!directBootMode) engineRepository.activationSucceeded(activeEngineSelection)
            } catch (e: Exception) {
                val failedSelection = activeEngineSelection
                if (!directBootMode && failedSelection.kind != RimeEngineKind.BUILTIN) {
                    engineRepository.activationFailed(failedSelection,
                        "外部引擎启动失败：${e.message ?: e.javaClass.simpleName}")
                    // The next process starts from the persisted fallback. Do not attempt to
                    // initialize another librime in a process where external constructors or
                    // setup may already have run, including an ordinary restart of a stable DSO.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }, 500)
                }
                startupError = e.message ?: "引擎启动失败"
                Timber.e(e, "Direct Rime startup failed")
                showUnavailable(startupError)
            }
        },
        onReady = {
            keyGate.running(started)
        },
        afterReady = {
            events.offer(listOf(FcitxEvent.ReadyEvent()))
            if (Build.VERSION.SDK_INT >= 34) engineScope.launch { SubtypeManager.syncWith(enabledIme()) }
        },
        onStopping = {
            invalidateInputSession()
            keyGate.stop()
        },
        finalize = {
            clearStatusHint()
            try { if (started) Native.stop() }
            finally { started = false; snapshot = null }
        },
    )

    override fun start() = runtime.start()
    override fun requestStop() {
        val shutdown = runtime.stopAsync()
        engineScope.launch {
            try { shutdown.await() }
            catch (e: Exception) { Timber.e(e, "Direct Rime shutdown failed") }
        }
    }

    // Backup/restore callers need a completed close before touching Rime's files.
    override fun stop() { runBlocking { runtime.stopAsync().await() } }

    private val shared get() = File(DataManager.dataDir, "usr/share/rime-data")
    private fun configureSelectedEngine(): Boolean {
        if (directBootMode) {
            activeEngineSelection = RimeEngineSelection.Builtin
            return false
        }
        val (state, pendingActivation) = engineRepository.beginActivation()
        newEngineActivation = pendingActivation
        val plugins = if (state.active.kind == RimeEngineKind.PLUGIN) {
            RimeEnginePlugins.discover(context)
        } else emptyList()
        var resolved = engineRepository.resolveActive(plugins)
        var fellBackDuringResolution = false
        if (resolved.selection != state.active) {
            fellBackDuringResolution = true
            engineRepository.activationFailed(state.active,
                "已选择的外部引擎文件、签名或安装状态发生变化，已回退到内置引擎")
            resolved = ResolvedRimeEngine.Builtin
        }
        // Record the selected external engine before validating its profile so a removed or
        // renamed directory follows the same persisted fallback path as native load failures.
        activeEngineSelection = resolved.selection
        val profiles = config.directoryState().findByName("Profiles")?.value.orEmpty()
            .lineSequence().filter(String::isNotBlank).toSet()
        val targetProfile = when (resolved) {
            ResolvedRimeEngine.Builtin -> state.builtinProfile
                ?.takeIf { (pendingActivation || state.restoreBuiltinProfile || fellBackDuringResolution) && it in profiles }
            is ResolvedRimeEngine.External -> resolved.profile.also {
                require(it in profiles) { "引擎绑定的方案工作目录不存在：$it" }
            }
        }
        if (targetProfile != null && targetProfile != config.current) config.switch(targetProfile)
        if (resolved is ResolvedRimeEngine.External) {
            val file = File(resolved.filePath)
            require(file.isFile) { "外部引擎文件不存在" }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                Native.configureExternal(descriptor.fd, resolved.entrySymbol, resolved.payloadSha256)
            }
        }
        return pendingActivation && resolved.selection == state.active
    }
    private inline fun <T> startupStage(name: String, crossinline block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        return try { trace("rime.direct.startup.$name") { block() } }
        finally { android.util.Log.i("RimeLifecycle", "startup phase=$name duration_ms=${SystemClock.elapsedRealtime() - start}") }
    }
    private suspend fun <T> onOwner(block: () -> T): T = withContext(owner) { block() }
    private fun nativeTiming(values: LongArray = Native.operationTiming()): String =
        "core_us=${values[0] / 1000} read_commit_us=${values[1] / 1000} read_context_us=${values[2] / 1000} read_status_us=${values[3] / 1000} input_tabs_us=${values[4] / 1000} core_cpu_us=${if (values[5] < 0) -1 else values[5] / 1000} core_minflt=${values[6]} core_majflt=${values[7]} core_nvcsw=${values[8]} core_nivcsw=${values[9]} io_open_us=${values[10] / 1000} io_read_us=${values[11] / 1000} io_write_us=${values[12] / 1000} io_sync_us=${values[13] / 1000} io_stat_us=${values[14] / 1000} io_calls=${values[15]} input_priority=${interactivePriority.lastPriority}"
    private fun refreshSchemas() {
        schemas = Native.schemaEntries().toList().chunked(2).map { it[0] to it[1] }
    }
    private fun forwardsKeysDirectly(): Boolean = directBootMode ||
        flags.flags and (CapabilityFlag.Password.flag or CapabilityFlag.Disable.flag) != 0UL

    private fun bindingUsable(value: Snapshot): Boolean = value.ready ||
        // No Chinese schema is expected before first unlock. Direct input still needs the
        // same generation/ticket/maintenance fences, but does not need a native session.
        (value.gate == 2 && forwardsKeysDirectly())

    private fun hasUsableBinding(generation: Long): Boolean =
        snapshot?.let { it.generation == generation && bindingUsable(it) } == true

    private fun begin(generation: Long) {
        if (!started || generation != inputSession) return
        val request = binding?.takeIf { it.generation == generation }
        if (request?.needsRime == false) { clearBinding(generation); return }
        if (request == null) prepareInputSession(generation, program, flags, true)
        val target = binding?.takeIf { it.generation == generation } ?: return
        program = target.program
        flags = target.flags
        val startedAt = SystemClock.elapsedRealtime()
        val noLearning = directBootMode || flags.flags and CapabilityFlag.PasswordOrSensitive.flag != 0UL
        // Native binding restores Rime's schema/options and can do synchronous I/O.
        // Keep it and the first status read at interactive priority, including failure paths.
        val details = interactivePriority.run {
            val before = Native.sessionStats()
            val beginAt = SystemClock.elapsedRealtimeNanos()
            val result = Native.beginInput(generation, program, noLearning)
            val beginDone = SystemClock.elapsedRealtimeNanos()
            val beginTiming = if (BuildConfig.INPUT_DIAGNOSTICS) Native.operationTiming() else null
            // Rime already applies saved options, schema resets and its schema-selection
            // policy. Replaying an earlier editor's snapshot would override that result.
            if (result.ready) appliedNoLearning = noLearning
            val publishAt = SystemClock.elapsedRealtimeNanos()
            publish(result)
            bindingKeys = 0
            bindingSelections = 0
            val usable = bindingUsable(result)
            keyGate.resolve(generation, usable)
            val publishDone = SystemClock.elapsedRealtimeNanos()
            val after = Native.sessionStats()
            // A few clocks/counters per binding remain enabled in ordinary builds. Per-key
            // CPU/fault/I/O instrumentation is still restricted to INPUT_DIAGNOSTICS builds.
            "ready=${result.ready} usable=$usable direct=${forwardsKeysDirectly()} no_learning=$noLearning" +
                " created=${after[0] - before[0]} reused=${after[1] - before[1]}" +
                " native_begin_us=${(beginDone - beginAt) / 1000}" +
                " publish_us=${(publishDone - publishAt) / 1000}" +
                if (beginTiming != null) " ${nativeTiming(beginTiming)}" else ""
        }
        android.util.Log.i("RimeLifecycle", "bind generation=$generation duration_ms=${SystemClock.elapsedRealtime() - startedAt} $details")
    }
    override suspend fun startInputSession(generation: Long, program: String) = onOwner {
        if (generation != inputSession) return@onOwner
        if (binding?.generation != generation) prepareInputSession(generation, program, flags, true)
        if (hasUsableBinding(generation) && binding?.needsRime == true) {
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
        events.offer(listOf(
            FcitxEvent.ClientPreeditEvent(FormattedText.Empty),
            FcitxEvent.InputPanelEvent(inputPanelCached),
            FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data.Empty)
        ).onEach { it.inputSession = generation })
        android.util.Log.i("RimeLifecycle", "binding_inactive generation=$generation native_create=false")
    }
    private fun act(action: Int, index: Int = 0, value: String = "", forceCandidates: Boolean = false,
                    statisticsSource: InputStatisticsSource? = null): Snapshot? {
        val previous = snapshot ?: return null
        if (!started || maintenance || previous.generation != inputSession) return null
        val collectStatistics = shouldCollectStatistics(statisticsSource, previous)
        val wallTime = if (collectStatistics) System.currentTimeMillis() else 0L
        val monotonicTime = if (collectStatistics) SystemClock.elapsedRealtime() else 0L
        return interactivePriority.run {
            Native.action(previous.generation, previous.revision, action, index, value, collectStatistics).also {
                val statistics = if (collectStatistics) statisticsFromResult(statisticsSource!!, previous, it, wallTime, monotonicTime) else null
                publish(it, forceCandidates = forceCandidates, statistics = statistics)
            }
        }
    }

    private fun shouldCollectStatistics(source: InputStatisticsSource?, previous: Snapshot?): Boolean {
        if (source == null || previous == null || !previous.ready || directBootMode || forwardsKeysDirectly() ||
            source.generation != inputSession || previous.generation != inputSession ||
            flags.flags and CapabilityFlag.PasswordOrSensitive.flag != 0UL) return false
        return try { source.stillEnabled() } catch (_: Exception) { false }
    }

    private fun statisticsFromResult(source: InputStatisticsSource, previous: Snapshot, result: Snapshot,
                                     wallTime: Long, monotonicTime: Long): InputOperationStatistics? {
        if (result.rawInputLength < 0) return null
        return InputOperationStatistics(source.epoch, previous.schema, previous.schemaName,
            result.rawInputLength + 1, result.inputWasCommand, result.preedit.isNotEmpty(), wallTime, monotonicTime)
    }
    private fun formatted(text: String, cursor: Int = text.length, underline: Boolean = false) =
        if (text.isEmpty()) FormattedText.Empty else FormattedText(arrayOf(text),
            intArrayOf(if (underline) TextFormatFlag.Underline.flag else 0), cursor.coerceIn(0, text.length))

    private fun publish(value: Snapshot, release: Boolean = false, forceCandidates: Boolean = false,
                        statistics: InputOperationStatistics? = null, statisticsKey: Int? = null) {
        if (value.generation != inputSession || value.gate == 3) return
        val previous = snapshot
        snapshot = value
        val directInput = forwardsKeysDirectly()
        // Keep the new revision for subsequent selection, but avoid repacking unchanged UI arrays.
        // A handled release can invalidate lazy candidate tails even if the visible page is equal.
        if (release && value.isUnchangedRelease(previous)) return
        val newSession = publishedGeneration != value.generation
        val modeChanged = !newSession && previous?.ready == true && value.ready &&
            (previous.schema != value.schema || previous.ascii != value.ascii)
        if (modeChanged && !directInput) {
            showStatusHint(value, if (previous!!.schema != value.schema) value.schemaName.ifEmpty { value.schema } else
                context.getString(if (value.ascii) R.string.direct_rime_ascii_mode else R.string.direct_rime_chinese_mode))
        } else if (newSession || !value.ready || value.preedit.isNotEmpty() || value.commit.isNotEmpty()) {
            clearStatusHint()
        }
        val optionsChanged = value.ready && value.optionsRevision != optionsRevision
        if (value.ready) {
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
        val tabContext = CandidateTabActionContext(value.generation, value.tabsRevision)
        val tabs = if (value.tabs.isEmpty()) EMPTY_CANDIDATE_ACTIONS else Array(value.tabs.size) { i ->
            CandidateAction(i, value.tabs[i], false, "", false, false, CandidateTabActionTarget(tabContext, i))
        }
        val panel = FcitxEvent.InputPanelEvent.Data(
            if (config.preeditMode == "No") formatted(value.preedit, value.cursorUtf16) else FormattedText.Empty,
            if (directInput && bindingUsable(value)) FormattedText.Empty
            else if (value.ready) formatted(statusHint) else formatted(startupError.ifEmpty { value.gateMessage }),
            FormattedText.Empty, tabs, statusHintId = if (value.ready) statusHintLifetime.id else 0
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
        if (statistics != null && statisticsKey != null && value.ready)
            batch += FcitxEvent.StatisticsKeyEvent(statistics, statisticsKey)
        if (value.commit.isNotEmpty()) batch += FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data(value.commit, -1)).apply {
            commitProducedNanos = SystemClock.elapsedRealtimeNanos()
            this.statistics = statistics
        }
        if (clientChanged) batch += FcitxEvent.ClientPreeditEvent(client)
        if (panelChanged) batch += FcitxEvent.InputPanelEvent(panel)
        val candidates = Array(value.candidates.size) { i ->
            // Candidate codes are separate metadata, not a schema's visible comment.
            // Appending them would bypass Lua filters such as Wanxiang's comment switch.
            CandidateWord(value.labels.getOrElse(i) { "${(i + 1) % 10} " }, value.candidates[i],
                value.comments.getOrElse(i) { "" })
        }
        val candidateEvent = if (pagingMode != 0) FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data(
            candidates, value.highlighted, FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet, value.page > 0, !value.lastPage))
        else FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data(-1, candidates))
        // An unchanged first page can hide a changed tail of a lazy translation.
        // Keep handled keys/actions and composition changes invalidating expanded pages.
        val invalidateCandidates = newSession || value.handled || optionsChanged || previous?.preedit != value.preedit ||
            previous?.cursorBytes != value.cursorBytes || candidateEvent != candidateEventCached
        if (invalidateCandidates) candidateVersion++
        val candidateContext = CandidateActionContext(value.generation, candidateVersion)
        candidates.forEachIndexed { index, candidate ->
            // The native snapshot contains the current Rime page, even when a UI
            // chooses a different presentation mode. Keep that source identity.
            candidate.selectionTarget = CandidateSelectionTarget(candidateContext, index, global = false)
        }
        candidateEvent.candidateActionContext = candidateContext
        if (invalidateCandidates || forceCandidates) batch += candidateEvent
        candidateEventCached = candidateEvent
        if (imeChanged) batch += FcitxEvent.IMChangeEvent(ime)
        if (statusChanged) batch += FcitxEvent.StatusAreaEvent(FcitxEvent.StatusAreaEvent.Data(actions, ime))
        batch.forEach { it.inputSession = value.generation }
        publishedGeneration = value.generation
        if (batch.isNotEmpty()) events.offer(batch)
    }

    private fun clearStatusHint() {
        statusHintJob?.cancel()
        statusHintJob = null
        statusHint = ""
        statusHintLifetime.clear()
        statusHintGeneration = 0
    }

    private fun showStatusHint(value: Snapshot, text: String) {
        clearStatusHint()
        statusHint = text
        statusHintGeneration = value.generation
        val id = statusHintLifetime.start(SystemClock.uptimeMillis())
        scheduleStatusHintExpiry(value.generation, id)
    }

    override suspend fun statusHintPresented(hintId: Long, frameUptimeMillis: Long) {
        onOwner {
            if (started && statusHintGeneration == inputSession &&
                statusHintLifetime.presented(hintId, frameUptimeMillis.coerceAtMost(SystemClock.uptimeMillis()))) {
                scheduleStatusHintExpiry(statusHintGeneration, hintId)
            }
        }
    }

    private fun scheduleStatusHintExpiry(generation: Long, hintId: Long) {
        statusHintJob?.cancel()
        statusHintJob = engineScope.launch {
            delay(statusHintLifetime.remaining(SystemClock.uptimeMillis()))
            if (statusHintLifetime.id != hintId) return@launch
            statusHintJob = null
            statusHint = ""
            statusHintLifetime.clear()
            if (!started || generation != inputSession) return@launch
            // Expire only the presentation text, without refreshing native state or replaying commits.
            val panel = inputPanelCached.copy(auxUp = FormattedText.Empty, statusHintId = 0)
            if (panel != inputPanelCached) {
                inputPanelCached = panel
                events.offer(listOf(FcitxEvent.InputPanelEvent(panel).apply { inputSession = generation }))
            }
        }
    }
    private fun entry(value: Snapshot?): InputMethodEntry {
        val ascii = value?.ascii == true || forwardsKeysDirectly()
        return inputMethodEntryCache.get(ascii, value?.schema.orEmpty(), value?.schemaName.orEmpty(),
            context.getString(R.string.direct_rime_ime_name))
    }
    private fun action(id: Int, name: String, text: String = name, checked: Boolean? = null,
                       menu: Array<Action>? = null, separator: Boolean = false) =
        Action(id, separator, checked != null, checked == true, name, "", text, text, menu)
    private fun buildActions(value: Snapshot): Array<Action> {
        // The label describes the next mode; checked still reports Rime's current ASCII state.
        val targetMode = context.getString(if (value.ascii) R.string.direct_rime_chinese_mode else R.string.direct_rime_ascii_mode)
        val menu = mutableListOf(action(10, "ascii_mode", targetMode, value.ascii))
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
        val label = if (value.ascii) context.getString(R.string.direct_rime_ascii_mode) else value.schemaName.ifEmpty { "Rime" }
        return arrayOf(action(1, "rime", label, menu = menu.toTypedArray()), *options.toTypedArray())
    }
    private fun showUnavailable(message: String) {
        inputPanelCached = FcitxEvent.InputPanelEvent.Data(FormattedText.Empty, formatted(message), FormattedText.Empty, emptyArray())
        events.offer(listOf(FcitxEvent.InputPanelEvent(inputPanelCached)))
    }

    override suspend fun sendKey(sym: Int, states: UInt, code: Int, up: Boolean, timestamp: Int) {
        val expected = inputSession
        // Rime can commit on a release too (for example a schema's release binding).
        // Only DOWN produces a key counter, but either half may carry commit metadata.
        val statisticsSource = currentCoroutineContext()[InputStatisticsSource]
        val diagnosticTask = if (BuildConfig.INPUT_DIAGNOSTICS)
            currentCoroutineContext()[InputDiagnostics.ServiceTask] else null
        if (maintenance) return
        val queuedAt = if (BuildConfig.INPUT_DIAGNOSTICS) SystemClock.elapsedRealtimeNanos() else 0L
        events.awaitCapacity()
        val admittedAt = if (BuildConfig.INPUT_DIAGNOSTICS) SystemClock.elapsedRealtimeNanos() else 0L
        onOwner {
            if (expected != inputSession || maintenance || !started) return@onOwner
            val ownerAt = if (BuildConfig.INPUT_DIAGNOSTICS) SystemClock.elapsedRealtimeNanos() else 0L
            val bypass = forwardsKeysDirectly()
            if (!up) clearStatusHint()
            val previous = snapshot
            val collectStatistics = !bypass && shouldCollectStatistics(statisticsSource, previous)
            val wallTime = if (collectStatistics) System.currentTimeMillis() else 0L
            val monotonicTime = if (collectStatistics) SystemClock.elapsedRealtime() else 0L
            val result = if (bypass) null else interactivePriority.run { Native.key(expected, sym, states.toInt(), up, collectStatistics) }
            val nativeAt = if (BuildConfig.INPUT_DIAGNOSTICS) SystemClock.elapsedRealtimeNanos() else 0L
            if (result != null) {
                val statistics = if (collectStatistics) statisticsFromResult(statisticsSource!!, previous!!, result, wallTime, monotonicTime) else null
                publish(result, release = up, statistics = statistics, statisticsKey = if (up) null else sym)
            }
            if (!up && result?.ready == true) {
                bindingKeys++
                if (BuildConfig.INPUT_DIAGNOSTICS && (bindingKeys <= 3 || nativeAt - queuedAt >= 100_000_000L))
                    android.util.Log.i("RimeInputTiming", "key generation=$expected ordinal=$bindingKeys service_task=${diagnosticTask?.id ?: 0} service_category=${diagnosticTask?.category ?: "none"} admission_wait_us=${(admittedAt - queuedAt) / 1000} owner_wait_us=${(ownerAt - admittedAt) / 1000} native_us=${(nativeAt - ownerAt) / 1000} publish_us=${(SystemClock.elapsedRealtimeNanos() - nativeAt) / 1000} ${nativeTiming()}")
            }
            val keyId = sym to code
            val forward = if (up) forwardedKeys.remove(keyId) else
                (bypass || (result?.ready == true && !result.handled)).also { if (it) forwardedKeys.add(keyId) }
            if (forward) {
                // Preserve hardware down/up pairing and the frontend's virtual key fallback.
                val unicode = KeySym(sym).editorUnicode
                val event = FcitxEvent.KeyEvent(FcitxEvent.KeyEvent.Data(KeySym(sym), KeyStates(states), unicode, up, timestamp))
                event.inputSession = expected
                events.offer(listOf(event))
            }
        }
    }
    override suspend fun sendKey(c: Char, states: UInt, code: Int, up: Boolean, timestamp: Int) =
        sendKey(KeyCodec.fromUnicode(c.code), states, code, up, timestamp)
    override suspend fun sendKey(key: String, states: UInt, code: Int, up: Boolean, timestamp: Int) {
        val parsed = Key.parse(key)
        sendKey(parsed.sym, states or parsed.states.toUInt(), code, up, timestamp)
    }
    override suspend fun sendKey(sym: KeySym, states: KeyStates, code: Int, up: Boolean, timestamp: Int) = sendKey(sym.sym, states.states, code, up, timestamp)
    private suspend fun performAction(
        action: Int,
        index: Int,
        candidateContext: CandidateActionContext? = null,
        tabContext: CandidateTabActionContext? = null
    ): Snapshot? {
        val expected = snapshot ?: return null
        val statisticsSource = if (action == 1 || action == 11) currentCoroutineContext()[InputStatisticsSource] else null
        val diagnosticTask = if (BuildConfig.INPUT_DIAGNOSTICS)
            currentCoroutineContext()[InputDiagnostics.ServiceTask] else null
        if (!acceptsInput || expected.generation != inputSession ||
            (candidateContext != null && candidateContext != candidateActionContext) ||
            (tabContext != null && tabContext != candidateTabActionContext)) return null
        val queuedAt = if (BuildConfig.INPUT_DIAGNOSTICS) SystemClock.elapsedRealtimeNanos() else 0L
        events.awaitCapacity()
        val admittedAt = if (BuildConfig.INPUT_DIAGNOSTICS) SystemClock.elapsedRealtimeNanos() else 0L
        return onOwner {
            if (expected.generation != inputSession || snapshot?.revision != expected.revision ||
                (candidateContext != null && candidateContext != candidateActionContext) ||
                (tabContext != null && tabContext != candidateTabActionContext)) {
                if (action == 1 || action == 11)
                    android.util.Log.i("RimeInputTiming", "select_rejected generation=${expected.generation} service_task=${diagnosticTask?.id ?: 0} reason=stale_snapshot")
                null
            } else {
                val ownerAt = if (BuildConfig.INPUT_DIAGNOSTICS) SystemClock.elapsedRealtimeNanos() else 0L
                val result = act(action, index, statisticsSource = statisticsSource)
                if (action == 1 || action == 11) {
                    bindingSelections++
                    if (BuildConfig.INPUT_DIAGNOSTICS && (bindingSelections <= 2 || SystemClock.elapsedRealtimeNanos() - ownerAt >= 100_000_000L))
                        android.util.Log.i("RimeInputTiming", "select generation=${expected.generation} ordinal=$bindingSelections service_task=${diagnosticTask?.id ?: 0} service_category=${diagnosticTask?.category ?: "none"} admission_wait_us=${(admittedAt - queuedAt) / 1000} owner_wait_us=${(ownerAt - admittedAt) / 1000} native_publish_us=${(SystemClock.elapsedRealtimeNanos() - ownerAt) / 1000} ${nativeTiming()}")
                }
                result
            }
        }
    }
    override suspend fun select(idx: Int) = idx >= 0 && performAction(if (pagingMode == 0) 11 else 1, idx)?.ready == true
    override suspend fun selectGlobal(idx: Int) = idx >= 0 && performAction(11, idx)?.ready == true
    override suspend fun selectCandidateIfCurrent(idx: Int, global: Boolean, expected: CandidateActionContext) =
        idx >= 0 && performAction(if (global) 11 else 1, idx, expected)?.ready == true
    override suspend fun isEmpty() = onOwner { snapshot?.preedit.isNullOrEmpty() }
    override suspend fun reset() { onOwner { act(0) } }
    override suspend fun moveCursor(position: Int) { onOwner { moveCursorOnOwner(position, inputSession, null) } }
    override suspend fun moveCursorIfCurrent(position: Int, expectedSession: Long, expectedPreedit: String) =
        onOwner { moveCursorOnOwner(position, expectedSession, expectedPreedit) }

    private fun moveCursorOnOwner(position: Int, expectedSession: Long, expectedPreedit: String?): Boolean {
        val value = snapshot ?: return false
        if (!started || maintenance || !value.ready || value.generation != inputSession || expectedSession != inputSession)
            return false
        fun restoreClientCursor() {
            events.offer(listOf(FcitxEvent.ClientPreeditEvent(clientPreeditCached).apply { inputSession = value.generation }))
        }
        // CommitPreview is a prediction, not an editable representation of raw input.
        if (config.preeditMode != "ComposingText" ||
            (expectedPreedit != null && expectedPreedit != clientPreeditCached.toString())) {
            restoreClientCursor()
            return false
        }
        val raw = Native.rawInput(value.generation, value.revision)
        val target = raw?.let { preeditCursorToRawBytes(value.preedit, value.cursorUtf16, it.text, it.caretBytes, position) }
        if (raw == null || target == null) {
            restoreClientCursor()
            return false
        }
        if (target == raw.caretBytes) {
            restoreClientCursor()
            return true
        }
        return act(9, target)?.ready == true
    }
    override suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord> {
        val expected = snapshot ?: return emptyArray()
        // Snapshot revisions also advance on unhandled key-up. Only cancel a
        // page when a candidate invalidation was actually published, otherwise
        // Paging would lose its load without receiving a replacement refresh.
        val expectedVersion = candidateVersion
        val selectionContext = CandidateActionContext(expected.generation, expectedVersion)
        val count = limit.coerceIn(1, 256)
        if (offset < 0 || offset > Int.MAX_VALUE - count) return emptyArray()
        return readCandidatePage(offset, count) { start, size ->
            onOwner {
                if (!started || maintenance || expected.generation != inputSession ||
                    candidateVersion != expectedVersion) return@onOwner null
                trace("rime.direct.candidate_batch") {
                    Native.candidates(expected.generation, start, size).toList().chunked(2)
                        .mapIndexed { index, word ->
                            CandidateWord("", word[0], word[1]).apply {
                                selectionTarget = CandidateSelectionTarget(selectionContext, start + index, global = true)
                            }
                        }
                }
            }
        }.toTypedArray()
    }
    override suspend fun getCandidateActions(idx: Int) = arrayOf(CandidateAction(0, "忘记此词", false, "", false, false))
    override suspend fun triggerCandidateAction(idx: Int, actionIdx: Int) { if (actionIdx == 0) performAction(5, idx) }
    override suspend fun triggerGlobalCandidateAction(idx: Int, actionIdx: Int) { if (actionIdx == 0) performAction(12, idx) }
    override suspend fun triggerCandidateActionIfCurrent(
        idx: Int, actionIdx: Int, global: Boolean, expected: CandidateActionContext
    ): Boolean = idx >= 0 && actionIdx == 0 && performAction(if (global) 12 else 5, idx, expected)?.ready == true

    override suspend fun activateSchemaActionIfCurrent(id: Int, expectedInputSession: Long): Boolean = onOwner {
        if (inputSession != expectedInputSession || id !in 100 until 100 + schemas.size) false
        else {
            demandSessionForAction()
            act(3, value = schemas[id - 100].first)?.ready == true
        }
    }
    override suspend fun setCandidatePagingMode(mode: Int) { onOwner { pagingMode = mode; refreshPresentation(forceCandidates = true) } }
    private fun refreshPresentation(forceCandidates: Boolean = false) {
        // Read fresh output; never move raw input or replay a commit-bearing cached snapshot.
        act(13, forceCandidates = forceCandidates)
    }
    override suspend fun offsetCandidatePage(delta: Int) { onOwner { repeat(kotlin.math.abs(delta).coerceAtMost(100)) { act(2, delta) } } }
    override suspend fun triggerCandidateListTabAction(id: Int) { performAction(if (id == -1) 7 else 4, id) }
    override suspend fun triggerCandidateListTabActionIfCurrent(target: CandidateTabActionTarget): Boolean =
        target.id >= -1 &&
            performAction(if (target.id == -1) 7 else 4, target.id, tabContext = target.context)?.ready == true

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
        } else if (!hasUsableBinding(inputSession)) {
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
        val noLearning = directBootMode || flags.flags and CapabilityFlag.PasswordOrSensitive.flag != 0UL
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

    private fun restoreMaintenanceBinding(token: Long, previous: Binding?) {
        if (!started || token != inputSession || hasUsableBinding(token)) return
        val current = synchronized(bindingLock) { binding?.takeIf { it.generation == token } }
        if (current == null && previous != null) {
            prepareInputSession(token, previous.program, previous.flags, previous.needsRime)
        }
        if (synchronized(bindingLock) { binding?.generation == token }) begin(token)
        else clearBinding(token)
    }

    private suspend fun maintain(operation: () -> Unit) = maintenanceMutex.withLock {
        maintenance = true
        keyGate.block()
        val (token, previousBinding) = synchronized(bindingLock) {
            val previous = binding
            val next = epoch.incrementAndGet()
            binding = null
            keyGate.invalidate(next)
            next to previous
        }
        try { onOwner {
            clearBinding(token)
            statusAreaActionsCached = emptyArray()
            events.offer(listOf(FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActionsCached, inputMethodEntryCached)
            ).apply { inputSession = token }))
            showUnavailable("正在部署，请稍候…")
            operation()
            refreshSchemas()
            restoreMaintenanceBinding(token, previousBinding)
        } } catch (e: Exception) {
            try {
                withContext(NonCancellable) {
                    onOwner {
                        if (token == inputSession) {
                            try {
                                restoreMaintenanceBinding(token, previousBinding)
                            } finally {
                                if (e !is CancellationException) {
                                    showUnavailable(e.message ?: "操作失败，请检查方案文件")
                                }
                            }
                        }
                    }
                }
            } catch (recovery: Exception) {
                if (recovery !== e) e.addSuppressed(recovery)
            }
            throw e
        } finally { maintenance = false; keyGate.running(started) }
    }
    private suspend fun redeploy() = maintain {
        if (started) check(Native.deploy()) { "部署失败，请检查方案文件后重新部署" }
        else restartNative()
    }
    private suspend fun synchronize() = maintain { check(Native.sync()) { "用户词典同步失败" } }
    private fun restartNative() {
        if (started) Native.stop()
        started = false
        Native.start(shared.absolutePath, config.user.absolutePath, BuildConfig.VERSION_NAME, fullCheck = true)
        started = true
    }
    override fun setLogRule(verbose: Boolean) {}
    override fun translate(str: String, domain: String) = str
    override fun getAddonReverseDependencies(addon: String) = emptyList<Pair<String, FcitxAPI.AddonDep>>()
    override suspend fun save() {
        // An unavailable engine must not prevent exporting existing user files.
        // When running, sync destroys native sessions and needs the maintenance gate.
        if (onOwner { started }) synchronize()
    }
    override suspend fun saveNonRimeState() {}
    override suspend fun reloadConfig() = maintain { config.load(); restartNative() }
    override suspend fun getGlobalConfig() = getImConfig("rime")
    override suspend fun setGlobalConfig(config: RawConfig) = setImConfig("rime", config)
    override suspend fun getAddonConfig(addon: String) = getImConfig(addon)
    override suspend fun setAddonConfig(addon: String, config: RawConfig) = setImConfig(addon, config)
    override suspend fun getImConfig(key: String) = onOwner { config.describe() }
    override suspend fun setImConfig(key: String, config: RawConfig) { onOwner { this.config.setOptions(config); refreshPresentation() } }
    override suspend fun addons() = arrayOf(AddonInfo("rime", "Rime", "librime 直连引擎", AddonCategory.InputMethod, true, true, true, false))
    override suspend fun setAddonState(name: Array<String>, state: BooleanArray) { require(state.all { it }) { "Rime 引擎不可关闭" } }
    override suspend fun getAddonSubConfig(addon: String, path: String): RawConfig {
        if (path.startsWith("wanxiang-settings/")) {
            val file = path.removePrefix("wanxiang-settings/")
            return onOwner {
                org.fcitx.fcitx5.android.daemon.FcitxDaemon.requireUserDataAvailable()
                require(WanxiangAdvancedFiles.source(file))
                config.requireUpdateTarget(config.current, config.user.canonicalPath)
                val snapshot = kotlinx.serialization.json.Json.encodeToString(WanxiangAdvancedFiles.snapshot(config.user, file, shared))
                RawConfig(arrayOf(RawConfig("Snapshot", snapshot)))
            }
        }
        return onOwner { when(path) {
            "profile-manager" -> config.directoryState()
            "schema-selector" -> RawConfig(arrayOf(RawConfig("Items", if (started) Native.schemaSelection() else "")))
            else -> RawConfig()
        } }
    }
    override suspend fun setAddonSubConfig(addon: String, path: String, config: RawConfig) {
        when(path) {
            "wanxiang-settings-repair" -> withContext(NonCancellable) { maintain {
                org.fcitx.fcitx5.android.daemon.FcitxDaemon.requireUserDataAvailable()
                this@DirectRimeBackend.config.requireUpdateTarget(config.findByName("Profile")!!.value, config.findByName("Path")!!.value)
                restartNative()
            } }
            "wanxiang-settings-save" -> withContext(NonCancellable) { maintain {
                org.fcitx.fcitx5.android.daemon.FcitxDaemon.requireUserDataAvailable()
                val id = java.util.UUID.fromString(config.findByName("RequestId")!!.value)
                val job = WanxiangPayload.child(context.noBackupFilesDir.canonicalFile, "wanxiang-settings/$id")
                val planFile = WanxiangPayload.child(job, "plan.json")
                require(planFile.isFile && planFile.length() <= 256 * 1024)
                val plan = kotlinx.serialization.json.Json.decodeFromString<WanxiangAdvancedFiles.Plan>(planFile.readText())
                this@DirectRimeBackend.config.requireUpdateTarget(plan.profile, plan.path)
                WanxiangAdvancedFiles.verify(this@DirectRimeBackend.config.user, plan.expected)
                val payload = WanxiangPayload.child(job, "payload")
                payload.listFiles().orEmpty().forEach { file ->
                    require(WanxiangAdvancedFiles.writable(file.name) && plan.expected.containsKey(file.name))
                    require(WanxiangPayload.child(payload.canonicalFile, file.name) == file.absoluteFile && file.isFile && file.length() <= org.fcitx.fcitx5.android.data.WanxiangYaml.MAX_TEXT)
                    if (!file.name.endsWith(".dict.yaml")) org.fcitx.fcitx5.android.data.WanxiangYaml().document(file.readText())
                }
                if (started) Native.stop()
                started = false
                try {
                    WanxiangUpdateTransaction.install(this@DirectRimeBackend.config.user, payload,
                        activate = { restartNative() }, deactivate = { if (started) Native.stop(); started = false }, settings = true)
                } catch (error: Throwable) {
                    try { WanxiangUpdateTransaction.recover(this@DirectRimeBackend.config.user); restartNative() }
                    catch (recovery: Throwable) { error.addSuppressed(recovery) }
                    throw error
                }
            } }
            "rime-custom-update" -> withContext(NonCancellable) { maintain {
                org.fcitx.fcitx5.android.daemon.FcitxDaemon.requireUserDataAvailable()
                val id = java.util.UUID.fromString(config.findByName("RequestId")!!.value)
                val request = org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeUpdates.request(context, id)
                this@DirectRimeBackend.config.requireUpdateTarget(request.target.profile, request.target.path)
                val job = org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeUpdates.directory(context, id)
                require(WanxiangPayload.child(job, "prepared").isFile) { "更新资源尚未准备完成" }
                val staging = WanxiangPayload.child(job, "payload")
                if (staging.walkTopDown().none { it.isFile }) return@maintain
                if (started) Native.stop()
                started = false
                try {
                    WanxiangUpdateTransaction.install(this@DirectRimeBackend.config.user, staging,
                        activate = { restartNative() }, deactivate = { if (started) Native.stop(); started = false }, generic = true)
                } catch (error: Throwable) {
                    try { WanxiangUpdateTransaction.recover(this@DirectRimeBackend.config.user); restartNative() }
                    catch (recovery: Throwable) { error.addSuppressed(recovery) }
                    throw error
                }
            } }
            "wanxiang-update" -> withContext(NonCancellable) { maintain {
                org.fcitx.fcitx5.android.daemon.FcitxDaemon.requireUserDataAvailable()
                this@DirectRimeBackend.config.requireUpdateTarget(config.findByName("Profile")!!.value, config.findByName("Path")!!.value)
                val id = java.util.UUID.fromString(config.findByName("RequestId")!!.value)
                val staging = WanxiangPayload.child(context.noBackupFilesDir.canonicalFile, "wanxiang-updates/$id/payload")
                val selection = org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangUpdates.jobSelection(context, id)
                org.fcitx.fcitx5.android.data.WanxiangProtection(selection.protection).filter(staging, this@DirectRimeBackend.config.user)
                org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangExtraDictionaries.verifyAuxiliary(staging.parentFile!!, staging, this@DirectRimeBackend.config.user)
                if (staging.walkTopDown().none { it.isFile }) return@maintain
                if (started) Native.stop()
                started = false
                try {
                    WanxiangUpdateTransaction.install(this@DirectRimeBackend.config.user, staging,
                        activate = { restartNative() },
                        deactivate = { if (started) Native.stop(); started = false }, overrides = true)
                } catch (error: Throwable) {
                    try {
                        // Recovery must succeed before opening any database or model again.
                        WanxiangUpdateTransaction.recover(this@DirectRimeBackend.config.user)
                        restartNative()
                    } catch (recovery: Throwable) { error.addSuppressed(recovery) }
                    throw error
                }
            } }
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
                val destinationPath = config.findByName("Destination")?.value.orEmpty()
                val parent = if (destinationPath.isBlank()) {
                    this.config.dataRoot.canonicalFile
                } else {
                    File(destinationPath).canonicalFile
                }
                require(parent.isDirectory && parent.canRead() && parent.canWrite()) { "无法写入所选目录" }

                val profiles = this.config.directoryState().findByName("Profiles")?.value
                    .orEmpty().lineSequence().filter(String::isNotBlank).toSet()
                var timestamp = System.currentTimeMillis()
                var name = "import-$timestamp"
                while (name in profiles || File(parent, name).exists() || File(parent, ".$name.importing").exists()) {
                    name = "import-${++timestamp}"
                }
                val staging = File(parent, ".$name.importing")
                val target = File(parent, name)
                try {
                    archive.inputStream().use { ProfileArchive.extract(it, staging) }
                    check(staging.renameTo(target)) { "无法安装方案文件" }
                } catch (e: Exception) {
                    staging.deleteRecursively()
                    throw e
                }
                try {
                    this.config.create(name, target.absolutePath)
                } catch (e: Exception) {
                    target.deleteRecursively()
                    throw e
                }
                val previous = this.config.current
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
