/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates

import android.graphics.Typeface
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

class CandidateTypefaceTest {
    @Test fun replacingTypefaceRemeasuresUnchangedText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val text = AutoScaleTextView(instrumentation.targetContext).apply {
                    setPadding(0, 0, 0, 0)
                    textSize = 32f
                    typeface = Typeface.MONOSPACE
                    text = "MMMMMMMM"
                }
                val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY)
                text.measure(widthSpec, heightSpec)
                val oldWidth = text.measuredWidth
                text.typeface = Typeface.SERIF
                text.measure(widthSpec, heightSpec)
                val expectedWidth = ceil(text.paint.measureText(text.text.toString())).toInt()
                assertNotEquals("The font fixture must change the natural width", oldWidth, expectedWidth)
                assertEquals("Unchanged text must use the new font's width", expectedWidth, text.measuredWidth)
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
