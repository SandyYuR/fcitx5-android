package org.fxrime.direct

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.AddonInfo
import org.fcitx.fcitx5.android.core.CandidateAction
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxLifecycle
import org.fcitx.fcitx5.android.core.FcitxLifecycleOwner
import org.fcitx.fcitx5.android.core.FcitxLifecycleRegistry
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputEngineBackend
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.RawConfig
import timber.log.Timber

/**
 * 直连 librime 的引擎实现：不经过 fcitx5，Kotlin -> JNI -> libdirect_rime.so -> librime。
 *
 * 为什么实现 [FcitxAPI] 而不是另起一套接口：UI 层（候选栏 InputView / CandidatesView、
 * IME 服务、键盘、主题、剪贴板、语音入口）全部只依赖 [FcitxAPI] 与 [FcitxEvent]。
 * 实现同一个接口即可保留完整 UI 与键盘附加功能，改动只落在引擎这一层。
 *
 * 线程模型（报告 §6 阶段 B 硬要求）：librime 非线程安全，所有调用必须经 owner
 * 派发到唯一线程，绝不能从任意 coroutine 并发进入。
 *
 * 未实现的部分（RawConfig 配置体系、addon、statusArea、多输入法切换）一律返回安全
 * 默认值而不是抛异常 —— 这些是 fcitx5 提供的能力，直连后需另行补；抛异常会让设置页
 * 和状态栏崩掉，那是比「功能缺失」更糟的结果。
 */
class DirectRimeEngine(private val context: Context) : InputEngineBackend {

    // ---- 直连的三个门控模块（原型里业已有 JVM 用例覆盖）----
    private val owner = RimeThreadOwner("rime-main")
    private val generation = SessionGenerationGate()
    private val deployGate = DeployKeyGate()

    // KeyEvent 兜底用的最近一次按键三要素（见 dispatchKey 与 publish）。
    @Volatile private var lastKeyCode: Int = 0
    @Volatile private var lastUnicodeChar: Int = 0
    @Volatile private var lastKeyUp: Boolean = false

    // ---- 生命周期 ----
    private val lifecycleRegistry = FcitxLifecycleRegistry()
    override val lifecycle: FcitxLifecycle get() = lifecycleRegistry

    override val isReady: Boolean
        get() = lifecycle.currentState == FcitxLifecycle.State.READY && session != 0L

