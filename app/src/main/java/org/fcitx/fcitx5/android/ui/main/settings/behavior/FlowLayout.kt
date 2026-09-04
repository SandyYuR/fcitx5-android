/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

open class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        // Only UNSPECIFIED means "as wide as you like". AT_MOST carries a real limit — the
        // common case inside a ScrollView or a RecyclerView row — and treating it as unbounded
        // made availableWidth effectively infinite, so children never wrapped: one long row ran
        // off-screen instead of flowing onto the next line.
        val width = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else widthSize

        val paddingLeft = paddingLeft
        val paddingRight = paddingRight
        val paddingTop = paddingTop
        val paddingBottom = paddingBottom

        val availableWidth = width - paddingLeft - paddingRight

        val measured = ArrayList<FlowLayoutLines.Child>(childCount)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            // measureChildWithMargins accounts for the margins this layout honours; measureChild
            // ignored them, so a child with margins could be measured wider than the space it
            // was then laid out in.
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            measured += FlowLayoutLines.Child(
                width = child.measuredWidth + lp.leftMargin + lp.rightMargin,
                height = child.measuredHeight + lp.topMargin + lp.bottomMargin
            )
        }
        val lines = FlowLayoutLines.measure(measured, availableWidth)

        // resolveSize honours the height spec instead of ignoring it, so a fixed or bounded
        // height from the parent is respected rather than silently overflowing.
        val measuredWidth = if (widthMode == MeasureSpec.EXACTLY) {
            widthSize
        } else {
            resolveSize(lines.maxLineWidth + paddingLeft + paddingRight, widthMeasureSpec)
        }
        setMeasuredDimension(
            measuredWidth,
            resolveSize(lines.totalHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val paddingLeft = paddingLeft
        val paddingRight = paddingRight
        val paddingTop = paddingTop

        val availableWidth = width - paddingLeft - paddingRight

        var x = 0
        var y = 0
        var lineHeight = 0

        val childCount = childCount
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            // Mirror the wrap rule used while measuring, including "never wrap before the
            // first child of a line"; otherwise layout and measure disagree about line breaks.
            if (x > 0 && x + childWidth + lp.leftMargin + lp.rightMargin > availableWidth) {
                x = 0
                y += lineHeight
                lineHeight = 0
            }

            val left = x + lp.leftMargin + paddingLeft
            val top = y + lp.topMargin + paddingTop
            val right = left + childWidth
            val bottom = top + childHeight

            child.layout(left, top, right, bottom)

            x += childWidth + lp.leftMargin + lp.rightMargin
            lineHeight = maxOf(lineHeight, childHeight + lp.topMargin + lp.bottomMargin)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateLayoutParams(p: LayoutParams): LayoutParams {
        return MarginLayoutParams(p)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        )
    }

    override fun checkLayoutParams(p: LayoutParams): Boolean {
        return p is MarginLayoutParams
    }
}
