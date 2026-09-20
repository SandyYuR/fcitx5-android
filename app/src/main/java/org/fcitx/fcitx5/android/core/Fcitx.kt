/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.tracing.trace
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.ImmutableGraph
import org.fcitx.fcitx5.android.utils.Locales
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.timberDebugEnabled
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Do not use this class directly, accessing fcitx via daemon instead
 */
class Fcitx(private val context: Context) : FcitxAPI, FcitxLifecycleOwner {

    private val lifecycleRegistry = FcitxLifecycleRegistry()

    override val eventFlow = eventFlow_.asSharedFlow()

    override val isReady
        get() = lifecycle.currentState == FcitxLifecycle.State.READY

    override var inputMethodEntryCached =
        InputMethodEntry(context.getString(R.string._not_available_))
        private set

    override var statusAreaActionsCached: Array<Action> = emptyArray()
        private set

    override var clientPreeditCached = FormattedText.Empty
        private set

    override var inputPanelCached = FcitxEvent.InputPanelEvent.Data()
        private set

    // TODO: custom log rule
    override fun setLogRule(verbose: Boolean) {
        setupLogStream(verbose)
    }

    // the computation is delayed to the first call of [getAddonReverseDependencies]
    private var addonGraph: ImmutableGraph<String, FcitxAPI.AddonDep>? = null

    private val addonReverseDependencies =
        mutableMapOf<String, List<Pair<String, FcitxAPI.AddonDep>>>()

    override fun getAddonReverseDependencies(addon: String) =
        (addonGraph ?: run { computeAddonGraph().also { addonGraph = it } }).let { graph ->
            addonReverseDependencies.computeIfAbsent(addon)
            {
                graph.bfs(it) { level, _, dep ->
                    // stop when the direct child is an optional dependency
                    dep == FcitxAPI.AddonDep.Required
                            || (level == 1 && dep == FcitxAPI.AddonDep.Optional)
                }
            }
        }

    override fun translate(str: String, domain: String) = getFcitxTranslation(domain, str)

    override suspend fun save() = withFcitxContext { saveFcitxState() }
    override suspend fun saveNonRimeState() =
        withFcitxContext { saveFcitxStateWithoutRime() }
    override suspend fun reloadConfig() = withFcitxContext { reloadFcitxConfig() }

    override suspend fun sendKey(
        key: String,
        states: UInt,
        code: Int,
        up: Boolean,
        timestamp: Int
    ) =
        withFcitxContext {
            trace("sendKey JNI") { sendKeyToFcitxString(key, states.toInt(), code, up, timestamp) }
        }

    override suspend fun sendKey(c: Char, states: UInt, code: Int, up: Boolean, timestamp: Int) =
        withFcitxContext {
            trace("sendKey JNI") { sendKeyToFcitxChar(c, states.toInt(), code, up, timestamp) }
        }

    override suspend fun sendKey(sym: Int, states: UInt, code: Int, up: Boolean, timestamp: Int) =
        withFcitxContext {
            trace("sendKey JNI") { sendKeySymToFcitx(sym, states.toInt(), code, up, timestamp) }
        }

    override suspend fun sendKey(
        sym: KeySym,
        states: KeyStates,
        code: Int,
        up: Boolean,
        timestamp: Int
    ) =
        withFcitxContext {
            trace("sendKey JNI") { sendKeySymToFcitx(sym.sym, states.toInt(), code, up, timestamp) }
        }

    override suspend fun select(idx: Int): Boolean = withFcitxContext { selectCandidate(idx) }
    override suspend fun isEmpty(): Boolean = withFcitxContext { isInputPanelEmpty() }
    override suspend fun reset() = withFcitxContext { resetInputContext() }
    override suspend fun moveCursor(position: Int) = withFcitxContext { repositionCursor(position) }
    override suspend fun availableIme() =
        withFcitxContext { availableInputMethods() ?: emptyArray() }

    override suspend fun enabledIme() =
        withFcitxContext { listInputMethods() ?: emptyArray() }

    override suspend fun setEnabledIme(array: Array<String>) =
        withFcitxContext { setEnabledInputMethods(array) }

    override suspend fun toggleIme() = withFcitxContext { toggleInputMethod() }
    override suspend fun activateIme(ime: String) = withFcitxContext { setInputMethod(ime) }
    override suspend fun enumerateIme(forward: Boolean) =
        withFcitxContext { nextInputMethod(forward) }

