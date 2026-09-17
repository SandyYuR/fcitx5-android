/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.os.SystemClock

/**
 * 退格边界保护（Backspace boundary guard）。
 *
 * 还原万象拼音 `super_processor/enable_backspace_limit` 的语义：长按/连续退格把编码区
 * 删空之后，在**手指一直按着退格键**的整个过程中，后续退格不应继续向前删除已经提交的
 * 正文；直到手指抬起（退格按住序列结束）才恢复正常删除。
 *
 * 桌面 Lua 的实现要点（`handle_backspace`）：
 * - 记录连续退格序列中每次到达时的输入长度；
 * - 当"上一次长度 1 → 本次长度 0"时返回 kAccepted，**并且在此之前就 return，
 *   因此 `bs_prev_len` 一直保持 1** —— 于是同一按住序列里随后的每一次重复退格
 *   都持续命中同一个条件，全部被吞；而不是只吞一次；
 * - 按键 release 或按下别的键时复位序列，之后新的退格才恢复删正文。
 *
 * 为什么必须在应用层重做：
 * - Lua 侧的拦截分支带 `not is_mobile_device() and not is_special_desktop()` 平台守卫，
 *   Fcitx5 Android 上恒不命中；
 * - 本项目的链路里，空 composing 的 BackSpace 会经 `AndroidFrontend::keyEvent` 回调到
 *   Java（引擎未 accept），再由 `FcitxInputMethodService.handleBackspaceDirectly`
 *   直接删除编辑器正文 —— 这一步必须由应用层拦。
 *
 * 驱动信号：
 * - [onComposingStateChanged]：`ClientPreeditEvent` / `InputPanelEvent` 到达时喂入
 *   "composing 是否非空"（调用方需合并 client 与 panel 两部分后传入）。从非空变空
 *   即"删空"，记为保护待命起点；
 * - [onBackspace]：引擎未消费的退格（即将走 Java 直删通道之前）调用。命中则返回
 *   [Decision.Consume]：**一旦吞下第一次，就进入保护态持续吞掉同一按住序列里的所有
 *   后续退格**；
 * - [onBackspacePressStarted]：退格键 ACTION_DOWN（由键盘层转发）。用于识别"长按抬手后
 *   极短时间内又开始的新按压"——那是抬手抖动/回弹，不是用户真想删字；
 * - [onUserKeyAction]：任何"非退格按键"的用户动作都表示退格按住序列已经结束
 *   （退格键抬起时键盘会发出 DeleteSelectionAction，其它按键同理）；
 * - [onCommit] / [onSessionChanged]：上屏或输入会话切换，结束保护并抑制随后的
 *   "清空"事件被误判为删空。
 *
 * 两类"抬手边界"的兜底（真机上实测到的两个漏网场景）：
 * - **在途重复退格**：重复退格每隔约 50ms 发一次，紧贴抬手前发出的那一次，其引擎回调
 *   会晚于抬手到达。抬手后保留 [DefaultInFlightSettleMs] 的沉降窗口继续吞掉它；
 * - **抖动按压**：实测日志里松手后 79ms 又出现一次 74ms 的按压（疑似抬手回弹），保护
 *   已在抬手时结束，于是那次按压删掉了正文一个字。若新按压在抬手后
 *   [DefaultBounceWindowMs] 内开始，则判定为抖动，其退格在
 *   [DefaultBounceAbsorbTtlMs] 内一并吞掉；隔得更久的按压是有意操作，照常删字。
 *
 * 其它兜底：保护态内每次吞退格都会刷新时间戳，若超过 [protectionIdleTimeoutMs]
 * 没有任何退格到达（正常按住时重复间隔约 50ms），说明抬起信号丢失，解除保护。
 *
 * 线程：实例由输入法服务持有，所有方法都在 IME 主线程调用（`handleFcitxEvent`
 * 收集器、按键回调、键盘动作分发同为主线程），无需同步。单元测试可注入
 * [uptimeMillis] 控制时间。
 *
 * 无副作用约束：本类只做判断，绝不直接操作 InputConnection；调用方按返回值决定
 * "吞掉 / 继续执行原删除逻辑"。
 */
