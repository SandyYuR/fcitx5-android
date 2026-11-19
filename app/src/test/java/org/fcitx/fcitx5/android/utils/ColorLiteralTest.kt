/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorLiteralTest {
    @Test
    fun sixDigitHexGetsOpaqueAlpha() {
        assertEquals(0xFF5C6B89.toInt(), ColorLiteral.parseArgb("#5C6B89"))
    }

    @Test
    fun eightDigitHexKeepsAlpha() {
        assertEquals(0x805C6B89.toInt(), ColorLiteral.parseArgb("#805C6B89"))
    }

    @Test
    fun shortHexFormsExpand() {
        assertEquals(0xFFAABBCC.toInt(), ColorLiteral.parseArgb("#abc"))
        assertEquals(0x88AABBCC.toInt(), ColorLiteral.parseArgb("#8abc"))
    }

    @Test
    fun zeroExAndDecimalStillWork() {
        assertEquals(0xFF5C6B89.toInt(), ColorLiteral.parseArgb("0xFF5C6B89"))
        assertEquals(-1, ColorLiteral.parseArgb("-1"))
        assertEquals(255, ColorLiteral.parseArgb("255"))
    }

    @Test
    fun invalidInputReturnsNull() {
        assertNull(ColorLiteral.parseArgb(null))
        assertNull(ColorLiteral.parseArgb(""))
        assertNull(ColorLiteral.parseArgb("null"))
        assertNull(ColorLiteral.parseArgb("#12345"))
        assertNull(ColorLiteral.parseArgb("#GGGGGG"))
    }
}
