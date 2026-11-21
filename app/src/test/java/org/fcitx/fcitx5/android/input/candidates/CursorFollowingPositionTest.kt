/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates

import org.fcitx.fcitx5.android.input.candidates.floating.CursorFollowingPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class CursorFollowingPositionTest {
    private fun position(
        x: Float,
        top: Float,
        bottom: Float,
        rtl: Boolean = false,
        limit: Float = 600f
    ): Pair<Float, Float> {
        val placement = CursorFollowingPosition.calculate(400f, limit, 150, 80, x, top, bottom, 8f, rtl)
        return placement.x to placement.y
    }

    @Test
    fun tracksBothAxes() {
        assertEquals(40f to 128f, position(40f, 100f, 120f))
        assertEquals(90f to 228f, position(90f, 200f, 220f))
    }

    @Test
    fun flipsAboveKeyboardAndClampsRightEdge() {
        assertEquals(242f to 452f, position(390f, 540f, 560f))
    }

    @Test
    fun rtlAlignsRightEdgeToCursor() {
        assertEquals(150f to 128f, position(300f, 100f, 120f, true))
        assertEquals(8f to 128f, position(20f, 100f, 120f, true))
    }

    @Test
    fun absentOrInvalidAnchorFallsBackAboveKeyboard() {
        assertEquals(8f to 512f, position(0f, 0f, 0f))
        assertEquals(242f to 512f, position(Float.NaN, 0f, 0f, true))
        assertEquals(8f to 512f, position(40f, 90f, 80f))
    }

    @Test
    fun clampsOffscreenCursor() {
        assertEquals(8f to 8f, position(-20f, -90f, 10f, limit = 60f))
        assertEquals(242f to 512f, position(1000f, 900f, 920f))
    }

    @Test
    fun fitsBothDrawingAndTouchBoundsInTinyLandscapeViewport() {
        val placement = CursorFollowingPosition.calculate(100f, 60f, 150, 80, 20f, 100f, 120f, 8f)
        assertEquals(CursorFollowingPosition.Placement(8f, 8f, 84, 44), placement)
        assertEquals(92f, placement.x + placement.width)
        assertEquals(52f, placement.y + placement.height)
    }

    @Test
    fun leavesNoTouchableHeightWhenKeyboardFillsViewport() {
        val placement = CursorFollowingPosition.calculate(400f, 0f, 150, 80, 40f, 100f, 120f, 8f)
        assertEquals(0, placement.height)
    }
}
