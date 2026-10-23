/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [KeySym.editorUnicode] decides what the frontend commits when the engine did
 * not consume a key. The fallback must never change for ordinary keys, and the
 * keypad block must mirror X11 NumLock-on meanings exactly once.
 */
class KeySymEditorUnicodeTest {

    @Test fun keypadDigitsFoldToAsciiDigits() {
        for (d in 0..9) {
            val kp = FcitxKeyMapping.FcitxKey_KP_0 + d
            assertEquals("KP_$d", '0'.code + d, KeySym(kp).editorUnicode)
        }
    }

    @Test fun keypadSymbolsFoldToTheirNumLockOnCharacters() {
        assertEquals('*'.code, KeySym(FcitxKeyMapping.FcitxKey_KP_Multiply).editorUnicode)
        assertEquals('+'.code, KeySym(FcitxKeyMapping.FcitxKey_KP_Add).editorUnicode)
        assertEquals('-'.code, KeySym(FcitxKeyMapping.FcitxKey_KP_Subtract).editorUnicode)
        assertEquals('/'.code, KeySym(FcitxKeyMapping.FcitxKey_KP_Divide).editorUnicode)
        assertEquals('.'.code, KeySym(FcitxKeyMapping.FcitxKey_KP_Decimal).editorUnicode)
        assertEquals(','.code, KeySym(FcitxKeyMapping.FcitxKey_KP_Separator).editorUnicode)
        assertEquals('='.code, KeySym(FcitxKeyMapping.FcitxKey_KP_Equal).editorUnicode)
    }

    @Test fun keypadEnterStaysZeroSoItIsDeliveredAsAKeyCode() {
        // Committing a raw newline would bypass the editor's Enter handling.
        assertEquals(0, KeySym(FcitxKeyMapping.FcitxKey_KP_Enter).editorUnicode)
    }

    @Test fun ordinaryAsciiIsPreserved() {
        assertEquals(' '.code, KeySym(0x20).editorUnicode)
        assertEquals('0'.code, KeySym('0'.code).editorUnicode)
        assertEquals('~'.code, KeySym('~'.code).editorUnicode)
        assertEquals(0xff, KeySym(0xff).editorUnicode)
    }

    @Test fun latinOneSupplementRangeIsPreserved() {
        // 0x100..0x178: Latin-1 supplement and Latin Extended-A, not in 0xff00..0xffff
        assertEquals(0xe9, KeySym(0xe9).editorUnicode) // é, direct range segment
        assertEquals(0x100, KeySym(0x100).editorUnicode) // Ā
        assertEquals(0x178, KeySym(0x178).editorUnicode) // Ÿ
    }

    @Test fun privateUseAliasUnpacksToUnicode() {
        // Fcitx packs non-X11 codepoints into 0x01xxxxxx
        assertEquals(0x4e2d, KeySym(0x01004e2d).editorUnicode) // 中
        assertEquals(0x1f600, KeySym(0x0101f600).editorUnicode) // 😀
    }

    @Test fun unicodeRangeAboveLatinOneIsPreserved() {
        assertEquals(0x4e2d, KeySym(0x4e2d).editorUnicode)
        assertEquals(0x10ffff, KeySym(0x10ffff).editorUnicode)
    }

    @Test fun otherFunctionKeysymsStayZero() {
        // These must keep falling back to the keycode path, not commit text.
        assertEquals(0, KeySym(0xff0d).editorUnicode) // XK_Return
        assertEquals(0, KeySym(0xffe1).editorUnicode) // XK_Shift_L
        assertEquals(0, KeySym(0xff52).editorUnicode) // XK_Up
        assertEquals(0, KeySym(0xff08).editorUnicode) // XK_BackSpace
        // Just past the KP digit block: unmapped function-key territory.
        assertEquals(0, KeySym(0xffba).editorUnicode)
    }
}