    override suspend fun currentIme() =
        withFcitxContext { inputMethodStatus() ?: inputMethodEntryCached }

    override suspend fun getGlobalConfig() = withFcitxContext {
        getFcitxGlobalConfig() ?: RawConfig()
    }

    override suspend fun setGlobalConfig(config: RawConfig) = withFcitxContext {
        setFcitxGlobalConfig(config)
    }

    override suspend fun getAddonConfig(addon: String) = withFcitxContext {
        getFcitxAddonConfig(addon) ?: RawConfig()
    }

    override suspend fun setAddonConfig(addon: String, config: RawConfig) = withFcitxContext {
        setFcitxAddonConfig(addon, config)
    }

    override suspend fun getAddonSubConfig(addon: String, path: String) = withFcitxContext {
        getFcitxAddonSubConfig(addon, path) ?: RawConfig()
    }

    override suspend fun setAddonSubConfig(addon: String, path: String, config: RawConfig) =
        withFcitxContext { setFcitxAddonSubConfig(addon, path, config) }

    override suspend fun getImConfig(key: String) = withFcitxContext {
        getFcitxInputMethodConfig(key) ?: RawConfig()
    }

    override suspend fun setImConfig(key: String, config: RawConfig) = withFcitxContext {
        setFcitxInputMethodConfig(key, config)
    }

    override suspend fun addons() = withFcitxContext { getFcitxAddons() ?: emptyArray() }
    override suspend fun setAddonState(name: Array<String>, state: BooleanArray) =
        withFcitxContext { setFcitxAddonState(name, state) }

    private suspend fun setClipboard(string: String, password: Boolean = false) =
        withFcitxContext { setFcitxClipboard(string, password) }

    override suspend fun focus(focus: Boolean) = withFcitxContext { focusInputContext(focus) }
    override suspend fun focusOutIn() = withFcitxContext { focusInputContextOutIn() }
    override suspend fun activate(uid: Int, pkgName: String) =
        withFcitxContext { activateInputContext(uid, pkgName) }

    override suspend fun deactivate(uid: Int) = withFcitxContext { deactivateInputContext(uid) }
    override suspend fun setCapFlags(flags: CapabilityFlags) =
        withFcitxContext { setCapabilityFlags(flags.toLong()) }

    override suspend fun statusArea(): Array<Action> =
        withFcitxContext { getFcitxStatusAreaActions() ?: emptyArray() }

    override suspend fun activateAction(id: Int) =
        withFcitxContext { activateUserInterfaceAction(id) }

    override suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord> =
        withFcitxContext {
            FcitxEvent.internCandidates(getFcitxCandidates(offset, limit) ?: emptyArray())
        }

    override suspend fun getCandidateActions(idx: Int): Array<CandidateAction> =
        withFcitxContext { getFcitxCandidateActions(idx) ?: emptyArray() }

    override suspend fun triggerCandidateAction(idx: Int, actionIdx: Int) =
        withFcitxContext { triggerFcitxCandidateAction(idx, actionIdx) }

    override suspend fun setCandidatePagingMode(mode: Int) =
        withFcitxContext { setFcitxCandidatePagingMode(mode) }

    override suspend fun offsetCandidatePage(delta: Int) =
        withFcitxContext { offsetFcitxCandidatePage(delta) }

    override suspend fun triggerCandidateListTabAction(id: Int) =
        withFcitxContext { triggerFcitxCandidateListTabAction(id) }

    init {
        if (lifecycle.currentState != FcitxLifecycle.State.STOPPED)
            throw IllegalAccessException("Fcitx5 has already been created!")
    }


    override val lifecycle: FcitxLifecycle
        get() = lifecycleRegistry

