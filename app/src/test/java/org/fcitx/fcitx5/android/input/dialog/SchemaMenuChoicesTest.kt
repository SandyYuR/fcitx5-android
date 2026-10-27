/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.dialog

import android.app.Activity
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.radiobutton.MaterialRadioButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class SchemaMenuChoicesTest {
    private fun fixture(fontScale: Float = 1f, block: (Activity, android.content.Context) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.Theme_FcitxSettings_Base_Light)
        controller.setup()
        try {
            val activity = controller.get()
            val config = Configuration(activity.resources.configuration).apply { this.fontScale = fontScale }
            val context = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_FcitxSettings_Base_Light)
            block(activity, context)
        } finally { controller.pause().stop().destroy() }
    }
    private fun layout(view: ScrollView, width: Int = 360, height: Int = 240) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun rows(view: ScrollView) = view.getChildAt(0) as LinearLayout

    @Test fun manySchemasCanScrollToTheLastEntry() = fixture { activity, context ->
        val entries = List(40) { "方案 $it" to (700 + it) }
        val view = buildSchemaMenuChoices(context, entries, 0) {}
        activity.setContentView(view)
        layout(view)
        assertTrue(view.canScrollVertically(1))
        view.scrollTo(0, rows(view).height)
        assertTrue(view.scrollY > 0)
        assertFalse(view.canScrollVertically(1))
        assertTrue(rows(view).getChildAt(39).bottom <= view.scrollY + view.height)
    }

    @Test fun landscapeAndLargeFontsWrapLongLabelsRatherThanClipFixedRows() = fixture(2f) { activity, context ->
        val view = buildSchemaMenuChoices(context, List(8) { "A long schema label with multiple words $it ".repeat(4) to it }, -1) {}
        activity.setContentView(view)
        layout(view, width = 480, height = 160)
        val first = rows(view).getChildAt(0) as MaterialRadioButton
        // Legacy Robolectric text measurement does not wrap glyphs like Android.
        // The actual large-font line count is checked by SchemaMenuChoicesViewTest.
        assertTrue(first.maxLines > 1)
        assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, first.layoutParams.height)
        assertTrue(first.height >= (48 * context.resources.displayMetrics.density).toInt())
        view.scrollTo(0, rows(view).height)
        assertFalse(view.canScrollVertically(1))
        assertTrue(rows(view).getChildAt(7).bottom <= view.scrollY + view.height)
    }

    @Test fun initialSelectionMatchesTheEnabledSchemaOrRemainsUnset() = fixture { _, context ->
        for (selected in listOf(-1, 7)) {
            val view = buildSchemaMenuChoices(context, List(10) { "schema $it" to it }, selected) {}
            val rows = rows(view)
            for (index in 0 until rows.childCount) {
                assertEquals(index == selected, (rows.getChildAt(index) as MaterialRadioButton).isChecked)
            }
        }
    }

    @Test fun lastEntryClickUsesItsEngineActionId() = fixture { activity, context ->
        var selected: Int? = null
        val entries = List(30) { "schema $it" to (900 + it * 13) }
        val view = buildSchemaMenuChoices(context, entries, 0) { selected = it }
        activity.setContentView(view)
        layout(view)
        view.scrollTo(0, rows(view).height)
        rows(view).getChildAt(29).performClick()
        assertEquals(entries.last().second, selected)
    }

    @Test fun anEmptyMenuDoesNotSelectAnImplicitSchema() = fixture { activity, context ->
        var selected = false
        val view = buildSchemaMenuChoices(context, emptyList(), -1) { selected = true }
        activity.setContentView(view)
        layout(view)
        assertEquals(0, rows(view).childCount)
        assertFalse(selected)
    }
}
