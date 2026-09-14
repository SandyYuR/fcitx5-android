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
            // textSize only exists on the Text subclasses, so the type assertion has to happen
            // before reading it.
            val toolbarText = requireNotNull(appearance as? KeyDef.Appearance.ToolbarText) {
                "expected ToolbarText, got ${appearance::class}"
            }
            assertEquals(KeyDef.Appearance.Variant.Normal, toolbarText.variant)
            assertEquals(21f, toolbarText.textSize, 0f)
            assertEquals(KeyDef.Appearance.Border.Off, toolbarText.border)
            assertEquals(false, toolbarText.margin)
        }
    }
}