    private companion object JNI {

        /**
         * called from native-lib
         */
        @Suppress("unused")
        @JvmStatic
        fun showToast(s: String) {
            ContextCompat.getMainExecutor(appContext).execute {
                appContext.toast(s)
            }
        }

        private val eventFlow_ =
            MutableSharedFlow<FcitxEvent<*>>(
                extraBufferCapacity = 15,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )

        // we may need to modify the list during iteration
        // eg. remove the "first run" listener after first ReadyEvent
        private val fcitxEventHandlers = CopyOnWriteArrayList<(FcitxEvent<*>) -> Unit>()

        init {
            System.loadLibrary("native-lib")
        }

        @JvmStatic
        external fun setupLogStream(verbose: Boolean)

        @JvmStatic
        external fun startupFcitx(
            locale: String,
            appData: String,
            appLib: String,
            extData: String,
            extCache: String,
            extDomains: Array<String>
        )

        @JvmStatic
        external fun getFcitxTranslation(domain: String, str: String): String

        @JvmStatic
        external fun exitFcitx()

        @JvmStatic
        external fun saveFcitxState()

        @JvmStatic
        external fun saveFcitxStateWithoutRime()

        @JvmStatic
        external fun reloadFcitxConfig()

        @JvmStatic
        external fun sendKeyToFcitxString(
            key: String,
            state: Int,
            code: Int,
            up: Boolean,
            timestamp: Int
        )

        @JvmStatic
        external fun sendKeyToFcitxChar(c: Char, state: Int, code: Int, up: Boolean, timestamp: Int)

        @JvmStatic
        external fun sendKeySymToFcitx(sym: Int, state: Int, code: Int, up: Boolean, timestamp: Int)

        @JvmStatic
        external fun selectCandidate(idx: Int): Boolean

        @JvmStatic
        external fun isInputPanelEmpty(): Boolean

        @JvmStatic
        external fun resetInputContext()

        @JvmStatic
        external fun repositionCursor(position: Int)

        @JvmStatic
        external fun toggleInputMethod()

        @JvmStatic
        external fun nextInputMethod(forward: Boolean)

        @JvmStatic
        external fun listInputMethods(): Array<InputMethodEntry>?

        @JvmStatic
        external fun inputMethodStatus(): InputMethodEntry?

        @JvmStatic
        external fun setInputMethod(ime: String)

        @JvmStatic
        external fun availableInputMethods(): Array<InputMethodEntry>?

        @JvmStatic
        external fun setEnabledInputMethods(array: Array<String>)

        @JvmStatic
        external fun getFcitxGlobalConfig(): RawConfig?

        @JvmStatic
        external fun getFcitxAddonConfig(addon: String): RawConfig?

        @JvmStatic
        external fun getFcitxAddonSubConfig(addon: String, path: String): RawConfig?

        @JvmStatic
        external fun getFcitxInputMethodConfig(im: String): RawConfig?

        @JvmStatic
        external fun setFcitxGlobalConfig(config: RawConfig)

        @JvmStatic
        external fun setFcitxAddonConfig(addon: String, config: RawConfig)

        @JvmStatic
        external fun setFcitxAddonSubConfig(addon: String, path: String, config: RawConfig)

        @JvmStatic
        external fun setFcitxInputMethodConfig(im: String, config: RawConfig)

        @JvmStatic
        external fun getFcitxAddons(): Array<AddonInfo>?

        @JvmStatic
        external fun setFcitxAddonState(name: Array<String>, state: BooleanArray)

        @JvmStatic
        external fun setFcitxClipboard(string: String, password: Boolean)

        @JvmStatic
        external fun focusInputContext(focus: Boolean)

        @JvmStatic
        external fun focusInputContextOutIn()

        @JvmStatic
        external fun activateInputContext(uid: Int, pkgName: String)

        @JvmStatic
        external fun deactivateInputContext(uid: Int)

        @JvmStatic
        external fun setCapabilityFlags(flags: Long)

        @JvmStatic
        external fun getFcitxStatusAreaActions(): Array<Action>?

        @JvmStatic
        external fun activateUserInterfaceAction(id: Int)

        @JvmStatic
        external fun getFcitxCandidates(offset: Int, limit: Int): Array<CandidateWord>?

        @JvmStatic
        external fun getFcitxCandidateActions(idx: Int): Array<CandidateAction>?

        @JvmStatic
        external fun triggerFcitxCandidateAction(idx: Int, actionIdx: Int)

        @JvmStatic
        external fun setFcitxCandidatePagingMode(mode: Int)

        @JvmStatic
        external fun offsetFcitxCandidatePage(delta: Int)

        @JvmStatic
        external fun triggerFcitxCandidateListTabAction(id: Int)

        @JvmStatic
        external fun loopOnce()

        @JvmStatic
        external fun scheduleEmpty()

        /**
         * Called from native-lib
         */
        @Suppress("unused")
        @JvmStatic
        fun handleFcitxEvent(type: Int, params: Array<Any>) {
            // Phase 0 perf tracing: event arrival on the fcitx thread.
            trace("HandleFcitxEvent") {
                val event = FcitxEvent.create(type, params)
                // Guard before interpolating: a release build with verbose logging off drops
                // DEBUG inside ConciseTree.log(), i.e. only after this string and the event's
                // toString() (a candidate event joins its candidates) have been built. This runs
                // once per native event — several times per keystroke.
                if (timberDebugEnabled) {
                    Timber.d("Handling $event")
                }
                fcitxEventHandlers.forEach { it.invoke(event) }
                eventFlow_.tryEmit(event)
            }
        }

        // will be called in fcitx main thread
        private fun onFirstRun() {
            Timber.i("onFirstRun")
            // rime 专版：把默认启用的输入法固定为 rime。fcitx 全新配置生成的
            // 默认分组只含 keyboard-us 条目，而本构建已移除 Android 英文键盘
            // addon，该条目不存在；不在这里重设的话，输入法列表会指向一个
            // 不存在的条目。
            runCatching { setEnabledInputMethods(arrayOf("rime")) }
                .onFailure { Timber.w(it, "Failed to seed rime as the default input method") }
        }

        /**
         * register a [FcitxEvent] handler that will fire before events go into [eventFlow_]
         */
        private fun registerFcitxEventHandler(handler: (FcitxEvent<*>) -> Unit) {
            if (fcitxEventHandlers.contains(handler)) return
            fcitxEventHandlers.add(handler)
        }

        private fun unregisterFcitxEventHandler(handler: (FcitxEvent<*>) -> Unit) {
            fcitxEventHandlers.remove(handler)
        }

    }

