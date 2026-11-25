/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickyModifierCompositionTest {
    @Test
    fun onlySingleKeyTapMacrosAreStickyConsumable() {
        // 内置自定义布局用「单键 Tap 宏」表示普通字母/符号键。
        assertEquals(
            "A",
            MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("A"))))).singleFcitxTapCode()
        )
        // 多键、多步、文本、按住/释放、Android 键都不是单次普通按键。
        assertNull(
            MacroAction(
                listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("A"), KeyRef.Fcitx("B"))))
            ).singleFcitxTapCode()
        )
        assertNull(
            MacroAction(listOf(MacroStep.Text("a"))).singleFcitxTapCode()
        )
        assertNull(
            MacroAction(listOf(MacroStep.Down(listOf(KeyRef.Fcitx("A"))))).singleFcitxTapCode()
        )
        assertNull(
            MacroAction(listOf(MacroStep.Up(listOf(KeyRef.Fcitx("A"))))).singleFcitxTapCode()
        )
        assertNull(
            MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Android(66))))).singleFcitxTapCode()
        )
        assertNull(
            MacroAction(
                listOf(
                    MacroStep.Tap(listOf(KeyRef.Fcitx("A"))),
                    MacroStep.Text("b")
                )
            ).singleFcitxTapCode()
        )
        assertNull(MacroAction(emptyList()).singleFcitxTapCode())
    }

    @Test
    fun shortcutSendsModifierDownThenKeyTapThenReverseRelease() {
        val events = stickyShortcutEvents(listOf(StickyModifier.Ctrl), 29, 30)
        assertEquals(
            listOf(
                StickyShortcutEvent(113, 29, down = true),
                StickyShortcutEvent(29, 30, down = true),
                StickyShortcutEvent(29, 30, down = false),
                StickyShortcutEvent(113, 29, down = false)
            ),
            events
        )
    }

    @Test
    fun shortcutReleasesCombinedModifiersInReverseActivationOrder() {
        val events = stickyShortcutEvents(
            listOf(StickyModifier.Ctrl, StickyModifier.Shift), 29, 30
        )
        assertEquals(listOf(113, 59, 29, 29, 59, 113), events.map { it.keyCode })
        assertEquals(listOf(29, 42, 30, 30, 42, 29), events.map { it.scanCode })
        assertEquals(
            listOf(true, true, true, false, false, false),
            events.map { it.down }
        )
    }

    @Test
    fun shortcutWithoutAndroidKeyCodeProducesNoEvents() {
        assertTrue(stickyShortcutEvents(listOf(StickyModifier.Ctrl), -1, 0).isEmpty())
    }

    @Test
    fun shiftOnlyLetterIsUpperCasedAsVirtualInput() {
        val fcitx = KeyAction.FcitxKeyAction("a", 38, KeyStates(KeyState.Virtual))
        assertEquals(
            KeyAction.FcitxKeyAction(
                "A", 38, KeyStates(KeyState.Virtual, KeyState.Shift), up = false
            ),
            fcitx.stickyShiftLetterAction()
        )
        val sym = KeyAction.SymAction(KeySym('b'.code), KeyStates(KeyState.Virtual))
        assertEquals(
            KeyAction.SymAction(
                KeySym('B'.code), KeyStates(KeyState.Virtual, KeyState.Shift), up = false
            ),
            sym.stickyShiftLetterAction()
        )
    }

    @Test
    fun nonLettersAreNotShiftUpperCasedInput() {
        val actions = listOf(
            KeyAction.FcitxKeyAction("1", 10),
            KeyAction.FcitxKeyAction("Left", 113),
            KeyAction.FcitxKeyAction("minus", 12),
            KeyAction.SymAction(KeySym('1'.code)),
            KeyAction.SymAction(KeySym(0xff51)),
            KeyAction.CommitAction("a"),
            MacroAction(emptyList())
        )
        actions.forEach { assertNull(it.toString(), it.stickyShiftLetterAction()) }
    }
}
