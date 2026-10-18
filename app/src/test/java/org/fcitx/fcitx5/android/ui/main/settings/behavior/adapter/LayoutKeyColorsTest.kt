/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter

import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutKeyColorsTest {
    private val light = ThemePreset.MaterialLight
    private val dark = ThemePreset.MaterialDark

    @Test fun `ordinary special accent and alt-foreground keys follow current theme variants`() {
        val colors = LayoutKeyColors()
        for (theme in listOf(light, dark)) {
            assertEquals(theme.keyBackgroundColor, colors.background(mapOf("type" to "AlphabetKey"), theme))
            assertEquals(theme.keyTextColor, colors.foreground(mapOf("type" to "MacroKey"), theme))
            assertEquals(theme.altKeyBackgroundColor, colors.background(mapOf("type" to "BackspaceKey"), theme))
            assertEquals(theme.accentKeyBackgroundColor, colors.background(mapOf("type" to "ReturnKey"), theme))
            assertEquals(theme.accentKeyTextColor, colors.foreground(mapOf("type" to "ReturnKey"), theme))
            // This project's LanguageKey uses AltForeground, so its background stays normal.
            assertEquals(theme.keyBackgroundColor, colors.background(mapOf("type" to "LanguageKey"), theme))
            assertEquals(theme.altKeyTextColor, colors.foreground(mapOf("type" to "LanguageKey"), theme))
        }
    }

    @Test fun `theme references stay dynamic and take priority over stored static colors`() {
        val colors = LayoutKeyColors()
        val key = mapOf("type" to "AlphabetKey", "backgroundColorMonet" to "theme:accentKeyBackgroundColor", "backgroundColor" to 1)
        assertEquals(light.accentKeyBackgroundColor, colors.background(key, light))
        assertEquals(dark.accentKeyBackgroundColor, colors.background(key, dark))
    }

    @Test fun `system references and fallback use the existing key field precedence`() {
        val colors = LayoutKeyColors { name -> if (name == "system_accent1_500") 42 else null }
        assertEquals(42, colors.foreground(mapOf("textColorMonet" to "system_accent1_500", "textColor" to 7), light))
        assertEquals(7, colors.foreground(mapOf("textColorMonet" to "missing", "textColor" to 7), light))
        assertEquals(light.keyTextColor, colors.foreground(mapOf("textColorMonet" to "theme:missing"), light))
    }

    @Test fun `transparent colors remain explicit and preview never mutates layout data`() {
        val colors = LayoutKeyColors()
        val key = linkedMapOf<String, Any?>("type" to "MacroKey", "backgroundColor" to 0, "textColor" to "0xff123456")
        val snapshot = key.toMap()
        assertEquals(0, colors.background(key, light))
        assertEquals(0xff123456.toInt(), colors.foreground(key, dark))
        assertEquals(snapshot, key)
    }
}
