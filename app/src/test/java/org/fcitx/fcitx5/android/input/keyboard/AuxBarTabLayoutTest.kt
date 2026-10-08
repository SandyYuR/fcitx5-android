/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuxBarTabLayoutTest {

    @Test
    fun invalidInputReturnsNoHeights() {
        assertArrayEquals(IntArray(0), resolveVerticalAuxBarTabItemHeights(0, 3))
        assertArrayEquals(IntArray(0), resolveVerticalAuxBarTabItemHeights(-1, 3))
        assertArrayEquals(IntArray(0), resolveVerticalAuxBarTabItemHeights(400, 0))
        assertArrayEquals(IntArray(0), resolveVerticalAuxBarTabItemHeights(400, -1))
    }

    @Test
    fun sixOrFewerTabsFillTheAvailableHeightEvenly() {
        (1..6).forEach { count ->
            val heights = resolveVerticalAuxBarTabItemHeights(401, count)
            assertEquals(401, heights.sum())
            assertTrue(heights.maxOrNull()!! - heights.minOrNull()!! <= 1)
        }
        assertArrayEquals(
            intArrayOf(401),
            resolveVerticalAuxBarTabItemHeights(containerHeight = 401, itemCount = 1)
        )
        assertArrayEquals(
            intArrayOf(101, 100, 100, 100),
            resolveVerticalAuxBarTabItemHeights(containerHeight = 401, itemCount = 4)
        )
    }

    @Test
    fun moreThanSixTabsExposeSixAndAHalfRows() {
        val heights = resolveVerticalAuxBarTabItemHeights(containerHeight = 650, itemCount = 8)
        assertArrayEquals(IntArray(8) { 100 }, heights)
        assertEquals(650, 6 * heights.first() + heights.first() / 2)
    }
}
