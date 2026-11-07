/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import com.google.android.material.textview.MaterialTextView
import org.fcitx.fcitx5.android.utils.settingsColor

/** Explicit data typography: large bold values, small regular labels and units. */
internal object StatsTypography {
    enum class Role(val sp: Float, val weight: Int) {
        HERO(28f, Typeface.BOLD),
        VALUE(24f, Typeface.BOLD),
        SPEED(34f, Typeface.BOLD),
        SECTION(16f, Typeface.BOLD),
        LABEL(12f, Typeface.NORMAL),
        BODY(14f, Typeface.NORMAL)
    }

    fun text(context: Context, value: String, role: Role, color: Int? = null) =
        MaterialTextView(context).apply {
            text = value
            tag = role
            setTextSize(TypedValue.COMPLEX_UNIT_SP, role.sp)
            typeface = Typeface.create("sans-serif", role.weight)
            includeFontPadding = false
            setTextColor(color ?: context.settingsColor(
                if (role == Role.LABEL) com.google.android.material.R.attr.colorOnSurfaceVariant
                else com.google.android.material.R.attr.colorOnSurface))
        }

    fun value(context: Context, value: String, role: Role = Role.VALUE, color: Int? = null) =
        ValueView(context, value, role, color)

    internal class ValueView(context: Context, value: String, private val role: Role, color: Int?) : MaterialTextView(context) {
        private val minimumSp = if (role == Role.SPEED) 26f else 18f
        val minimumValueWidth: Float
            get() = TextPaint(paint).apply { textSize = sp(minimumSp) }
                .measureText(text.toString()) + compoundPaddingLeft + compoundPaddingRight + 2

        init {
            text = value
            tag = role
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            setHorizontallyScrolling(false)
            maxLines = 1
            textDirection = View.TEXT_DIRECTION_LTR
            ellipsize = null
            setTextColor(color ?: context.settingsColor(com.google.android.material.R.attr.colorOnSurface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, role.sp)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Float.MAX_VALUE
                else (MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1).toFloat()
            val desired = sp(role.sp)
            // Never mutate TextView's paint for a measurement probe: setTextSize
            // would then see the same size and leave a stale text layout behind.
            val natural = TextPaint(paint).apply { textSize = desired }.measureText(text.toString())
            // The grid changes column count first. This last fit handles long numeric
            // values without dropping digits or adding an ellipsis.
            val fitted = if (natural > available - 2) desired * (available - 2).coerceAtLeast(1f) / natural else desired
            val minimum = sp(minimumSp)
            // An extreme lifetime total can wrap at a readable size even after
            // the grid has reduced to one column. Every digit stays available.
            val lines = if (fitted < minimum) Int.MAX_VALUE else 1
            if (maxLines != lines) maxLines = lines
            setTextSize(TypedValue.COMPLEX_UNIT_PX, maxOf(minimum, fitted))
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }
}

/** Four metrics at normal width; two or one column when font size or labels need more space. */
internal class StatsMetricGrid(context: Context, private val preferredColumns: Int) : ViewGroup(context) {
    private val gap = (12 * resources.displayMetrics.density).toInt()
    private var columns = preferredColumns
    private var cellWidth = 0
    private var rowHeights = emptyList<Int>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            (320 * resources.displayMetrics.density).toInt() else MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        fun valueWidth(view: View): Float = when (view) {
            is StatsTypography.ValueView -> view.minimumValueWidth
            is ViewGroup -> (0 until view.childCount).maxOfOrNull { valueWidth(view.getChildAt(it)) }
                ?.plus(view.paddingLeft + view.paddingRight) ?: 0f
            else -> 0f
        }
        val labelSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        val minimum = maxOf((if (preferredColumns >= 3) 5f else 92f / 12f) * labelSize,
            (0 until childCount).maxOfOrNull { valueWidth(getChildAt(it)) } ?: 0f)
        columns = preferredColumns.coerceAtMost(childCount.coerceAtLeast(1))
        while (columns > 1 && (available - gap * (columns - 1)) / columns < minimum) {
            columns = if (columns > 2) 2 else 1
        }
        cellWidth = ((available - gap * (columns - 1)) / columns).coerceAtLeast(0)
        val childWidth = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val rows = (childCount + columns - 1) / columns
        rowHeights = (0 until rows).map { row ->
            var height = 0
            for (index in row * columns until minOf((row + 1) * columns, childCount)) {
                val child = getChildAt(index)
                child.measure(childWidth, childHeight)
                height = maxOf(height, child.measuredHeight)
            }
            height
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(
            rowHeights.sum() + gap * (rows - 1).coerceAtLeast(0) + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var y = paddingTop
        rowHeights.forEachIndexed { row, height ->
            for (index in row * columns until minOf((row + 1) * columns, childCount)) {
                val col = index % columns
                val child = getChildAt(index)
                val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - paddingRight - cellWidth - col * (cellWidth + gap)
                    else paddingLeft + col * (cellWidth + gap)
                child.layout(x, y, x + cellWidth, y + child.measuredHeight)
            }
            y += height + gap
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
