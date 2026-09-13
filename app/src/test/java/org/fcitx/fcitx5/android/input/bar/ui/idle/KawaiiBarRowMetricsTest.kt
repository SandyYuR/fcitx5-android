/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KawaiiBarRowMetricsTest {

    private val minButtonWidth = 40
    private val spacing = 4

    private fun metrics(available: Int, count: Int) =
        resolveKawaiiBarRowMetrics(available, count, spacing, minButtonWidth)

    @Test
    fun distributesEquallyWhenEveryButtonKeepsMinimumWidth() {
        // (331 - 6*4) / 6 = 51
        val result = metrics(available = 331, count = 6)
        assertTrue(result.evenDistribution)
        assertEquals(51, result.buttonWidth)
    }

    @Test
    fun minimumWidthIsTheBoundaryBetweenEvenAndScrollMode() {
        // 6 * (40 + 4) is exactly enough for every button to reach the minimum width.
        val boundary = metrics(available = 264, count = 6)
        assertTrue(boundary.evenDistribution)
        assertEquals(40, boundary.buttonWidth)

        val onePixelLess = metrics(available = 263, count = 6)
        assertFalse(onePixelLess.evenDistribution)
        assertEquals(0, onePixelLess.buttonWidth)
    }

    @Test
    fun floatingKeyboardWidthStillDistributesEvenly() {
        // Narrower floating keyboard, default 6-button layout: (290 - 24) / 6 = 44
        val result = metrics(available = 290, count = 6)
        assertTrue(result.evenDistribution)
        assertEquals(44, result.buttonWidth)
    }

    @Test
    fun manyButtonsUseScrollMode() {
        val result = metrics(available = 331, count = 12)
        assertFalse(result.evenDistribution)
        assertEquals(0, result.buttonWidth)
    }

    @Test
    fun marginsAreAccountedForPerButton() {
        // (300 - 8*4) / 8 = 33
        val result = metrics(available = 300, count = 8)
        assertFalse(result.evenDistribution)
    }

    @Test
    fun emptyOrUnmeasuredRowStaysOutOfScrollMode() {
        val noButtons = metrics(available = 331, count = 0)
        assertTrue(noButtons.evenDistribution)
        assertEquals(0, noButtons.buttonWidth)

        val noWidth = metrics(available = 0, count = 6)
        assertTrue(noWidth.evenDistribution)
        assertEquals(0, noWidth.buttonWidth)
    }
}