class BackspaceBoundaryGuard(
    private val uptimeMillis: () -> Long = { SystemClock.uptimeMillis() },
    private val armWindowMs: Long = DefaultArmWindowMs,
    private val protectionIdleTimeoutMs: Long = DefaultProtectionIdleTimeoutMs,
    private val inFlightSettleMs: Long = DefaultInFlightSettleMs,
    private val bounceWindowMs: Long = DefaultBounceWindowMs,
    private val bounceAbsorbTtlMs: Long = DefaultBounceAbsorbTtlMs
) {
    /** [onBackspace] 的返回值：是否吞掉这次退格。 */
    enum class Decision { Consume, PassThrough }

    // 上一次已知的 composing 是否非空。
    private var wasComposing = false
    // "删空"发生时刻（uptimeMillis）；-1 表示当前没有待命中的边界。
    private var clearedAtMs: Long = -1L
    // 是否处于保护态：同一按住序列内的退格将持续被吞，直到手指抬起。
    private var protecting = false
    // 保护态内最近一次吞退格的时刻，用于抬起信号丢失时的兜底超时。
    private var lastProtectedAtMs: Long = -1L
    // 抬手后在途重复退格的吸收窗口终点。
    private var settleUntilMs: Long = -1L
    // 受保护按住序列的抬手时刻；-1 表示没有待判定的抬手。
    private var liftedAtMs: Long = -1L
    // 抖动按压的吸收窗口终点。
    private var bounceAbsorbUntilMs: Long = -1L
    // 在此时刻之前到达的"变空"事件不记为删空（上屏/会话切换造成的清空）。
    private var suppressClearUntilMs: Long = -1L

    /**
     * 引擎 composing 状态变化时调用。
     *
     * @param nonEmpty 当前 composing 是否非空。调用方需把 client preedit 与
     * panel preedit 两部分合并后传入（Android 上 Rime 配置为 `PreeditMode::No`，
     * client preedit 恒空，panel preedit 才是真实输入区内容），否则 client 的
     * 空事件会把"正在输入"误判成"删空"。
     */
    fun onComposingStateChanged(nonEmpty: Boolean) {
        if (nonEmpty) {
            wasComposing = true
            clearedAtMs = -1L
            // 出现新的 composing 说明进入了新的输入周期（手指不可能同时按住退格
            // 又打出编码），保护、沉降与抖动吸收状态一并失效。
            suppressClearUntilMs = -1L
            endProtection()
            clearBoundaryExtras()
            return
        }
        if (!wasComposing) return
        wasComposing = false
        if (uptimeMillis() <= suppressClearUntilMs) {
            // 上屏/会话切换造成的清空，不是删空。
            return
        }
        clearedAtMs = uptimeMillis()
    }

    /**
     * 引擎未消费的退格到达（Java 直删编辑器之前）调用。
     *
     * @return [Decision.Consume] 表示吞掉这次退格（不删编辑器正文）；
     * [Decision.PassThrough] 表示按原逻辑继续。
     */
    fun onBackspace(): Decision {
        val now = uptimeMillis()
        if (protecting) {
            if (now - lastProtectedAtMs > protectionIdleTimeoutMs) {
                // 抬起信号丢失（超过保护空闲超时没有退格到达），兜底解除保护。
                endProtection()
            } else {
                // 同一按住序列：继续吞，并刷新兜底时间戳。
                lastProtectedAtMs = now
                return Decision.Consume
            }
        }
        if (now <= settleUntilMs) {
            // 抬手前发出、抬手后才回到 Java 的在途重复退格：一并吞掉。
            return Decision.Consume
        }
        if (now <= bounceAbsorbUntilMs) {
            // 抬手抖动按压产生的退格：吞掉，避免"松手后又多删一个字"。
            return Decision.Consume
        }
        if (clearedAtMs < 0L) return Decision.PassThrough
        if (now - clearedAtMs > armWindowMs) {
            // 删空已经过去很久，这次退格是新的一次按压，不是边界。
            clearedAtMs = -1L
            return Decision.PassThrough
        }
        // 删空之后紧接着到达的退格：吞掉并进入保护态，后续重复退格一并不删除正文，
        // 直到手指抬起（onUserKeyAction）或出现新的输入。
        protecting = true
        lastProtectedAtMs = now
        clearedAtMs = -1L
        return Decision.Consume
    }

    /**
     * 退格键被按下（ACTION_DOWN，由键盘层转发）。
     *
     * 若这次按压紧跟在一次受保护的按住序列抬手之后（[bounceWindowMs] 内），
     * 判定为抬手抖动/回弹：它的退格在 [bounceAbsorbTtlMs] 内一并吞掉。
     * 隔得更久的按压是用户有意操作，不受影响。
     */
    fun onBackspacePressStarted() {
        val now = uptimeMillis()
        if (liftedAtMs >= 0L && now - liftedAtMs <= bounceWindowMs) {
            bounceAbsorbUntilMs = now + bounceAbsorbTtlMs
        }
        liftedAtMs = -1L
    }

    /**
     * 任何"非退格按键"的用户动作到达时调用：退格按住序列已经结束
     * （退格键抬起时键盘发出的 DeleteSelectionAction 也走这里）。
     *
     * 若结束的是一次"受保护的按住"（手指刚抬起），额外开启沉降窗口以吸收在途的
     * 最后一次重复退格，并记下抬手时刻供抖动按压判定。
     */
    fun onUserKeyAction() {
        val wasProtecting = protecting
        endProtection()
        clearedAtMs = -1L
        if (wasProtecting) {
            val now = uptimeMillis()
            settleUntilMs = now + inFlightSettleMs
            liftedAtMs = now
        } else {
            // 用户做了别的事情：抖动窗口作废。
            liftedAtMs = -1L
        }
    }

    /**
     * 上屏提交（`CommitStringEvent`）到达时调用。
     *
     * 上屏同样会清空 composing，但随后的 preedit 空事件不是"删空"：抑制一个窗口，
     * 否则用户上屏后按退格删刚上屏的字会被误吞。上屏也意味着当前退格按住序列结束。
     */
    fun onCommit() {
        endProtection()
        clearBoundaryExtras()
        wasComposing = false
        clearedAtMs = -1L
        suppressClearUntilMs = uptimeMillis() + armWindowMs
    }

    /**
     * 输入会话切换（onStartInput/onFinishInput/onBindInput/onUnbindInput）与
     * 剪贴板搜索启停时调用。旧会话事件不得吞新编辑器的退格；且 `reset()` 异步
     * 产生的 preedit 空事件随后才到达，一并抑制。
     */
    fun onSessionChanged() {
        endProtection()
        clearBoundaryExtras()
        wasComposing = false
        clearedAtMs = -1L
        suppressClearUntilMs = uptimeMillis() + armWindowMs
    }

    private fun endProtection() {
        protecting = false
        lastProtectedAtMs = -1L
    }

    private fun clearBoundaryExtras() {
        settleUntilMs = -1L
        liftedAtMs = -1L
        bounceAbsorbUntilMs = -1L
    }

    companion object {
        /**
         * 删空之后多久内到达的退格算作"边界退格"。
         *
         * 长按重复间隔约 50ms（见 `CustomGestureView.RepeatInterval`），边界那次
         * 紧随删空事件到达；超过这个窗口的退格视为用户重新按下，按普通删除处理。
         */
        const val DefaultArmWindowMs = 500L

        /**
         * 保护态兜底超时：正常按住时每 ~50ms 有一次退格，超过该值没有任何退格到达
         * 说明手指抬起的信号丢失，解除保护，避免退格键永久失效。
         */
        const val DefaultProtectionIdleTimeoutMs = 600L

        /**
         * 抬手后的沉降窗口：吸收"抬手前发出、抬手后才回到 Java"的在途重复退格。
         * 重复退格间隔约 50ms，加上线程往返，80ms 足以覆盖一次在途事件。
         */
        const val DefaultInFlightSettleMs = 80L

        /**
         * 抬手后多久内开始的退格按压算作抖动/回弹。实测日志里松手后 79ms 出现过一次
         * 74ms 的按压；人类有意的再次按压间隔通常在 150ms 以上。
         */
        const val DefaultBounceWindowMs = 150L

        /**
         * 抖动按压的退格吸收时长（自按压开始计）。实测抖动按压约 74ms，
         * 其退格在按压结束后才到达 Java；300ms 覆盖按压时长加往返延迟。
         */
        const val DefaultBounceAbsorbTtlMs = 300L
    }
}
