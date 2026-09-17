/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BackspaceBoundaryGuard] 的状态机测试。
 *
 * 覆盖万象退格保护（`super_processor/enable_backspace_limit`）在 Android 应用层的
 * 还原语义：**删空编码之后，同一按住序列内的所有重复退格都被吞掉，直到手指抬起**
 * （不是只吞一次）；手指抬起后的新一次按压恢复正常的删正文行为。
 *
 * 关键约束（回归重点）：
 * - 长按期间 Rime 每次重复退格都会回调到 Java（引擎未消费），必须全部吞掉；
 * - 抬手时键盘发出的 DeleteSelectionAction（onUserKeyAction）结束保护；
 * - 抬手后要吸收两类"漏网"事件（真机日志实测）：
 *   1. 在途重复退格（沉降窗口）——抬手前发出、抬手后才回到 Java；
 *   2. 抬手抖动按压（抖动窗口）——实测松手后 79ms 又出现 74ms 的按压，
 *      否则会"松手后又多删一个字"；
 * - 上屏（选词/回车/字符提交）同样会清空 composing，但随后的 preedit 空事件
 *   不是"删空"，必须抑制，否则用户上屏后按退格删刚上屏的字会被误吞；
 * - composing 清空会产生 client 与 panel 两次独立的空事件，抑制必须用
 *   绝对时间窗口，不能用一次性标志。
 */
class BackspaceBoundaryGuardTest {

