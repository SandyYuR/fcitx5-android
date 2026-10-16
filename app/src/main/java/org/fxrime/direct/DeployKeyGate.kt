package org.fxrime.direct

/*
 * DeployKeyGate — 部署期的按键门控。
 *
 * 为什么必须有这一层（报告 §4「部署」领域）：
 *   现有链路里这件事由 fcitx5-rime 的 rimestate.cpp:183-202 承担，原文是
 *     「maintenance mode 或无 session 时 event.filterAndAccept() 吞键」，
 *   注释解释了原因：未处理的键会被 frontend 当普通字符提交进编辑器，产生 "hhh" 之类的垃圾文本，
 *   所以按下与抬起都要吞，编辑器不能收到没有 keydown 的 keyup。
 *   直连之后没有 AndroidFrontend / InputContext 帮我们 filter，这个门控必须显式重建，
 *   否则用户会在每次部署时看到一串乱码被写进输入框。
 *
 * 与 native 侧的分工：
 *   native/rime_bridge.c 的 nativeProcessKey 里已经用 dr_gate_key 判了一次，
 *   并在门控命中时仍然返回 shape 完整的数组（dispatch=SWALLOW）。
 *   这一层负责的是 Kotlin 侧的对外语义：告诉 IMS「这次要不要把键交给编辑器」，
 *   以及「down/up 成对吞」这条不变式。
 *
 * 只依赖 kotlin-stdlib，不 import android.*，因此可以在 JVM 上编译与单测。
 */
class DeployKeyGate {

    /** 部署是否进行中。由 native 的 notification（deploy start/success/failure）驱动。 */
    @Volatile
    var maintenance: Boolean = false
        private set

    /** 当前是否有可用 session。 */
    @Volatile
    var hasSession: Boolean = false
        private set

    /** 统计：被吞的 down / up 各多少。 */
    private var swallowedDown = 0L
    private var swallowedUp = 0L

    fun onDeployStart() { maintenance = true }
    fun onDeployEnd(success: Boolean) { maintenance = false }
    fun onSessionCreated() { hasSession = true }
    fun onSessionDestroyed() { hasSession = false }

    val stats: String
        get() = "swallowedDown=$swallowedDown swallowedUp=$swallowedUp"

    /**
     * 判定一次按键该怎么走。
     * 返回值与 native 的 dr_dispatch 同义，但这里额外维护 down/up 成对吞的不变式：
     * 只要门控开着，down 与 up 都必须返回 SWALLOW，不能只吞一半。
     */
    fun gate(mappedOk: Boolean, isDown: Boolean): Int {
        if (!mappedOk) return RimeSnapshotSlots.DISPATCH_DROP
        if (maintenance || !hasSession) {
            if (isDown) swallowedDown++ else swallowedUp++
            return RimeSnapshotSlots.DISPATCH_SWALLOW
        }
        return RimeSnapshotSlots.DISPATCH_TO_RIME
    }

    /**
     * 把 native 返回的 dispatch 翻译成「这次按键是否该交给编辑器」。
     *
     * 关键不变式：SWALLOW 时 down 与 up 都不给编辑器。
     * 这条来自 rimestate.cpp 的注释——编辑器不能收到没有 keydown 的 keyup，
     * 否则某些编辑器会把孤立的 keyup 解释成别的操作。
     */
    fun shouldForwardToEditor(dispatch: Int): Boolean = dispatch == RimeSnapshotSlots.DISPATCH_DROP

    /**
     * 一次按键的最终处理。返回 true 表示应当把键交给编辑器（即引擎完全不处理）。
     * snapshot 为 null（native 出错）时不放行 —— 宁可吞，也不能在状态不明时把键漏给编辑器。
     */
    fun resolve(snapshot: RimeSnapshot?, isDown: Boolean): Boolean {
        if (snapshot == null) return false
        return shouldForwardToEditor(snapshot.dispatch)
    }

    /**
     * 部署结束后的补一次判定：部署期间积压的 down 若没有配对 up，
     * 由调用方决定是否补发。这里只报告数字，不替调用方决定。
     */
    fun unmatchedDownCount(): Long = swallowedDown - swallowedUp
}
