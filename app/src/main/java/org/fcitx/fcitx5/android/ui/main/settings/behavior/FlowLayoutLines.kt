/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

/**
 * Line-breaking arithmetic for [FlowLayout], as pure functions.
 *
 * Split out of the View so it can be unit-tested, and so `onMeasure` and `onLayout` share one
 * implementation: they used to compute wrapping separately with slightly different conditions,
 * which meant a measured height that did not match where children were actually laid out.
 */
object FlowLayoutLines {

    /** Outer size of one child, margins included. */
    data class Child(val width: Int, val height: Int)

    /**
     * @param totalHeight combined height of all lines
     * @param maxLineWidth width of the widest line, for wrap_content measurement
     * @param lineOf index of the line each child landed on, parallel to the input list
     */
    data class Result(
        val totalHeight: Int,
        val maxLineWidth: Int,
        val lineOf: List<Int>
    )

    /**
     * Flow [children] into lines no wider than [availableWidth].
     *
     * A child never wraps when it would be the first on its line: an item wider than
     * [availableWidth] has nowhere better to go, and wrapping it produced an empty line followed
     * by an overflowing one.
     */
    fun measure(children: List<Child>, availableWidth: Int): Result {
        var totalHeight = 0
        var lineWidth = 0
        var lineHeight = 0
        var maxLineWidth = 0
        var line = 0
        val lineOf = ArrayList<Int>(children.size)

        children.forEach { child ->
            if (lineWidth > 0 && lineWidth + child.width > availableWidth) {
                totalHeight += lineHeight
                maxLineWidth = maxOf(maxLineWidth, lineWidth)
                lineWidth = 0
                lineHeight = 0
                line++
            }
            lineOf += line
            lineWidth += child.width
            lineHeight = maxOf(lineHeight, child.height)
        }

        totalHeight += lineHeight
        maxLineWidth = maxOf(maxLineWidth, lineWidth)
        return Result(totalHeight, maxLineWidth, lineOf)
    }
}
