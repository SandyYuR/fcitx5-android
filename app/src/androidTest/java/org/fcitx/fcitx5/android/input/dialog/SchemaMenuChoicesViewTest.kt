/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.dialog

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.radiobutton.MaterialRadioButton
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.junit.Assert.*
import org.junit.Test
import splitties.dimensions.dp

class SchemaMenuChoicesViewTest {
    @Test fun largeFontLabelsWrapAndTheLastSchemaCanBeSelectedInLandscape() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val config = Configuration(target.resources.configuration).apply { fontScale = 2f }
            val context = ContextThemeWrapper(target.createConfigurationContext(config), R.style.Theme_FcitxSettings_Base_Light)
            val entries = List(30) { "Long schema label with multiple words $it ".repeat(4) to (900 + it * 13) }
            var selected: Int? = null
            val view = buildSchemaMenuChoices(context, entries, 0) { selected = it }
            view.measure(View.MeasureSpec.makeMeasureSpec(context.dp(320), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(context.dp(160), View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val rows = view.getChildAt(0) as LinearLayout
            val first = rows.getChildAt(0) as MaterialRadioButton
            assertTrue("Actual Android glyph layout must wrap the label", first.lineCount > 1)
            assertTrue(first.height > context.dp(48))
            assertTrue(view.canScrollVertically(1))
            view.scrollTo(0, rows.height)
            assertFalse(view.canScrollVertically(1))
            assertTrue(rows.getChildAt(29).bottom <= view.scrollY + view.height)
            rows.getChildAt(29).performClick()
            assertEquals(entries.last().second, selected)
        }
    }
}
