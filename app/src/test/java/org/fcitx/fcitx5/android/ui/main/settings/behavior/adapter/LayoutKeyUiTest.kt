/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutKeyUiTest {
    @Test fun `identity and serializer defaults do not mark an alphabet key`() {
        assertFalse(LayoutKeyUi.hasCustomizedProperties(mapOf(
            "type" to "AlphabetKey", "main" to "a", "alt" to "!", "weight" to 0.1,
            "transparent" to false, "staticDisplay" to false, "textColor" to null
        )))
        assertFalse(LayoutKeyUi.hasCustomizedProperties(mapOf("type" to "SpaceKey", "weight" to 0)))
    }

    @Test fun `explicit display text is meaningful even when equal to main`() {
        val key = linkedMapOf<String, Any?>("type" to "AlphabetKey", "main" to "a", "displayText" to "a")
        val snapshot = key.toMap()
        assertTrue(LayoutKeyUi.hasCustomizedProperties(key))
        org.junit.Assert.assertEquals(snapshot, key)
    }

    @Test fun `nondefault widths macros and presentation flags remain visible`() {
        assertTrue(LayoutKeyUi.hasCustomizedProperties(mapOf("type" to "AlphabetKey", "weight" to 0.2f)))
        assertTrue(LayoutKeyUi.hasCustomizedProperties(mapOf("type" to "PlaceholderKey", "transparent" to true)))
        assertTrue(LayoutKeyUi.hasCustomizedProperties(mapOf("type" to "AlphabetKey", "composeOverride" to mapOf("main" to "x"))))
        assertFalse(LayoutKeyUi.hasCustomizedProperties(mapOf("type" to "MacroKey", "label" to "X", "tap" to listOf("x"))))
        assertTrue(LayoutKeyUi.hasCustomizedProperties(mapOf("type" to "MacroKey", "swipeUp" to listOf("x"))))
    }
}
