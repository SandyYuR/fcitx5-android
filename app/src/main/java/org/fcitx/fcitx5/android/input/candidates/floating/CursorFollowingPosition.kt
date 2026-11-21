/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.floating

/**
 * Places the floating candidates window next to the text cursor.
 *
 * Coordinates are in the candidate container's space. [top] and [bottom] are the
 * cursor's vertical edges, [horizontal] is the caret's horizontal position.
 * An unusable anchor falls back to the bottom-left corner above the keyboard.
 */
internal object CursorFollowingPosition {
    fun calculate(
        width: Float,
        bottomLimit: Float,
        selfWidth: Float,
        selfHeight: Float,
        horizontal: Float,
        top: Float,
        bottom: Float,
        gap: Float,
        rtl: Boolean = false
    ): Pair<Float, Float> {
        val maxX = (width - selfWidth - gap).coerceAtLeast(gap)
        val maxY = (bottomLimit - selfHeight - gap).coerceAtLeast(gap)
        val valid = horizontal.isFinite() && top.isFinite() && bottom.isFinite() &&
            bottom >= top && bottom > 0f
        if (!valid) return (if (rtl) maxX else gap) to maxY
        val x = (if (rtl) horizontal - selfWidth else horizontal).coerceIn(gap, maxX)
        val below = bottom + gap
        val above = top - gap - selfHeight
        val y = if (below + selfHeight > bottomLimit - gap &&
            top - gap > bottomLimit - below
        ) above else below
        return x to y.coerceIn(gap, maxY)
    }
}
