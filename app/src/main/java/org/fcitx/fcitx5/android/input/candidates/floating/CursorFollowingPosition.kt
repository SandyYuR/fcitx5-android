/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.floating

/**
 * Places the floating candidates window next to the text cursor.
 *
 * Coordinates are in the candidate container's space. [top] and [bottom] are the
 * cursor's vertical edges, [horizontal] is the caret's horizontal position.
 * An unusable anchor falls back to the bottom leading corner above the keyboard.
 */
internal object CursorFollowingPosition {
    data class Placement(val x: Float, val y: Float, val width: Int, val height: Int)

    fun maxSize(space: Float, gap: Float): Int = (space - 2 * gap).toInt().coerceAtLeast(0)

    fun calculate(
        width: Float,
        bottomLimit: Float,
        selfWidth: Int,
        selfHeight: Int,
        horizontal: Float,
        top: Float,
        bottom: Float,
        gap: Float,
        rtl: Boolean = false
    ): Placement {
        val visibleWidth = selfWidth.coerceAtMost(maxSize(width, gap))
        val visibleHeight = selfHeight.coerceAtMost(maxSize(bottomLimit, gap))
        val maxX = (width - visibleWidth - gap).coerceAtLeast(gap)
        val maxY = (bottomLimit - visibleHeight - gap).coerceAtLeast(gap)
        val valid = horizontal.isFinite() && top.isFinite() && bottom.isFinite() &&
            bottom >= top && bottom > 0f
        if (!valid) return Placement(if (rtl) maxX else gap, maxY, visibleWidth, visibleHeight)
        val x = (if (rtl) horizontal - visibleWidth else horizontal).coerceIn(gap, maxX)
        val below = bottom + gap
        val above = top - gap - visibleHeight
        val y = if (below + visibleHeight > bottomLimit - gap &&
            top - gap > bottomLimit - below
        ) above else below
        return Placement(x, y.coerceIn(gap, maxY), visibleWidth, visibleHeight)
    }
}
