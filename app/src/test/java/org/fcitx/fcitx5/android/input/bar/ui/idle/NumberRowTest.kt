/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.junit.Assert.assertEquals
import org.junit.Test

class NumberRowTest {

    @Test
    fun numberRowUsesToolbarRendererWithKeyFont() {
        val appearances = NumberRow.Layout
            .single()
            .map { it.appearance }

        assertEquals(10, appearances.size)
        appearances.forEach { appearance ->
            // ToolbarText keeps the label out of the keyboard text-scale pipeline but still
            // renders through the normal key-label font path, so digits stay visible while
            // following the user's configured key typeface and font size.
            assertEquals(
                "NumberRow labels must use the toolbar text renderer",
                KeyDef.Appearance.ToolbarText::class,
                appearance::class
            )
            // `textSize` is declared on the Text renderer; `KeyDef.appearance` is typed as the
            // base Appearance, so the property is only reachable through the cast below.
            val textAppearance = appearance as KeyDef.Appearance.Text
            assertEquals(KeyDef.Appearance.Variant.Normal, textAppearance.variant)
            assertEquals(21f, textAppearance.textSize, 0f)
            assertEquals(KeyDef.Appearance.Border.Off, textAppearance.border)
            assertEquals(false, textAppearance.margin)
        }
    }
}
