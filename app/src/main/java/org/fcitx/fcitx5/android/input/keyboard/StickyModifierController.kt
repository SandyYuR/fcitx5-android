/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym

internal data class StickyModifierGesture(val modifier: StickyModifier, val id: Long)

/** Main-thread state for soft Ctrl/Shift/Alt keys waiting for one ordinary key. */
internal class StickyModifierController {
    private enum class SlotState { Held, Latched }

    private data class Slot(
        val modifier: StickyModifier,
        var state: SlotState,
        var pendingGesture: Long?
    )

    internal class Consumption internal constructor(
        internal val id: Long,
        val modifiers: List<StickyModifier>
    )

    private val slots = LinkedHashMap<StickyModifier, Slot>()
    private val listeners = mutableSetOf<() -> Unit>()
    private var nextGesture = 0L
    private var nextConsumption = 0L
    private var consuming: Consumption? = null

    val hasActiveModifiers: Boolean get() = slots.isNotEmpty()

    fun isLatched(modifier: StickyModifier): Boolean = slots[modifier]?.state == SlotState.Latched

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    /** Returns this gesture's identity, or null for a duplicate/rejected physical Down. */
    fun onDown(modifier: StickyModifier): Long? {
        if (consuming != null) return null
        val existing = slots[modifier]
        if (existing?.pendingGesture != null) return null
        val gesture = ++nextGesture
        if (existing == null) {
            slots[modifier] = Slot(modifier, SlotState.Held, gesture)
        } else {
            // A second tap on an already latched key is armed here; only its eligible Up cancels.
            existing.pendingGesture = gesture
        }
        notifyChanged()
        return gesture
    }

    fun onUp(modifier: StickyModifier, gesture: Long?, clickEligible: Boolean, cancelled: Boolean) {
        val slot = slots[modifier] ?: return
        if (gesture == null || slot.pendingGesture != gesture) return
        slot.pendingGesture = null
        if (cancelled || !clickEligible) {
            if (slot.state == SlotState.Held) slots.remove(modifier)
        } else {
            when (slot.state) {
                SlotState.Held -> slot.state = SlotState.Latched
                SlotState.Latched -> slots.remove(modifier)
            }
        }
        notifyChanged()
    }

    /** Atomically takes every Held/Latched slot in activation order. */
    fun consume(): Consumption? {
        if (consuming != null || slots.isEmpty()) return null
        val consumption = Consumption(++nextConsumption, slots.keys.toList())
        slots.clear()
        consuming = consumption
        notifyChanged()
        return consumption
    }

    fun finish(consumption: Consumption) {
        if (consuming?.id == consumption.id) consuming = null
    }

    fun cancelAll() {
        if (slots.isEmpty() && consuming == null) return
        slots.clear()
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
