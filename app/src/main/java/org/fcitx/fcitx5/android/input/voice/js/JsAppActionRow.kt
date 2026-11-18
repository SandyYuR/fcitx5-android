/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.ui.common.WrappingActionRow
import kotlin.math.ceil

/** Uses the same whole-button wrapping as settings forms. */
internal class JsAppActionRow(context: Context) : WrappingActionRow(context)

/** A root header can stay above scrolling content without changing any plugin events. */
internal class JsAppHeaderRow(context: Context) : LinearLayout(context) {
    private var switcher: JsAppSwitcher? = null

    fun insertSwitcher(view: JsAppSwitcher) {
        switcher = view
        addView(view, 1, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tools = switcher
        if (tools != null && childCount == 3 && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val title = getChildAt(0) as TextView
            val close = getChildAt(2)
            close.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val titleWidth = (ceil(title.paint.measureText(title.text.toString())).toInt() +
                title.compoundPaddingStart + title.compoundPaddingEnd).coerceAtMost(width * 3 / 5)
            val margins = (0 until childCount).sumOf {
                val params = getChildAt(it).layoutParams as LayoutParams
                params.marginStart + params.marginEnd
            }
            tools.fitWidth(width - paddingStart - paddingEnd - margins - titleWidth - close.measuredWidth)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
