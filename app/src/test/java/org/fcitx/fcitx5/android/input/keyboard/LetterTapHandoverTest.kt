/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterTapHandoverTest {
    @Test
    fun recognizesBuiltInAndMacroLetterKeys() {
        assertTrue(KeyAction.FcitxKeyAction("g").isSingleVirtualLetterTap())
        assertTrue(KeyAction.FcitxKeyAction("G").isSingleVirtualLetterTap())
        assertTrue(MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("A"))))).isSingleVirtualLetterTap())
    }

    @Test
    fun preservesOtherKeyAndMacroGestures() {
        assertFalse(KeyAction.FcitxKeyAction("1").isSingleVirtualLetterTap())
        assertFalse(KeyAction.FcitxKeyAction("G", up = true).isSingleVirtualLetterTap())
        assertFalse(KeyAction.FcitxKeyAction("G", states = KeyStates(KeyState.Virtual, KeyState.Shift)).isSingleVirtualLetterTap())
        val one = MacroStep.Tap(listOf(KeyRef.Fcitx("A")))
        assertFalse(MacroAction(listOf(one, one)).isSingleVirtualLetterTap())
        assertFalse(MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("Control_L"), KeyRef.Fcitx("A"))))).isSingleVirtualLetterTap())
    }
}
