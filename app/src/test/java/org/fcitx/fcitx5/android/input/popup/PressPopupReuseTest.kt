/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.font.FontProviderApi
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PressPopupReuseTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun aDifferentLabelRedrawsTheAttachedEntryWithoutRelayout() {
        val layer = PopupLayer(context)
        val entry = entry()
        val legacy = entry()
        layer.addView(entry.root, FrameLayout.LayoutParams(WIDTH, HEIGHT))
        entry.setText("i")
        layout(layer)
        val first = bitmap(layer)

        entry.setText("宽🙂")
        assertFalse(entry.root.isLayoutRequested)
        assertFalse(layer.isLayoutRequested)

        (legacy.root.getChildAt(0) as TextView).text = "宽🙂"
        layout(legacy.root)
        val updated = bitmap(layer)
        assertFalse(first.sameAs(updated))
        assertTrue(bitmap(legacy.root).sameAs(updated))
    }

    @Test fun reuseFollowsTheConfiguredFontRevision() {
        val original = FontProviders.provider
        val fonts = object : FontProviderApi by original {
            override val fontSizeMap = mutableMapOf("popup_key_font_size" to 20f)
            override var fontDataVersion = 1L
            var customTypeface: Typeface = Typeface.MONOSPACE
            override fun resolveTypeface(key: String, current: Typeface?) = customTypeface
        }
        try {
            FontProviders.provider = fonts
            val entry = entry()
            val label = entry.root.getChildAt(0) as TextView
            assertSame(Typeface.MONOSPACE, label.typeface)
            val initialSize = label.textSize

            fonts.customTypeface = Typeface.SERIF
            fonts.fontSizeMap["popup_key_font_size"] = 26f
            fonts.fontDataVersion++
            FontProviders.provider = fonts
            entry.setText("字")

            assertSame(Typeface.SERIF, label.typeface)
            assertTrue(label.textSize > initialSize)
        } finally {
            FontProviders.provider = original
        }
    }

    @Test fun raisingAnAttachedEntryUpdatesDrawingOrderWithoutRequestingLayout() {
        val layer = PopupLayer(context)
        val (oldest, newer, longPressMenu) = listOf(Color.RED, Color.GREEN, Color.BLUE).map { color ->
            View(context).apply {
                elevation = 2f
                setBackgroundColor(color)
            }
        }
        layer.addView(oldest)
        layer.addView(newer)
        layer.addView(longPressMenu)
        layout(layer)
        assertEquals(listOf(oldest, newer, longPressMenu), drawingOrder(layer))
        assertEquals(Color.BLUE, bitmap(layer).getPixel(WIDTH / 2, HEIGHT / 2))

        layer.raise(oldest)
        assertEquals(listOf(newer, longPressMenu, oldest), drawingOrder(layer))
        assertFalse(layer.isLayoutRequested)
        assertEquals(Color.RED, bitmap(layer).getPixel(WIDTH / 2, HEIGHT / 2))

        layer.removeView(longPressMenu)
        assertEquals(listOf(newer, oldest), drawingOrder(layer))
        layer.addView(longPressMenu)
        assertEquals(listOf(newer, oldest, longPressMenu), drawingOrder(layer))
    }

    private fun entry() = PopupEntryUi(context, ThemePreset.MaterialLight, 48, 5f)

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, WIDTH, HEIGHT)
    }

    private fun bitmap(view: View): Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }

    private fun drawingOrder(layer: PopupLayer) =
        (0 until layer.childCount).map { layer.getChildAt(layer.getChildDrawingOrder(it)) }

    private companion object {
        const val WIDTH = 38
        const val HEIGHT = 116
    }
}
