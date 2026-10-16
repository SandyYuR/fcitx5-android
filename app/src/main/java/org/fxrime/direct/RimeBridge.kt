package org.fxrime.direct

/*
 * RimeBridge — Kotlin 侧到 libdirect_rime.so 的唯一入口。
 *
 * native 侧函数名的包名必须与此文件一致：Java_org_fxrime_direct_RimeBridge_native*
 * （native/rime_bridge.c，27 条导出，逐条列在 build/arm64-v8a/verify.txt）。
 *
 * 设计约束（来自报告 §4 与 §5.3）：
 *   - 所有状态变更入口都返回 Object[]（经 RimeSnapshot 解包），一次调用带回
 *     commit + context + status，JNI 往返次数是常数，不随候选条数增长。
 *   - 不宣称这样更快：报告 §5.3 明令禁止「JNI 次数是主瓶颈」这类结论，
 *     这里只说明「批量接口是可数的设计选择」，延迟收益未测量。
 *   - 所有 librime 调用必须经 RimeThreadOwner 派发到单线程；本类不自己保证线程安全，
 *     由调用方（IMS）包一层 owner，与 foxy 的 runOnRime 同构。
 *
 * 本机无真机、无模拟器，因此这些 native 方法在 JVM 上只会抛 UnsatisfiedLinkError；
 * 编译通过只证明签名与 native 导出一致，不证明运行时行为。
 */
object RimeBridge {

    init {
        System.loadLibrary("direct_rime")
    }

    // ---- 生命周期 ----

    @JvmStatic
    external fun nativeSetup(
        sharedDir: String,
        userDir: String,
        logDir: String?,
        appName: String?
    ): Boolean

    @JvmStatic
    external fun nativeFinalize()

    @JvmStatic
    external fun nativeGetVersion(): String?

    // ---- 会话 ----

    @JvmStatic
    external fun nativeCreateSession(): Long

    @JvmStatic
    external fun nativeFindSession(session: Long): Boolean

    @JvmStatic
    external fun nativeDestroySession(session: Long): Boolean

    // ---- 按键：唯一的批量入口 ----

    /**
     * @param keyCode   Android KeyEvent.getKeyCode()
     * @param metaState Android KeyEvent.getMetaState()
     * @param unicodeChar Android KeyEvent.getUnicodeChar()，非 0 时优先于 keyCode 映射
     * @param action    0 = DOWN, 1 = UP（与 native DR_ACTION_DOWN/UP 同值）
     * @return 打包状态；null 表示 native 侧出错（引擎未初始化等），调用方不得当成空状态
     */
    @JvmStatic
    external fun nativeProcessKey(
        session: Long,
        keyCode: Int,
        metaState: Int,
        unicodeChar: Int,
        action: Int
    ): Array<Any>?

    // ---- 候选操作 ----

    @JvmStatic
    external fun nativeSelectCandidate(session: Long, index: Int): Array<Any>?

    @JvmStatic
    external fun nativeSelectCandidateOnCurrentPage(session: Long, index: Int): Array<Any>?

    @JvmStatic
    external fun nativeChangePage(session: Long, backward: Boolean): Array<Any>?

    @JvmStatic
    external fun nativeDeleteCandidate(session: Long, index: Int): Array<Any>?

    @JvmStatic
    external fun nativeCommitComposition(session: Long): Array<Any>?

    @JvmStatic
    external fun nativeClearComposition(session: Long): Array<Any>?

    // ---- 开关与方案 ----

    @JvmStatic
    external fun nativeSetOption(session: Long, option: String, value: Boolean)

    @JvmStatic
    external fun nativeGetOption(session: Long, option: String): Boolean

    /** 返回 [id0, name0, id1, name1, ...] 的扁平数组。 */
    @JvmStatic
    external fun nativeGetSchemaList(): Array<Any>?

    @JvmStatic
    external fun nativeSelectSchema(session: Long, schemaId: String): Boolean

    // ---- 定制能力（报告 §2.4 点名，不是可选） ----

    /** 返回每个 tab 三槽的扁平数组：[label, span, source, ...]。 */
    @JvmStatic
    external fun nativeGetInputTabs(session: Long, position: Int): Array<Any>?

    @JvmStatic
    external fun nativeSelectTab(
        session: Long,
        position: Int,
        label: String,
        span: Int
    ): Array<Any>?

    @JvmStatic
    external fun nativeClearTabs(session: Long): Array<Any>?

    /** 返回 [matchingSegments, seg0, seg1, ...]。 */
    @JvmStatic
    external fun nativeGetCandidateCode(session: Long, index: Int): Array<Any>?

    // ---- 部署 / 维护 ----

    @JvmStatic
    external fun nativeIsMaintenanceMode(): Boolean

    @JvmStatic
    external fun nativeStartMaintenance(fullCheck: Boolean): Boolean

    @JvmStatic
    external fun nativeJoinMaintenanceThread()

    @JvmStatic
    external fun nativeSyncUserData(): Boolean

    // ---- 杂项 ----

    @JvmStatic
    external fun nativeGetInput(session: Long): String?

    @JvmStatic
    external fun nativeGetCaretPos(session: Long): Int

    // ---- native → Java 的回调 ----

    /**
     * librime 的 notification 回调入口。签名必须与 native 侧查到的
     * "(JLjava/lang/String;Ljava/lang/String;)V" 一致，否则 native 侧 GetStaticMethodID 拿不到。
     *
     * deploy 三态的 messageValue 为 "start" / "success" / "failure"，sessionId 为 0。
     * 这条路径需要真机才走得到，本机无设备，标注为未验证。
     */
    @JvmStatic
    fun onRimeNotification(sessionId: Long, messageType: String?, messageValue: String?) {
        RimeNotificationSink.onNotification(sessionId, messageType, messageValue)
    }
}

/*
 * 通知的落地处。单独拆出来是因为 RimeBridge 是 object（JVM 静态方法），
 * 而通知需要被 DeployKeyGate 消费；放这里可以让两者解耦，也便于单测里直接喂事件。
 */
object RimeNotificationSink {

    private val listeners = ArrayList<(Long, String?, String?) -> Unit>()

    fun register(l: (Long, String?, String?) -> Unit) {
        listeners.add(l)
    }

    fun clear() = listeners.clear()

    fun onNotification(sessionId: Long, type: String?, value: String?) {
        for (l in listeners) l(sessionId, type, value)
    }

    /**
     * 把通知接到部署门控上：deploy start 开门、success/failure 关门。
     * 这是「部署状态机」在 Kotlin 侧的落地，native 侧另有 g_deploy_running 作为兜底。
     */
    fun bindDeployGate(gate: DeployKeyGate) {
        register { _, type, value ->
            if (type == "deploy") {
                when (value) {
                    "start" -> gate.onDeployStart()
                    "success", "failure" -> gate.onDeployEnd(value == "success")
                }
            }
        }
    }
}
