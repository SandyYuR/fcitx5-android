/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym

internal data class StickyModifierGesture(val modifier: StickyModifier, val id: Long)

/**
 * 粘滞修饰键的状态机。
 *
 * [latched] 是唯一的事实来源：其中的修饰键会修饰下一次可消费的按键，被消费后清空。
 * 物理 Down 只登记 [pending]（用于配对 Up、阻止多指重复按下），**不改变** [latched]：
 * 手势尚未确定时键盘状态不会被提前改写，修饰键自身的长按/滑动动作也只按「按下前」
 * 的状态决定是否带修饰。
 */
internal class StickyModifierController {
    internal class Consumption internal constructor(
        internal val id: Long,
        val modifiers: List<StickyModifier>
    )

    /** 已点亮、等待被消费的修饰键，按点亮顺序排列。 */
    private val latched = LinkedHashMap<StickyModifier, Unit>()

    private data class PendingGesture(val id: Long, var consumed: Boolean = false)

    /** 保留物理按压的占位；被消费的手势抬手时只清理，不再切换点亮。 */
    private val pending = HashMap<StickyModifier, PendingGesture>()

    private val listeners = mutableSetOf<() -> Unit>()
    private var nextGesture = 0L
    private var nextConsumption = 0L
    private var consuming: Consumption? = null

    val hasActiveModifiers: Boolean get() = latched.isNotEmpty()

    fun isLatched(modifier: StickyModifier): Boolean = latched.containsKey(modifier)

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    /** 登记一次物理按下；返回手势 id，重复按下或正在消费时返回 null。 */
    fun onDown(modifier: StickyModifier): Long? {
        if (consuming != null) return null
        if (pending.containsKey(modifier)) return null
        val gesture = ++nextGesture
        pending[modifier] = PendingGesture(gesture)
        return gesture
    }

    /**
     * 抬手确认。只有「有效单击」才切换 [latched]；取消或非单击保持原状态。
     *
     * 非单击（长按、滑动、滑出键外）时不改动状态，动作执行方查到的就是按下前的值。
     */
    fun onUp(modifier: StickyModifier, gesture: Long?, clickEligible: Boolean, cancelled: Boolean) {
        val recorded = pending[modifier] ?: return
        if (gesture == null || recorded.id != gesture) return
        pending.remove(modifier)
        if (recorded.consumed || cancelled || !clickEligible) return
        if (latched.remove(modifier) == null) {
            latched[modifier] = Unit
        }
        notifyChanged()
    }

    /** 原子地取走全部已点亮的修饰键；尚未锁存的按压不参与消费。 */
    fun consume(): Consumption? {
        if (consuming != null || latched.isEmpty()) return null
        val consumption = Consumption(++nextConsumption, latched.keys.toList())
        // 只失效本次参与消费的手势，保留占位直到匹配的 Up，避免旧指未抬时同键重入。
        consumption.modifiers.forEach { pending[it]?.consumed = true }
        latched.clear()
        consuming = consumption
        notifyChanged()
        return consumption
    }

    fun finish(consumption: Consumption) {
        if (consuming?.id == consumption.id) consuming = null
    }

    fun cancelAll() {
        if (latched.isEmpty() && pending.isEmpty() && consuming == null) return
        latched.clear()
        pending.clear()
        consuming = null
        notifyChanged()
    }
}

/**
 * 宏恰好是「一次单击、一个 Fcitx 键」时返回该键码，否则返回 null。
 *
 * 内置自定义布局用这种宏表示普通字母与符号键
 * （`tap: {macro: [{type: "tap", keys: [{fcitx: "A"}]}]}`），它等价于一次普通按键，
 * 因此可以消费粘滞修饰键。多步宏、文本提交、快捷键与按住/释放类宏都不是单次普通按键，
 * 保持原有语义不消费。
 */
internal fun MacroAction.singleFcitxTapCode(): String? {
    val step = steps.singleOrNull() as? MacroStep.Tap ?: return null
    return (step.keys.singleOrNull() as? KeyRef.Fcitx)?.code
}

/**
 * 快捷键路径使用的 Android 键位，取值与 [android.view.KeyEvent] 的 KEYCODE_* 一致。
 *
 * 写成常量而不是引用 KeyEvent，是为了让 JVM 单测不必加载 android 桩类。
 */
internal val StickyModifier.shortcutKeyCode: Int
    get() = when (this) {
        StickyModifier.Ctrl -> 113 // KeyEvent.KEYCODE_CTRL_LEFT
        StickyModifier.Shift -> 59 // KeyEvent.KEYCODE_SHIFT_LEFT
        StickyModifier.Alt -> 58 // KeyEvent.KEYCODE_ALT_LEFT
    }

/** 与 [shortcutKeyCode] 配套的 scanCode（与 `sendFcitxKeyDown` 中的常量一致）。 */
internal val StickyModifier.shortcutScanCode: Int
    get() = when (this) {
        StickyModifier.Ctrl -> 29
        StickyModifier.Shift -> 42
        StickyModifier.Alt -> 56
    }

internal data class StickyShortcutEvent(val keyCode: Int, val scanCode: Int, val down: Boolean)

/**
 * 快捷键的模拟物理键序列：修饰键按激活顺序 Down → 键 Down → 键 Up → 修饰键逆序 Up。
 *
 * 普通键没有可用 Android 键位时返回空列表：调用方只消费粘滞状态，绝不改为提交文本。
 */
internal fun stickyShortcutEvents(
    modifiers: List<StickyModifier>,
    keyCode: Int,
    scanCode: Int
): List<StickyShortcutEvent> {
    if (keyCode < 0) return emptyList()
    return buildList {
        modifiers.forEach {
            add(StickyShortcutEvent(it.shortcutKeyCode, it.shortcutScanCode, down = true))
        }
        add(StickyShortcutEvent(keyCode, scanCode, down = true))
        add(StickyShortcutEvent(keyCode, scanCode, down = false))
        modifiers.asReversed().forEach {
            add(StickyShortcutEvent(it.shortcutKeyCode, it.shortcutScanCode, down = false))
        }
    }
}

/**
 * 仅 Shift + 字母时改写为「大写 keysym + Virtual|Shift」，让 Rime 把它当大写字母组成候选；
 * 其它动作返回 null，走快捷键路径。
 */
internal fun KeyAction.stickyShiftLetterAction(): KeyAction? = when (this) {
    is KeyAction.FcitxKeyAction -> {
        val letter = act.singleOrNull()?.takeIf { it.isLetter() }
        if (letter == null) null else copy(
            act = letter.uppercaseChar().toString(),
            states = KeyStates(KeyState.Virtual, KeyState.Shift),
            up = false
        )
    }
    is KeyAction.SymAction -> {
        val code = sym.sym
        val isLetter = code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code
        if (!isLetter) null else copy(
            sym = KeySym(code.toChar().uppercaseChar().code),
            states = KeyStates(KeyState.Virtual, KeyState.Shift),
            up = false
        )
    }
    else -> null
}
