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
    fun numberRowUsesToolbarForegroundVariant() {
        val appearances = NumberRow.Layout
            .single()
            .map { it.appearance }

        assertEquals(10, appearances.size)
        appearances.forEach { appearance ->
            assertEquals(KeyDef.Appearance.Variant.AltForeground, appearance.variant)
        }
    }
}
