/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import org.fcitx.fcitx5.android.ui.main.settings.behavior.FlowLayoutLines.Child
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C14: FlowLayout must actually wrap.
 *
 * onMeasure treated MeasureSpec.AT_MOST as unbounded, so inside a ScrollView or a RecyclerView row
 * availableWidth became Int.MAX_VALUE and children never wrapped — the row simply ran off-screen.
 */
class FlowLayoutLinesTest {

    private fun chip(width: Int, height: Int = 10) = Child(width, height)

    @Test
    fun childrenFittingOneLineStayOnOneLine() {
        val result = FlowLayoutLines.measure(listOf(chip(30), chip(30), chip(30)), availableWidth = 100)
        assertEquals(listOf(0, 0, 0), result.lineOf)
        assertEquals(10, result.totalHeight)
        assertEquals(90, result.maxLineWidth)
    }

    @Test
    fun overflowWrapsToTheNextLine() {
        val result = FlowLayoutLines.measure(listOf(chip(60), chip(60)), availableWidth = 100)
        assertEquals(listOf(0, 1), result.lineOf)
        assertEquals("two lines of 10", 20, result.totalHeight)
        assertEquals(60, result.maxLineWidth)
    }

    @Test
    fun lineHeightIsTheTallestChildOnThatLine() {
        val result = FlowLayoutLines.measure(
            listOf(chip(40, height = 10), chip(40, height = 25), chip(80, height = 12)),
            availableWidth = 100
        )
        assertEquals(listOf(0, 0, 1), result.lineOf)
        assertEquals("25 + 12", 37, result.totalHeight)
    }

    @Test
    fun childWiderThanTheLineDoesNotCreateAnEmptyLine() {
        // Wrapping a too-wide first child used to emit an empty line and then overflow anyway.
        val result = FlowLayoutLines.measure(listOf(chip(500), chip(20)), availableWidth = 100)
        assertEquals(listOf(0, 1), result.lineOf)
        assertEquals(20, result.totalHeight)
        assertEquals(500, result.maxLineWidth)
    }

    @Test
    fun exactFitDoesNotWrap() {
        val result = FlowLayoutLines.measure(listOf(chip(50), chip(50)), availableWidth = 100)
        assertEquals("100 fits in 100", listOf(0, 0), result.lineOf)
        assertEquals(10, result.totalHeight)
    }

    @Test
    fun oneOverflowingPixelWraps() {
        val result = FlowLayoutLines.measure(listOf(chip(50), chip(51)), availableWidth = 100)
        assertEquals(listOf(0, 1), result.lineOf)
    }

    @Test
    fun noChildrenMeasuresToNothing() {
        val result = FlowLayoutLines.measure(emptyList(), availableWidth = 100)
        assertEquals(0, result.totalHeight)
        assertEquals(0, result.maxLineWidth)
        assertEquals(emptyList<Int>(), result.lineOf)
    }

    @Test
    fun manyChildrenFlowOntoSeveralLines() {
        val children = List(10) { chip(30) }
        val result = FlowLayoutLines.measure(children, availableWidth = 100)
        // Three per line (90 <= 100, 120 > 100), so lines 0,0,0,1,1,1,2,2,2,3.
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 2, 2, 3), result.lineOf)
        assertEquals(40, result.totalHeight)
        assertEquals(90, result.maxLineWidth)
    }
}
