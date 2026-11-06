/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Keeps whole actions reachable at narrow widths and large font scales. */
open class WrappingActionRow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    private data class Row(val children: List<View>, val width: Int, val height: Int)
    private val rows = mutableListOf<Row>()
    private val gap = (8 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 2
            else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        rows.clear()
        var children = mutableListOf<View>()
        var width = 0
        var height = 0
        fun finishRow() {
            if (children.isNotEmpty()) rows += Row(children, width, height)
            children = mutableListOf(); width = 0; height = 0
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0,
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0)
            val params = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            if (children.isNotEmpty() && width + gap + childWidth > available) finishRow()
            width += (if (children.isEmpty()) 0 else gap) + childWidth
            height = max(height, child.measuredHeight + params.topMargin + params.bottomMargin)
            children += child
        }
        finishRow()
        val desiredWidth = (rows.maxOfOrNull { it.width } ?: 0) + paddingLeft + paddingRight
        val desiredHeight = rows.sumOf { it.height } + gap * (rows.size - 1).coerceAtLeast(0) + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var y = paddingTop
        for (row in rows) {
            var x = if (rtl) paddingLeft + row.width else width - paddingRight - row.width
            for (child in row.children) {
                val params = child.layoutParams as MarginLayoutParams
                val childLeft = if (rtl) x - params.rightMargin - child.measuredWidth else x + params.leftMargin
                val childTop = y + params.topMargin +
                    (row.height - params.topMargin - params.bottomMargin - child.measuredHeight) / 2
                child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
                x += (child.measuredWidth + params.leftMargin + params.rightMargin + gap) * if (rtl) -1 else 1
            }
            y += row.height + gap
        }
    }

    override fun generateDefaultLayoutParams() = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet) = MarginLayoutParams(context, attrs)
    override fun generateLayoutParams(params: LayoutParams) = MarginLayoutParams(params)
    override fun checkLayoutParams(params: LayoutParams) = params is MarginLayoutParams
}
