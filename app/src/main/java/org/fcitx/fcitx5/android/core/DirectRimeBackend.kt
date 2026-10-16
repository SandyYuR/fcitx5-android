/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fcitx.directrime.Native
import org.fcitx.directrime.ProfileArchive
import org.fcitx.directrime.Snapshot
import org.fcitx.fcitx5.android.FcitxApplication
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
    override val isReady get() = registry.currentState == FcitxLifecycle.State.READY
    private val epoch = AtomicLong()
    override val inputSession get() = epoch.get()
    override fun invalidateInputSession() = epoch.incrementAndGet()
    private val events = Channel<List<FcitxEvent<*>>>(Channel.UNLIMITED)
    private val flow = MutableSharedFlow<FcitxEvent<*>>(extraBufferCapacity = 64)
    override val eventFlow = flow.asSharedFlow()
    private val config = DirectRimeConfig(this.context)
    private var started = false
    @Volatile private var snapshot: Snapshot? = null
    private var program = ""
    private var activeUid = -1
    private var flags = CapabilityFlags.DefaultFlags
    private var pagingMode = 1
    private var preferredSchema = ""
    private var preferredAscii: Boolean? = null
    private var schemas = emptyList<Pair<String, String>>()
    private var optionRows = emptyList<List<String>>()
    private val forwardedKeys = mutableSetOf<Pair<Int, Int>>()
    private var startupError = ""
    @Volatile private var maintenance = false
    override val acceptsInput get() = isReady && !maintenance
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
        engineScope.launch {
            try {
                DataManager.sync()
                config.load()
                Native.start(shared.absolutePath, config.user.absolutePath)
                started = true
                startupError = ""
                refreshSchemas()
                begin(epoch.incrementAndGet())
            } catch (e: Exception) {
                startupError = e.message ?: "引擎启动失败"
                Timber.e(e, "Direct Rime startup failed")
                showUnavailable(startupError)
            } finally {
                registry.postEvent(FcitxLifecycle.Event.ON_READY)
                events.trySend(listOf(FcitxEvent.ReadyEvent()))
            }
            if (Build.VERSION.SDK_INT >= 34) SubtypeManager.syncWith(enabledIme())
        }
    }

    override fun stop() {
        if (registry.currentState == FcitxLifecycle.State.STOPPED) return
        invalidateInputSession()
        runBlocking(owner) {
            registry.postEvent(FcitxLifecycle.Event.ON_STOP)
            if (started) Native.stop()
            started = false
            snapshot = null
            registry.postEvent(FcitxLifecycle.Event.ON_STOPPED)
        }
    }

    private val shared get() = File(DataManager.dataDir, "usr/share/rime-data")
    private suspend fun <T> onOwner(block: () -> T): T = withContext(owner) { block() }
    private fun refreshSchemas() {
        schemas = Native.schemaEntries().toList().chunked(2).map { it[0] to it[1] }
    }
    private fun begin(generation: Long) {
        if (!started || generation != inputSession) return
        var result = Native.begin(generation, program)
        if (result.ready) {
            if (preferredSchema.isNotEmpty() && result.schema != preferredSchema && schemas.any { it.first == preferredSchema })
                result = Native.action(generation, result.revision, 3, 0, preferredSchema)
            preferredAscii?.let { ascii ->
                if (result.ascii != ascii) result = Native.action(generation, result.revision, 8, if (ascii) 1 else 0, "ascii_mode")
            }
        }
        publish(result)
    }
    override suspend fun startInputSession(generation: Long, program: String) = onOwner {
        if (generation != inputSession) return@onOwner
        this.program = program
        forwardedKeys.clear()
        begin(generation)
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
        snapshot = value
        if (value.ready) {
            preferredSchema = value.schema
            preferredAscii = value.ascii
            optionRows = Native.options().toList().chunked(4)
        }
        val client = when (config.preeditMode) {
            "ComposingText" -> formatted(value.preedit, value.cursorUtf16, true)
            "CommitPreview" -> formatted(value.preview, underline = true)
            else -> FormattedText.Empty
        }
        val tabs = value.tabs.mapIndexed { i, text -> CandidateAction(i, text, false, "", false, false) }.toTypedArray()
        val panel = FcitxEvent.InputPanelEvent.Data(
            if (config.preeditMode == "No") formatted(value.preedit, value.cursorUtf16) else FormattedText.Empty,
            if (value.ready) FormattedText.Empty else formatted(startupError.ifEmpty { value.gateMessage }),
            FormattedText.Empty, tabs
        )
        val ime = entry(value)
        val imeChanged = ime != inputMethodEntryCached
        val actions = buildActions(value)
        clientPreeditCached = client
        inputPanelCached = panel
        inputMethodEntryCached = ime
        statusAreaActionsCached = actions
        val batch = mutableListOf<FcitxEvent<*>>()
        if (value.commit.isNotEmpty()) batch += FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data(value.commit, -1))
        batch += FcitxEvent.ClientPreeditEvent(client)
        batch += FcitxEvent.InputPanelEvent(panel)
        val candidates = value.candidates.mapIndexed { i, text ->
            val comment = listOf(value.comments.getOrElse(i) { "" }, value.codes.getOrElse(i) { "" })
                .filter { it.isNotBlank() }.distinct().joinToString(" ")
            CandidateWord(value.labels.getOrElse(i) { "${(i + 1) % 10} " }, text, comment)
        }.toTypedArray()
        if (pagingMode != 0) batch += FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data(
            candidates, value.highlighted, FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet, value.page > 0, !value.lastPage))
        else batch += FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data(-1, candidates))
        if (imeChanged) batch += FcitxEvent.IMChangeEvent(ime)
        batch += FcitxEvent.StatusAreaEvent(FcitxEvent.StatusAreaEvent.Data(actions, ime))
        batch.forEach { it.inputSession = value.generation }
        events.trySend(batch)
    }
    private fun entry(value: Snapshot?) = InputMethodEntry("rime", "Rime", "fcitx-rime", "中州韵", "中", "zh", "rime", true,
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
        val options = optionRows.mapIndexed { i, row -> action(1000 + i, row[0], row[1], row[2] == "1") }
        menu += options
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
        onOwner {
            if (expected != inputSession || maintenance || !started) return@onOwner
            val bypass = flags.flags and (CapabilityFlag.Password.flag or CapabilityFlag.Disable.flag) != 0UL
            val result = if (bypass) null else Native.key(expected, sym, states.toInt(), up)
            if (result != null) publish(result)
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
        return onOwner {
            if (expected.generation != inputSession || snapshot?.revision != expected.revision) null else act(action, index)
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
    override suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord> = onOwner {
        if (!started || maintenance) return@onOwner emptyArray()
        Native.candidates(inputSession, offset, limit.coerceIn(1, 256)).toList().chunked(2)
            .map { CandidateWord("", it[0], it[1]) }.toTypedArray()
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
    override suspend fun toggleIme() { onOwner { act(6) } }
    override suspend fun activateIme(ime: String) {
        // Android can bind its manifest/default subtype before syncWith replaces it.
        // This edition has one IM; a stale keyboard-us subtype must not crash the service.
        if (ime != "rime") Timber.d("Ignoring an unavailable input method subtype")
    }
    override suspend fun enumerateIme(forward: Boolean) = toggleIme()
    override suspend fun activate(uid: Int, pkgName: String) { onOwner { activeUid = uid; program = pkgName } }
    override suspend fun deactivate(uid: Int) { onOwner { if (uid == activeUid) { snapshot?.let { Native.end(it.generation) }; activeUid = -1 } } }
    override suspend fun focus(focus: Boolean) { onOwner {
        if (!focus) { snapshot?.let { if (started) publish(Native.end(it.generation)) } }
        else if (snapshot?.generation != inputSession || snapshot?.ready != true) begin(epoch.incrementAndGet())
    } }
    override suspend fun focusOutIn() = reset()
    override suspend fun setCapFlags(flags: CapabilityFlags) { onOwner {
        this.flags = flags
        act(8, if (flags.flags and CapabilityFlag.PasswordOrSensitive.flag != 0UL) 1 else 0, "_no_learning")
    } }
    override suspend fun statusArea() = statusAreaActionsCached
    override suspend fun activateAction(id: Int) {
        when {
            id == 11 -> redeploy()
            id == 12 -> synchronize()
            else -> onOwner { when {
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
        } finally { maintenance = false }
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
