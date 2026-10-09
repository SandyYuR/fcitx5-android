/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomThemeActionTest {

    @Test
    fun randomThemeActionIsAvailableInBothButtonAreas() {
        assertEquals("random_theme", RandomThemeAction.id)
        assertEquals("toolbar.random_theme", RandomThemeAction.iconSlot)
        assertTrue(RandomThemeAction in ButtonAction.allActions)
        assertTrue(RandomThemeAction in ButtonAction.kawaiiBarActions)
        assertTrue(RandomThemeAction in ButtonAction.statusAreaActions)
        assertTrue(RandomThemeAction in ButtonAction.allConfigurableActions)
    }
}