    private val dispatcher = FcitxDispatcher(object : FcitxDispatcher.FcitxController {
        override fun nativeStartup() {
            DataManager.sync()
            val locale = Locales.fcitxLocale
            val dataDir = DataManager.dataDir.absolutePath
            val nativeLibDir = StringBuilder(context.applicationInfo.nativeLibraryDir)
            Timber.d(
                """
               Starting fcitx with:
               locale=$locale
               dataDir=$dataDir
               nativeLibDir=$nativeLibDir
            """.trimIndent()
            )
            with(FcitxApplication.getInstance().directBootAwareContext) {
                startupFcitx(
                    locale,
                    dataDir,
                    nativeLibDir.toString(),
                    (getExternalFilesDir(null) ?: filesDir).absolutePath,
                    (externalCacheDir ?: cacheDir).absolutePath,
                    emptyArray()
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                lifecycle.launchWhenReady {
                    SubtypeManager.syncWith(enabledIme())
                }
            }
        }

        override fun nativeLoopOnce() {
            loopOnce()
        }

        override fun nativeScheduleEmpty() {
            scheduleEmpty()
        }

        override fun nativeExit() {
            exitFcitx()
        }

        /**
         * Runs on the fcitx thread once the native loop is over — after a normal stop *and* after
         * a startup or loop failure.
         *
         * Everything that used to happen inline at the tail of `Fcitx.stop()` happens here
         * instead, because `stop()` no longer waits for the native side (that wait blocked the
         * Android main thread on every IME teardown, see `FcitxDispatcher.stopAndWait`).
         *
         * Converging from any state is what closes the "stuck at STARTING" hole: a failure in
         * `nativeStartup()` (DataManager.sync / startupFcitx) previously rolled back the dispatcher
         * flags only, leaving the lifecycle at STARTING — after which every `start()` and `stop()`
         * was rejected and every `runOnReady` caller stayed suspended forever.
         */
        override fun onStopped(error: Throwable?) {
            if (error != null) {
                Timber.e(error, "Fcitx stopped because the native loop failed")
            } else {
                Timber.i("Fcitx stopped")
            }
            convergeToStopped()
        }

    })

    /**
     * Bring the lifecycle to STOPPED from whatever state it is in and drop per-run resources.
     *
     * Safe to call more than once; a no-op once already STOPPED.
     */
    private fun convergeToStopped() {
        val state = lifecycle.currentState
        if (state == FcitxLifecycle.State.STOPPED) return
        if (state != FcitxLifecycle.State.STOPPING) {
            // STARTING or READY: take the ON_STOP edge first. STARTING is accepted now (see
            // FcitxLifecycleRegistry.postEvent), which is what makes "stop during startup" work
            // instead of being silently dropped.
            lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_STOP)
        }
        lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_STOPPED)
        ClipboardManager.removeOnUpdateListener(onClipboardUpdate)
        unregisterFcitxEventHandler(::handleFcitxEvent)
        // clear addon graph
        addonGraph = null
        addonReverseDependencies.clear()
    }

    private suspend inline fun <T> withFcitxContext(crossinline block: suspend () -> T): T =
        withContext(dispatcher) {
            block()
        }

    @Keep
    private val onClipboardUpdate = ClipboardManager.OnClipboardUpdateListener {
        lifecycle.launchWhenReady { setClipboard(it.text, it.sensitive) }
    }

    private fun computeAddonGraph() = runBlocking {
        addons().flatMap { a ->
            a.dependencies.map {
                ImmutableGraph.Edge(it, a.uniqueName, FcitxAPI.AddonDep.Required)
            } + a.optionalDependencies.map {
                ImmutableGraph.Edge(it, a.uniqueName, FcitxAPI.AddonDep.Optional)
            }
        }.let { ImmutableGraph(it) }
    }

    private var firstRun by AppPrefs.getInstance().internal.firstRun

    private fun handleFirstRunReadyEvent(event: FcitxEvent<*>) {
        if (event is FcitxEvent.ReadyEvent && firstRun) {
            firstRun = false
            // this method runs in same thread with `startupFcitx`
            // block it will also block fcitx
            onFirstRun()
            unregisterFcitxEventHandler(::handleFirstRunReadyEvent)
        }
    }

    private fun handleFcitxEvent(event: FcitxEvent<*>) {
        when (event) {
            is FcitxEvent.ReadyEvent -> lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_READY)
            is FcitxEvent.IMChangeEvent -> inputMethodEntryCached = event.data
            is FcitxEvent.StatusAreaEvent -> {
                val (actions, im) = event.data
                statusAreaActionsCached = actions
                // Engine subMode update won't trigger IMChangeEvent, but usually updates StatusArea
                if (im != inputMethodEntryCached) {
                    inputMethodEntryCached = im
                    // notify downstream consumers that engine subMode has changed
                    eventFlow_.tryEmit(FcitxEvent.IMChangeEvent(im))
                }
            }
            is FcitxEvent.ClientPreeditEvent -> clientPreeditCached = event.data
            is FcitxEvent.InputPanelEvent -> inputPanelCached = event.data
            else -> {}
        }
    }

    /**
     * Start the engine.
     *
     * @return true when this call actually started the engine.
     */
    fun start(): Boolean {
        if (lifecycle.currentState != FcitxLifecycle.State.STOPPED) {
            Timber.w("Skip starting fcitx: not at stopped state!")
            return false
        }
        if (firstRun) {
            registerFcitxEventHandler(::handleFirstRunReadyEvent)
        }
        registerFcitxEventHandler(::handleFcitxEvent)
        lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_START)
        ClipboardManager.addOnUpdateListener(onClipboardUpdate)
        setupLogStream(AppPrefs.getInstance().internal.verboseLog.getValue())
        return dispatcher.start()
    }

    /**
     * Ask the engine to stop. Returns immediately.
     *
     * This must not block — every IME teardown calls it on the Android main thread, and waiting
     * for the native loop there froze the process (the "blocked for 10+ seconds / Skipped 1378
     * frames" incident in native-lib.cpp). The lifecycle reaches STOPPED from
     * [FcitxDispatcher.FcitxController.onStopped]; code that needs the engine really gone uses
     * [stopAndWait].
     *
     * Also works while the engine is still STARTING: the previous implementation required READY
     * and silently dropped the request, so the engine stayed resident with zero clients and any
     * later `start()` was refused.
     */
    fun stop(): Boolean {
        val state = lifecycle.currentState
        if (state == FcitxLifecycle.State.STOPPED) {
            Timber.w("Skip stopping fcitx: already stopped!")
            return false
        }
        Timber.i("Fcitx stop()")
        if (state != FcitxLifecycle.State.STOPPING) {
            lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_STOP)
        }
        dispatcher.stop()
        return true
    }

    /**
     * Ask the engine to stop and block until the native side is fully gone.
     *
     * **Never call this from the main thread.** Only for callers that replace engine files
     * underneath (`AdvancedSettingsFragment` user-data import) or otherwise need the native side
     * quiesced; returns false if the wait timed out.
     */
    fun stopAndWait(timeoutMs: Long = FcitxDispatcher.STOP_TIMEOUT_MS): Boolean {
        val state = lifecycle.currentState
        if (state == FcitxLifecycle.State.STOPPED) return true
        Timber.i("Fcitx stopAndWait()")
        if (state != FcitxLifecycle.State.STOPPING) {
            lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_STOP)
        }
        return dispatcher.stopAndWait(timeoutMs)
    }

}