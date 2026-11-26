/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.icon

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28, 35], qualifiers = "w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class IconThemeEditorPreviewTest {
    private val svg = """
        <svg xmlns="http://www.w3.org/2000/svg" width="60px" height="24px" viewBox="0 0 60 24">
          <g><path fill="#5c6b89" d="M2 2h56v20H2z"/></g>
          <metadata>
            <sfw xmlns="http://ns.adobe.com/SaveForWeb/1.0/">
              <slices/>
              <sliceSourceBounds x="0" y="0" width="60" height="24" bottomLeftOrigin="true"/>
            </sfw>
          </metadata>
        </svg>
    """.trimIndent()
    private val brokenSvg = "svg:\n<?xml version=\"1.0\"?>\n<svg><path d=\"" + "M0 0 ".repeat(500)

    private fun context(dark: Boolean = false): Context {
        val app = RuntimeEnvironment.getApplication()
        return SettingsUiPreferences.themedContext(app, SettingsUiPreferences.read(app).copy(
            mode = if (dark) SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
            palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false
        ))
    }

    @After fun resetFontScale() { RuntimeEnvironment.setFontScale(1f) }

    private fun customViews(ui: SlotRowUi): Pair<ImageView, TextView> {
        val column = ui.root.getChildAt(1) as ViewGroup
        return (column.getChildAt(0) as ImageView) to (column.getChildAt(1) as TextView)
    }

    private fun measure(root: View) {
        val density = root.resources.displayMetrics.density
        root.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        assertTrue("Source code must not expand the row", root.height <= (64 * density).toInt())
    }

    @Test fun inlineSvgPrefixesDeclarationsMetadataAndNewlinesRenderAsThumbnails() {
        val headers = listOf("", "<?xml version=\"1.0\"?>\n",
            "\uFEFF<?xml version=\"1.0\"?>\n<!-- exported icon -->\n<!DOCTYPE svg>\n")
        for (prefix in listOf("", "svg:", "svg:\n")) {
            for (header in headers) {
                val raw = prefix + header + svg
                val ui = SlotRowUi(context())
                ui.bind("keys.space", raw, R.drawable.ic_baseline_space_bar_24)
                val (image, summary) = customViews(ui)
                assertEquals(View.VISIBLE, image.visibility)
                val bitmap = checkNotNull((image.drawable as? BitmapDrawable)?.bitmap) {
                    "Missing thumbnail for prefix/header: $prefix / $header"
                }
                assertTrue("Thumbnail must contain visible ink", (0 until bitmap.width).any { x ->
                    (0 until bitmap.height).any { y -> Color.alpha(bitmap.getPixel(x, y)) > 0 }
                })
                assertEquals(View.GONE, summary.visibility)
                assertEquals("", summary.text.toString())
                assertEquals("SVG", SlotRowUi.previewSummary(raw))
                measure(ui.root)
            }
        }
    }

    @Test fun unrenderableMarkupHasShortSingleLineSummaryInLightDarkAndLargeRtlText() {
        val brokenXml = "<?xml version=\"1.0\"?>\n<vector broken=\"" + "metadata\n".repeat(500)
        for (dark in listOf(false, true)) {
            for (scale in listOf(1f, 1.5f)) {
                RuntimeEnvironment.setFontScale(scale)
                val ui = SlotRowUi(context(dark))
                ui.root.layoutDirection = View.LAYOUT_DIRECTION_RTL
                for ((raw, expected) in listOf(brokenSvg to "SVG", brokenXml to "XML")) {
                    ui.bind("keys.space", raw, 0)
                    val (image, summary) = customViews(ui)
                    assertNull(image.drawable)
                    assertEquals(View.GONE, image.visibility)
                    assertEquals(View.VISIBLE, summary.visibility)
                    assertEquals(expected, summary.text.toString())
                    assertEquals(1, summary.maxLines)
                    assertTrue(SlotRowUi.isGraphicContent(raw))
                    measure(ui.root)
                }
            }
        }
    }

    @Test fun recycledRowsReplaceOldPreviewAndBoundTextWithoutChangingTheSource() {
        val ui = SlotRowUi(context())
        val (image, summary) = customViews(ui)
        ui.bind("toolbar.more", svg, 0)
        assertNotNull(image.drawable)
        ui.bind("toolbar.more", brokenSvg, 0)
        assertNull(image.drawable)
        assertEquals("SVG", summary.text.toString())
        val text = "★ custom\n".repeat(100)
        ui.bind("toolbar.more", text, 0)
        assertEquals(View.GONE, image.visibility)
        assertTrue(summary.text.length <= 17)
        assertFalse(summary.text.contains('\n'))
        assertFalse(SlotRowUi.isGraphicContent(text))
        measure(ui.root)
        ui.bind("toolbar.more", null, 0)
        assertEquals("--", summary.text.toString())
        ui.bind("toolbar.more", svg, 0)
        assertEquals(View.VISIBLE, image.visibility)
        assertEquals(View.GONE, summary.visibility)
        assertEquals("", summary.text.toString())
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun openingAndConfirmingEditorKeepsOriginalInlineSvgIncludingInvalidSource() {
        val controller = Robolectric.buildActivity(IconThemeEditorActivity::class.java).setup()
        val activity = controller.get()
        try {
            val icons = ReflectionHelpers.getField<MutableMap<String, String>>(activity, "mutableIcons")
            val slot = "toolbar.more"
            for (raw in listOf("svg:\n\uFEFF<?xml version=\"1.0\"?>\n$svg", brokenSvg)) {
                icons[slot] = raw
                IconThemeEditorActivity::class.java.getDeclaredMethod("showIconSelector", String::class.java).apply {
                    isAccessible = true
                }.invoke(activity, slot)
                val dialog = ReflectionHelpers.getField<AlertDialog>(activity, "currentDialog")
                val views = descendants(dialog.window!!.decorView)
                assertEquals("", views.filterIsInstance<EditText>().single().text.toString())
                assertTrue(views.filterIsInstance<TextView>().none {
                    it.text.contains("<svg") || it.text.contains("<?xml") || it.text.contains("<path")
                })
                views.filterIsInstance<TextView>().single {
                    it.isClickable && it.text.toString() == activity.getString(android.R.string.ok)
                }.performClick()
                assertEquals("Previewing and accepting without text must preserve the full original", raw, icons[slot])
                assertFalse(dialog.isShowing)
            }
        } finally {
            ReflectionHelpers.getField<AlertDialog?>(activity, "currentDialog")?.dismiss()
            controller.pause().stop().destroy()
        }
    }
}