    private class Clock(var now: Long = 0L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun guard(clock: Clock) = BackspaceBoundaryGuard(
        uptimeMillis = { clock.now },
        armWindowMs = BackspaceBoundaryGuard.DefaultArmWindowMs,
        protectionIdleTimeoutMs = BackspaceBoundaryGuard.DefaultProtectionIdleTimeoutMs,
        inFlightSettleMs = BackspaceBoundaryGuard.DefaultInFlightSettleMs,
        bounceWindowMs = BackspaceBoundaryGuard.DefaultBounceWindowMs,
        bounceAbsorbTtlMs = BackspaceBoundaryGuard.DefaultBounceAbsorbTtlMs
    )

    /** 走过抬手沉降窗口（在途重复退格的吸收窗口）。 */
    private fun pastSettle(clock: Clock) {
        clock.advance(BackspaceBoundaryGuard.DefaultInFlightSettleMs + 1)
    }

    @Test
    fun idleBackspacePassesThrough() {
        val clock = Clock()
        val guard = guard(clock)
        // 从未输入过（无码）时的普通退格：直接删正文，不拦截。
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    /**
     * 核心用例：长按删空编码后，重复退格全部被吞，直到手指抬起。
     *
     * 日志现场（09-17 19:25:44）：删空后第一次退格被吞，随后每 ~50ms 的重复退格
     * 都回调到 Java 并删掉了正文（光标 4→3→2→1→0）。这里断言全部 Consume。
     */
    @Test
    fun boundaryConsumesAllRepeatsUntilGestureEnd() {
        val clock = Clock()
        val guard = guard(clock)
        // 打字 → composing 非空；长按删码 → composing 被删空。
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onComposingStateChanged(nonEmpty = false)
        // 删空后紧接着的第一次退格：吞掉并进入保护。
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        // 同一按住序列内的重复退格：全部吞掉（间隔 50ms，远小于兜底超时）。
        repeat(10) {
            clock.advance(50)
            assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        }
        // 手指抬起（键盘发出 DeleteSelectionAction 等非退格动作）。
        guard.onUserKeyAction()
        // 抬手后的新一次按压：恢复删正文（先走过沉降窗口）。
        pastSettle(clock)
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    /**
     * 抬手边界之一：在途重复退格（抬手前发出、抬手后才回到 Java）必须被吸收，
     * 否则松手瞬间会多删一个字。
     */
    @Test
    fun inFlightRepeatAfterLiftIsAbsorbed() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onComposingStateChanged(nonEmpty = false)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        // 手指抬起；紧接着引擎把最后一次重复退格送回来（在途事件）。
        guard.onUserKeyAction()
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        // 沉降窗口内的后续在途事件同样吞掉。
        clock.advance(20)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        // 沉降窗口过后，用户真正的新按压照常删正文。
        pastSettle(clock)
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    /**
     * 抬手边界之二：抬手抖动按压。
     *
     * 日志现场（09-17 20:55:17）：松手（17.280）后 79ms 又出现一次 74ms 的按压
     * （17.358→17.432），其退格在 17.437 删掉了正文一个字。抖动按压应被吸收。
     */
    @Test
    fun bouncePressAfterLiftIsAbsorbed() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onComposingStateChanged(nonEmpty = false)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        guard.onUserKeyAction() // 抬手：17.280
        // 79ms 后又碰了一下（抖动按压开始）。
        clock.advance(79)
        guard.onBackspacePressStarted()
        // 这次按压的退格（按压结束后才到达 Java）：吞掉。
        clock.advance(78)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        // 抖动按压自身也抬手了。
        guard.onUserKeyAction()
        // 抖动吸收窗口内仍吞。
        clock.advance(50)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        // 窗口过后恢复正常。
        clock.advance(BackspaceBoundaryGuard.DefaultBounceAbsorbTtlMs + 1)
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    /** 抬手后隔得足够久的新按压是有意操作，必须删字，不能被抖动逻辑吃掉。 */
    @Test
    fun deliberatePressAfterHoldDeletes() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onComposingStateChanged(nonEmpty = false)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        guard.onUserKeyAction()
        // 超过抖动窗口（150ms）才按下的新按压：有意操作。
        clock.advance(BackspaceBoundaryGuard.DefaultBounceWindowMs + 50)
        guard.onBackspacePressStarted()
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
        pastSettle(clock)
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    @Test
    fun gestureEndBeforeBoundaryBackspaceDisarms() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onComposingStateChanged(nonEmpty = false)
        // 删空后手指已抬起（没有在窗口内继续按退格），随后再次按下是新的一次：
        // 不应被吞。
        guard.onUserKeyAction()
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    @Test
    fun armWindowExpires() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onComposingStateChanged(nonEmpty = false)
        // 删空很久之后才按退格：不属于"删空后紧接着"的边界。
        clock.advance(BackspaceBoundaryGuard.DefaultArmWindowMs + 1)
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    @Test
    fun protectionIdleTimeoutReleasesBackspaceKey() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onComposingStateChanged(nonEmpty = false)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        // 抬起信号丢失（没有任何退格到达，也没有手势结束动作）：兜底超时后放行，
        // 避免退格键被永久吞掉。
        clock.advance(BackspaceBoundaryGuard.DefaultProtectionIdleTimeoutMs + 1)
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    @Test
    fun newInputEndsProtection() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onComposingStateChanged(nonEmpty = false)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        // 保护期内出现新的 composing（用户开始打新的编码）：保护解除。
        guard.onComposingStateChanged(nonEmpty = true)
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    @Test
    fun commitEndsProtection() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onComposingStateChanged(nonEmpty = false)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        // 上屏（选词/回车）：保护结束。
        guard.onCommit()
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    @Test
    fun sessionChangeEndsProtection() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onComposingStateChanged(nonEmpty = false)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        // 切换输入框：保护结束，旧会话不得吞新编辑器的退格。
        guard.onSessionChanged()
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    @Test
    fun commitSuppressesFollowingClearEvents() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        // 选词上屏：commit 先到，client/panel 的空事件随后才到。
        guard.onCommit()
        // 两次独立的空事件都不得记为删空（抑制用绝对时间窗口，非一次性标志）。
        guard.onComposingStateChanged(nonEmpty = false)
        guard.onComposingStateChanged(nonEmpty = false)
        // 上屏后按退格删刚上屏的字：必须放行，不得误吞。
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    @Test
    fun sessionChangeSuppressesResetClearEvents() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        // 切换输入框：复位，且 reset() 异步产生的空事件一并抑制。
        guard.onSessionChanged()
        guard.onComposingStateChanged(nonEmpty = false)
        guard.onComposingStateChanged(nonEmpty = false)
        assertEquals(BackspaceBoundaryGuard.Decision.PassThrough, guard.onBackspace())
    }

    @Test
    fun newInputAfterCommitStartsFreshBoundary() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onCommit()
        guard.onComposingStateChanged(nonEmpty = false)
        // 上屏后又打了新字再删空：是一次新的边界，照样保护（并且持续吞）。
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onComposingStateChanged(nonEmpty = false)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
        clock.advance(50)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
    }

    @Test
    fun suppressionExpires() {
        val clock = Clock()
        val guard = guard(clock)
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onCommit()
        guard.onComposingStateChanged(nonEmpty = false)
        // 抑制窗口过期后，新的"变空"不再被抑制。
        clock.advance(BackspaceBoundaryGuard.DefaultArmWindowMs + 1)
        guard.onComposingStateChanged(nonEmpty = true)
        guard.onComposingStateChanged(nonEmpty = false)
        assertEquals(BackspaceBoundaryGuard.Decision.Consume, guard.onBackspace())
    }
}