    // ---- 事件流：与 Fcitx.kt 同参数（extraBufferCapacity=15, DROP_OLDEST），
    //      保证 UI 侧消费行为不变。报告 §5.3 禁止据此宣称「丢字已解决」，这里只求等价。----
    private val eventFlow_ = MutableSharedFlow<FcitxEvent<*>>(
        extraBufferCapacity = 15,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val eventFlow: SharedFlow<FcitxEvent<*>> = eventFlow_.asSharedFlow()

    override var inputMethodEntryCached: InputMethodEntry = InputMethodEntry(
        "rime", "Rime", "rime", "Rime", "中", "zh", "rime", false
    )
        private set

    override var statusAreaActionsCached: Array<Action> = emptyArray()
        private set

    override var clientPreeditCached: FormattedText = FormattedText.Empty
        private set

    override var inputPanelCached: FcitxEvent.InputPanelEvent.Data =
        FcitxEvent.InputPanelEvent.Data()
        private set

    private var session: Long = 0L

    /** 每次按键后的最新快照，供 cached 属性与 isEmpty / getCandidates 读取 */
    @Volatile
    private var lastSnapshot: RimeSnapshot? = null

    init {
        RimeNotificationSink.bindDeployGate(deployGate)
    }

    // ---- 启动 / 停止 ----

    override fun start() {
        android.util.Log.i("DirectRimeStart", "enter state=" + lifecycle.currentState)
        if (lifecycle.currentState != FcitxLifecycle.State.STOPPED) return
        lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_START)
        try {
            // 先把 assets 里的数据层级同步到磁盘，再启动 librime。
            // 顺序不能反：shared_data_dir 指向磁盘上的 rime-data，没同步就没有 default.yaml，
            // librime 会因找不到共享数据而部署失败。
            android.util.Log.i("DirectRimeStart", "sync begin")
            org.fcitx.fcitx5.android.core.data.DataManager.sync()
            android.util.Log.i("DirectRimeStart", "sync end")
            owner.runOnRime {
                // 目录口径与 fcitx5-rime 的 rimeengine.cpp:337/447/457-459 对齐：
                //   shared_data_dir = <dataDir>/usr/share/rime-data
                //   user_data_dir   = <dataDir>/rime
                // dataDir 是 device protected storage（见 DataManager.kt:46-51），
                // 解锁前也能用。不要改成 filesDir —— 那里没有同步过的 assets。
                val dataDir = org.fcitx.fcitx5.android.core.data.DataManager.dataDir
                val shared = java.io.File(dataDir, "usr/share/rime-data").absolutePath
                val user = java.io.File(dataDir, "rime").absolutePath
                val log = java.io.File(context.cacheDir, "rime-log").absolutePath
                java.io.File(user).mkdirs()
                java.io.File(log).mkdirs()
                if (!java.io.File(shared, "default.yaml").exists()) {
                    android.util.Log.e("DirectRimeStart", "shared 缺 default.yaml: $shared")
                    Timber.e("DirectRime: shared 目录缺 default.yaml: $shared")
                }
                val ok = RimeBridge.nativeSetup(shared, user, log, "rime.fxrime")
                android.util.Log.i("DirectRimeStart", "setup=$ok shared=$shared user=$user")
                // 试打字包：首次启动时把 luna_pinyin 写入用户目录 default.custom.yaml 并触发部署，
                // 让引擎开箱即有可用方案。default.yaml 本体的 schema_list 保持 [] 不动（红线）。
                // 已部署过（default.custom.yaml 已存在）则跳过，避免覆盖用户自己的方案选择。
                try {
                    val custom = java.io.File(user, "default.custom.yaml")
                    if (!custom.exists()) {
                        custom.parentFile?.mkdirs()
                        custom.writeText("patch:\n  schema_list:\n    - schema: luna_pinyin\n")
                        android.util.Log.i("DirectRimeStart", "wrote default.custom.yaml")
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("DirectRimeStart", "写 default.custom.yaml 失败: $t")
                }
                Timber.i("DirectRime: setup=$ok shared=$shared user=$user version=${RimeBridge.nativeGetVersion()}")
                if (ok && session == 0L) {
                    // 首次部署：写完 default.custom.yaml 后必须跑一次维护线程，
                    // 否则新方案不会被编译进 build 目录，session 仍是空 schema。
                    // start_maintenance 是异步的：它拉起部署线程后立即返回，
                    // 必须 join 等部署完成再 create_session，否则 session 拿的是旧配置。
                    try {
                        val custom = java.io.File(user, "default.custom.yaml")
                        val needDeploy = !java.io.File(java.io.File(user, "build"), "luna_pinyin.schema.yaml").exists()
                        android.util.Log.i("DirectRimeStart", "needDeploy=$needDeploy")
                        if (needDeploy) {
                            deployGate.onDeployStart()
                            val mOk = RimeBridge.nativeStartMaintenance(false)
                            android.util.Log.i("DirectRimeStart", "startMaintenance=$mOk")
                            if (mOk) {
                                RimeBridge.nativeJoinMaintenanceThread()
                                android.util.Log.i("DirectRimeStart", "deploy joined")
                            }
                            deployGate.onDeployEnd(mOk)
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("DirectRimeStart", "首次部署失败: $t")
                    }
                    session = RimeBridge.nativeCreateSession()
                    deployGate.onSessionCreated()
                    android.util.Log.i("DirectRimeStart", "session=$session")
                    Timber.i("DirectRime: session=$session")
                    // 部署后新 session 默认仍是空 schema：首包不自动切方案，
                    // 只记 schemas 日志，把选择权留给用户操作。
                    // 自动 select_schema 会触发 ApplySchema 重建组件，与部署线程
                    // 的 notification 回调竞态导致 abort（见 15:35 detach 崩溃）。
                    // 用户在rime选择方案后由 UI 调 select() 走正常路径。
                    try {
                        val list = RimeBridge.nativeGetSchemaList()
                        val ids = list?.filterIsInstance<String>()?.filterIndexed { i, _ -> i % 2 == 0 } ?: emptyList()
                        android.util.Log.i("DirectRimeStart", "schemas=$ids")
                        android.util.Log.i("DirectRimeStart", "首包不自动 select，等用户在rime选方案")
                    } catch (t: Throwable) {
                        android.util.Log.e("DirectRimeStart", "选中方案失败: $t")
                    }
                }
            }
            lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_READY)
            emit(FcitxEvent.ReadyEvent())
            android.util.Log.i("DirectRimeStart", "ready")
        } catch (t: Throwable) {
            android.util.Log.e("DirectRimeStart", "start 失败: $t")
            Timber.e(t, "DirectRime: start 失败")
        }
    }

    override fun stop() {
        if (lifecycle.currentState != FcitxLifecycle.State.READY) return
        lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_STOP)
        try {
            owner.runOnRime {
                if (session != 0L) {
                    RimeBridge.nativeDestroySession(session)
                    session = 0L
                }
                RimeBridge.nativeFinalize()
            }
        } catch (t: Throwable) {
            Timber.e(t, "DirectRime: 销毁 session 失败")
        }
        deployGate.onSessionDestroyed()
        generation.bump()
        // 先让排队任务跑完，超时才 shutdownNow；返回被丢任务数（正常应为 0）
        val dropped = owner.shutdown()
        if (dropped > 0) Timber.w("DirectRime: 关闭时丢弃了 $dropped 个排队任务")
        lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_STOPPED)
    }

    private fun emit(e: FcitxEvent<*>) {
        eventFlow_.tryEmit(e)
    }

    /** 把一次按键后的快照翻译成 UI 认识的事件流 */
    private fun publish(snapshot: RimeSnapshot) {
        lastSnapshot = snapshot

        snapshot.commitText?.let { text ->
            emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data(text, text.length)))
        }

        // KeyEvent 兜底：只在引擎明确不处理（PASSTHROUGH）时补发。
        // TO_RIME（引擎已消费，进 preedit/候选）绝不能发——发了字母就会
        // 直通上屏，与 preedit 双写，这正是"nihao 同步上屏"的根因。
        // DROP/SWALLOW 不进 publish（见 dispatchKey），无需在此判断。
        // 有 commit 时不补发，避免字符被提交两次。
        // 只在 DOWN 发一次：IMS 的 else 分支按 !up 提交字符，UP 再发会走
        // "Unhandled Fcitx KeyEvent" 噪音分支。
        // 修饰键（Shift/Ctrl/Alt/Meta/CapsLock）是状态键不是输入键：
        // 英语切换中西文靠 ascii_composer 的 inline_ascii 逻辑，不靠这里补发。
        // 补发 Shift_L 的 KeyEvent 会让 IMS 把它当可打印字符上屏一个符号。
        // 修饰键抬起（UP）本来就不补发（见 !lastKeyUp），这里连 DOWN 也不发。
        val isModifier = lastUnicodeChar == 65505 || lastUnicodeChar == 65506 ||
            lastUnicodeChar == 65507 || lastUnicodeChar == 65508 ||
            lastUnicodeChar == 65511 || lastUnicodeChar == 65512 ||
            lastUnicodeChar == 65513 || lastUnicodeChar == 65514 ||
            lastUnicodeChar == 65509
        if (snapshot.dispatch == RimeSnapshotSlots.DISPATCH_PASSTHROUGH &&
            snapshot.commitText == null && !lastKeyUp && !isModifier) {
            // lastKeyCode 是 Linux scancode（dispatchKey 的 code 参数），不能喂给
            // 按 Android keyCode 查表的 keyCodeToSym：如 scancode 42 会命中
            // Android KEYCODE_N（A=29 起顺排）而错位成 'n' 上屏。lastUnicodeChar
            // 已是 native 侧定好的 keysym（unicode 优先口径，与 dr_build_key 一致），直接用它。
            val sym = lastUnicodeChar.takeIf { it > 0 }
            if (sym != null) {
                emit(
                    FcitxEvent.KeyEvent(
                        FcitxEvent.KeyEvent.Data(
                            sym = KeySym(sym),
                            states = KeyStates.Virtual,
                            unicode = lastUnicodeChar,
                            up = false,
                            timestamp = -1
                        )
                    )
                )
            }
        }

        val preedit = snapshot.preedit ?: ""
        // caret 越界保护：native 给的是 composition.cursor_pos（字节口径），
        // preedit 为空或 caret 超出 preedit 长度时 UI 侧 PreeditUi.update
        // 会 StringIndexOutOfBounds 闪退（见 17:15 crash 缓冲）。
        // 越界时按末尾处理——与 fcitx 原生侧行为一致（光标跟在预编辑末尾）。
        val safeCaret = when {
            preedit.isEmpty() -> -1
            snapshot.caretPos < 0 || snapshot.caretPos > preedit.length -> preedit.length
            else -> snapshot.caretPos
        }
        val ft = if (preedit.isEmpty()) FormattedText.Empty
        else FormattedText(arrayOf(preedit), intArrayOf(0), safeCaret)
        clientPreeditCached = ft
        val panel = FcitxEvent.InputPanelEvent.Data(
            ft, FormattedText.Empty, FormattedText.Empty, emptyArray()
        )
        inputPanelCached = panel
        emit(FcitxEvent.InputPanelEvent(panel))

        val cands = snapshot.candidates.map {
            CandidateWord("", it.text, it.comment ?: "")
        }.toTypedArray()
        emit(
            FcitxEvent.PagedCandidateEvent(
                FcitxEvent.PagedCandidateEvent.Data(
                    candidates = cands,
                    cursorIndex = snapshot.highlightedIndex,
                    layoutHint = FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet,
                    hasPrev = snapshot.pageNo > 0,
                    hasNext = !snapshot.isLastPage
                )
            )
        )
    }

    /**
     * 统一的按键派发。Android 三要素（keyCode / metaState / unicodeChar）直接交给 native，
     * 映射与部署期门控都在 native 侧完成（dr_gate_key），Kotlin 不再重复问一次状态。
     *
     * 返回 true 表示这次按键应当交给编辑器（引擎完全不处理）。
     */
    private fun dispatchKey(keyCode: Int, metaState: Int, unicodeChar: Int, up: Boolean): Boolean {
        android.util.Log.i("DirectRimeKey", "dispatch kc=$keyCode meta=$metaState uni=$unicodeChar up=$up session=$session maint=${deployGate.maintenance} hasSession=${deployGate.hasSession}")
        if (session == 0L) return false
        // KeyEvent 兜底要知道这次按的是哪个键：dispatchKey 是同步的，
        // publish 在同一调用内执行，直接记成员即可，无并发问题。
        lastKeyCode = keyCode
        lastUnicodeChar = unicodeChar
        lastKeyUp = up
        // 代次门控：派发时记下代次，结果回来时校验。不符即判为过期丢弃（计数可读，
        // 不静默丢）——防止 A 输入框的候选显示在刚获得焦点的 B 输入框上。
        val pending = generation.bind {
            try {
                owner.runOnRime {
                    RimeBridge.nativeProcessKey(
                        session, keyCode, metaState, unicodeChar, if (up) 1 else 0
                    )
                }
            } catch (t: Throwable) {
                Timber.e(t, "DirectRime: processKey 失败")
                null
            }
        }
        val raw = generation.consume(pending)
        if (raw == null) {
            android.util.Log.w("DirectRimeKey", "raw null gen=${pending.generation}")
            Timber.w(
                "DirectRime: 结果过期被丢弃 gen=${pending.generation} " +
                    "droppedStale=${generation.droppedStale}"
            )
            return false
        }

        val snapshot = RimeSnapshot.fromObjectArray(raw)
        android.util.Log.i("DirectRimeKey", "dispatch=${snapshot?.dispatch} commit=${snapshot?.commitText} preedit=${snapshot?.preedit} cands=${snapshot?.candidates?.size}")
        if (snapshot == null) return false

        return when (snapshot.dispatch) {
            RimeSnapshotSlots.DISPATCH_SWALLOW -> {
                deployGate.gate(mappedOk = true, isDown = !up)
                false
            }
            RimeSnapshotSlots.DISPATCH_DROP -> {
                deployGate.gate(mappedOk = false, isDown = !up)
                true   // 无法映射 -> 原样给编辑器
            }
            // librime 明确不处理（process_key 返回 false）：不能吞，也不能当空状态。
            // 必须让这次按键落回编辑器，否则用户按了键却没有任何反应。
            RimeSnapshotSlots.DISPATCH_PASSTHROUGH -> {
                deployGate.gate(mappedOk = true, isDown = !up)
                publish(snapshot)
                true
            }
            else -> {
                deployGate.gate(mappedOk = true, isDown = !up)
                publish(snapshot)
                false
            }
        }
    }

    // ---- 按键：四个重载都收敛到 dispatchKey ----

    override suspend fun sendKey(key: String, states: UInt, code: Int, up: Boolean, timestamp: Int) {
        // key 是单字符 String（如 "a"）：fcitx 原生侧收整个串自己处理，
        // 直连必须把字符码喂给 native，否则 unicode=0 会掉进 scancode 错位分支。
        // code 是 Linux scancode 不是 Android keyCode，传错一位——unicode 非零时
        // native 优先用它，keyCode 被忽略，错位被掩盖。
        dispatchKey(code, states.toInt(), key.firstOrNull()?.code ?: 0, up)
    }

    override suspend fun sendKey(c: Char, states: UInt, code: Int, up: Boolean, timestamp: Int) {
        dispatchKey(code, states.toInt(), c.code, up)
    }

    override suspend fun sendKey(sym: Int, states: UInt, code: Int, up: Boolean, timestamp: Int) {
        // sym 本身就是 X11 keysym；native 的 dr_build_key 把 unicode_char 非零值
        // 直接当 keysym 用（仅排除 \t \n 0xEF01 0xEF02），所以这里原样透传，
        // 可打印与功能键统一走同一条路，不再按可打印区过滤。
        dispatchKey(code, states.toInt(), sym, up)
    }

    override suspend fun sendKey(sym: KeySym, states: KeyStates, code: Int, up: Boolean, timestamp: Int) {
        // 同上：KeySym.sym 即 X11 keysym（含 0xff08 BackSpace 这类功能键），
        // 原样透传给 native；传 0 会让 BackSpace/Return 等在 keycode=0 时被 DROP。
        dispatchKey(code, states.toInt(), sym.sym, up)
    }

    // ---- 候选操作 ----

    override suspend fun select(idx: Int): Boolean {
        if (session == 0L) return false
        val raw = try {
            owner.runOnRime { RimeBridge.nativeSelectCandidateOnCurrentPage(session, idx) }
        } catch (t: Throwable) {
            Timber.e(t, "DirectRime: select 失败")
            null
        } ?: return false
        val s = RimeSnapshot.fromObjectArray(raw) ?: return false
        publish(s)
        return true
    }

    override suspend fun isEmpty(): Boolean {
        val s = lastSnapshot ?: return true
        return s.preedit.isNullOrEmpty() && s.candidates.isEmpty()
    }

    override suspend fun reset() {
        if (session == 0L) return
        val raw = try {
            owner.runOnRime { RimeBridge.nativeClearComposition(session) }
        } catch (t: Throwable) {
            Timber.e(t, "DirectRime: reset 失败")
            null
        }
        RimeSnapshot.fromObjectArray(raw)?.let { publish(it) }
    }

    override suspend fun moveCursor(position: Int) {
        // librime 无直接对应 API；预编辑光标由 composition.cursor_pos 决定，这里空实现。
    }

    override suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord> =
        lastSnapshot?.candidates?.map { CandidateWord("", it.text, it.comment ?: "") }
            ?.toTypedArray() ?: emptyArray()

    override suspend fun offsetCandidatePage(delta: Int) {
        if (session == 0L) return
        val raw = try {
            owner.runOnRime { RimeBridge.nativeChangePage(session, delta < 0) }
        } catch (t: Throwable) {
            Timber.e(t, "DirectRime: changePage 失败")
            null
        }
        RimeSnapshot.fromObjectArray(raw)?.let { publish(it) }
    }

    override suspend fun setCandidatePagingMode(mode: Int) {
        // 分页模式是 fcitx5 概念（按页/按列表），直连下由 librime 的 page_size 决定。
    }

    override suspend fun getCandidateActions(idx: Int): Array<CandidateAction> = emptyArray()
    override suspend fun triggerCandidateAction(idx: Int, actionIdx: Int) {}

    /**
     * 选中某个 input tab。
     *
     * native 的 select_tab 需要 (position, label, span) 三元组，而 [FcitxAPI] 只给了 id。
     * 这里把 id 当 tab 下标，先取一次 tabs 拿到真实的 label/span 再选中——
     * 不自己拼 label，避免用错ߦ的字面量把 tab 选飞。
     */
    override suspend fun triggerCandidateListTabAction(id: Int) {
        if (session == 0L || id < 0) return
        val raw = try {
            owner.runOnRime {
                val tabs = RimeBridge.nativeGetInputTabs(session, id)
                val (label, span) = pickTab(tabs, id)
                if (label == null) null
                else RimeBridge.nativeSelectTab(session, id, label, span)
            }
        } catch (t: Throwable) {
            Timber.e(t, "DirectRime: selectTab 失败")
            null
        }
        RimeSnapshot.fromObjectArray(raw)?.let { publish(it) }
    }

    /**
     * 从 nativeGetInputTabs 的扁平数组（每 tab 三槽：label, span, source）里取第 [index] 个。
     * 索引越界返回 null —— 宁可不选，也不能把错的 label 传进 librime。
     */
    private fun pickTab(tabs: Array<Any>?, index: Int): Pair<String?, Int> {
        if (tabs == null || tabs.size < (index + 1) * 3) return null to 0
        val base = index * 3
        val label = tabs[base] as? String
        val span = (tabs[base + 1] as? Int) ?: 0
        return label to span
    }

    // ---- 焦点与会话：报告 §4 要求「每编辑器/UID session 与焦点重绑」----

    override suspend fun focus(focus: Boolean) {
        if (focus && session == 0L && lifecycle.currentState == FcitxLifecycle.State.READY) {
            session = owner.runOnRime { RimeBridge.nativeCreateSession() }
            deployGate.onSessionCreated()
        }
    }

    /** 焦点切到新编辑器：代次自增，作废所有在途旧结果 */
    override suspend fun activate(uid: Int, pkgName: String) {
        generation.bump()
        if (session == 0L) {
            session = owner.runOnRime { RimeBridge.nativeCreateSession() }
            deployGate.onSessionCreated()
        }
    }

    override suspend fun deactivate(uid: Int) {
        generation.bump()
    }

    override suspend fun focusOutIn() {
        generation.bump()
    }

    override suspend fun setCapFlags(flags: CapabilityFlags) {
        // 能力标志告诉 fcitx 编辑器支持什么（如 surrounding text），直连下暂无对应机制。
    }

    // ---- 输入法列表：直连下只有 Rime 一个引擎 ----

    override suspend fun currentIme(): InputMethodEntry = inputMethodEntryCached
    override suspend fun availableIme(): Array<InputMethodEntry> = arrayOf(inputMethodEntryCached)
    override suspend fun enabledIme(): Array<InputMethodEntry> = arrayOf(inputMethodEntryCached)
    override suspend fun setEnabledIme(array: Array<String>) {}
    override suspend fun toggleIme() {}
    override suspend fun activateIme(ime: String) {}
    override suspend fun enumerateIme(forward: Boolean) {}

    // ---- 配置与 addon：fcitx5 的能力，直连后需另行补，这里返回安全默认值 ----

    override suspend fun save() {
        try {
            owner.runOnRime { RimeBridge.nativeSyncUserData() }
        } catch (t: Throwable) {
            Timber.e(t, "DirectRime: sync 失败")
        }
    }

    override suspend fun saveNonRimeState() {}

    override suspend fun reloadConfig() {
        if (session == 0L) return
        try {
            owner.runOnRime { RimeBridge.nativeStartMaintenance(false) }
        } catch (t: Throwable) {
            Timber.e(t, "DirectRime: startMaintenance 失败")
        }
    }

    override suspend fun getGlobalConfig(): RawConfig = RawConfig()
    override suspend fun setGlobalConfig(config: RawConfig) {}
    override suspend fun getAddonConfig(addon: String): RawConfig = RawConfig()
    override suspend fun setAddonConfig(addon: String, config: RawConfig) {}
    /**
     * 中州韵设置页（InputMethodConfigFragment，"rime"）的数据源。
     *
     * FcitxPreferenceFragment 要求返回树含 "cfg"+"desc" 两个子节点（否则显示
     * "config_addon_not_loaded"），desc 树的形状约定见 ConfigDescriptor：
     * desc.subItems[0] 为顶层节点，其 subItems 为各配置项；每项至少含
     * Type/Description 子节点，External 类型再加 External（URI）子节点。
     * 三项的 name 与 URI 与 fcitx5-rime 的 RimeEngineConfig 对齐
     *（rimeengine.h:141-161），使方案多选页与部署/同步动作成同一语义。
     */
    override suspend fun getImConfig(key: String): RawConfig {
        fun item(name: String, description: String, externalUri: String): RawConfig =
            RawConfig(
                name, "", "", arrayOf(
                    RawConfig("Type", "External"),
                    RawConfig("Description", description),
                    RawConfig("External", externalUri)
                )
            )
        val top = RawConfig(
            "Rime", "", "", arrayOf(
                item(
                    "SchemaSelector", "方案选择",
                    "fcitx://multiselect/addon/rime/schema-selector?option=Items&min=1&app_proto=2"
                ),
                item(
                    "DeployAction", "立即部署",
                    "fcitx://addon-action/addon/rime/deploy?app_proto=2"
                ),
                item(
                    "SynchronizeAction", "立即同步",
                    "fcitx://addon-action/addon/rime/sync?app_proto=2"
                )
            )
        )
        return RawConfig(
            "", "", "", arrayOf(
                RawConfig("cfg", "", "", arrayOf()),
                RawConfig("desc", "", "", arrayOf(top))
            )
        )
    }
    override suspend fun setImConfig(key: String, config: RawConfig) {
        // 本页三项全是 External 类型（点击跳转/执行，不经 PreferenceDataStore 写 cfg），
        // 退出页面的 save() 送来的是空 cfg 树，无内容可写，忽略。
    }
    /**
     * 方案多选页（GenericMultiSelectFragment）与部署/同步动作（runAddonAction）
     * 的数据源。payload 行格式与 GenericMultiSelectFragment.parseItems 对齐：
     * "1\tid\tlabel" 选中行，"0\tid\tlabel" 未选中行。
     */
    override suspend fun getAddonSubConfig(addon: String, path: String): RawConfig {
        if (addon != "rime" || path != "schema-selector") return RawConfig()
        val list = try {
            owner.runOnRime { RimeBridge.nativeGetSchemaList() }
        } catch (t: Throwable) {
            Timber.e(t, "DirectRime: getSchemaList 失败")
            null
        }
        val current = lastSnapshot?.schemaId
        val payload = list?.filterIsInstance<String>()?.chunked(2)
            ?.filter { it.size == 2 }
            ?.joinToString("\n") { (id, name) ->
                "${if (id == current) "1" else "0"}\t$id\t${name.replace('\t', ' ').replace('|', ' ')}"
            }.orEmpty()
        return RawConfig(arrayOf(RawConfig("Items", payload)))
    }
    override suspend fun setAddonSubConfig(addon: String, path: String, config: RawConfig) {
        if (addon != "rime") return
        when (path) {
            "schema-selector" -> {
                val selected = config.findByName("Items")?.value.orEmpty()
                    .lineSequence().mapNotNull { line ->
                        val parts = line.split('\t', limit = 3)
                        if (parts.size < 3 || parts[0] != "1") null else parts[1]
                    }.firstOrNull() ?: return
                val s = session
                if (s == 0L) return
                try {
                    owner.runOnRime { RimeBridge.nativeSelectSchema(s, selected) }
                } catch (t: Throwable) {
                    Timber.e(t, "DirectRime: selectSchema 失败")
                }
            }
            "deploy" -> try {
                owner.runOnRime { RimeBridge.nativeStartMaintenance(false) }
            } catch (t: Throwable) {
                Timber.e(t, "DirectRime: startMaintenance 失败")
            }
            "sync" -> try {
                owner.runOnRime { RimeBridge.nativeSyncUserData() }
            } catch (t: Throwable) {
                Timber.e(t, "DirectRime: syncUserData 失败")
            }
        }
    }
    override suspend fun addons(): Array<AddonInfo> = emptyArray()
    override suspend fun setAddonState(name: Array<String>, state: BooleanArray) {}
    override suspend fun statusArea(): Array<Action> = emptyArray()
    override suspend fun activateAction(id: Int) {}

    // ---- 杂项 ----

    override fun setLogRule(verbose: Boolean) {}

    override fun getAddonReverseDependencies(addon: String): List<Pair<String, FcitxAPI.AddonDep>> =
        emptyList()

    override fun translate(str: String, domain: String): String = str
}

