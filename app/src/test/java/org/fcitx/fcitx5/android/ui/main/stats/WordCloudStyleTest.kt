/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class WordCloudStyleTest {
    private fun host(dark: Boolean = false, scale: Float = 1f, block: (Activity, Context) -> Unit) {
        RuntimeEnvironment.setFontScale(scale)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val context = SettingsUiPreferences.themedContext(activity, SettingsUiPreferences.read(activity).copy(
                mode = if (dark) SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
                palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false))
            block(activity, context)
        } finally {
            controller.get().finish()
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun layout(view: View, width: Int, height: Int = 280) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun sp(context: Context, size: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, context.resources.displayMetrics)

    @Test fun frequencyHierarchyUsesLargeHeadWordsAndOneThemeHue() = host { activity, context ->
        val input = linkedMapOf("山" to 5000L, "水" to 1000L, "日" to 250L, "月" to 80L,
            "云" to 20L, "雨" to 5L, "风" to 1L)
        val cloud = WordCloudView(context).apply { setWords(input) }
        activity.setContentView(cloud)
        layout(cloud, 320)
        val words = cloud.layoutSnapshot()
        assertEquals(input.keys, words.map { it.word }.toSet())
        assertEquals(sp(context, 41f), words.first().textSize, .02f)
        assertEquals(sp(context, 12f), words.last().textSize, .02f)
        words.zipWithNext().forEach { (first, second) -> assertTrue(first.textSize >= second.textSize) }
        assertTrue(words.take(6).all { it.bold })
        assertFalse(words.last().bold)
        val primary = context.settingsColor(androidx.appcompat.R.attr.colorPrimary)
        val ink = context.settingsColor(com.google.android.material.R.attr.colorOnSurface)
        val tones = listOf(primary, ColorUtils.blendARGB(primary, ink, .24f),
            ColorUtils.blendARGB(primary, ink, .48f), ink)
        assertEquals(tones, words.take(4).map { it.color })
        assertInk(cloud)
    }

    @Test fun longPhrasesAndSupplementaryCharactersKeepTheirFullInkInNarrowLargeText() = host(scale = 1.6f) { activity, context ->
        val phrase = "天地玄黄宇宙洪荒日月盈昃"
        val input = linkedMapOf(phrase to 500L, "输入" to 200L, "你好" to 80L, "生活" to 1L,
            "学习" to 30L, "世界" to 22L, "中文" to 12L, "明天" to 9L, "键盘" to 6L, "𠮷" to 3L)
        val cloud = WordCloudView(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            accessibilityTitle = "字词频次"
            setWords(input)
        }
        activity.setContentView(cloud)
        layout(cloud, 240)
        val first = cloud.layoutSnapshot()
        assertTrue(first.any { it.word == phrase })
        assertTrue(first.all { it.word in input && it.textSize >= sp(context, 10f) - .01f })
        assertTrue(cloud.contentDescription.toString().contains(phrase + ": 500"))
        assertInk(cloud)
        cloud.setWords(input.entries.reversed().associate { it.toPair() })
        assertEquals(first, cloud.layoutSnapshot())
        layout(cloud, 360)
        layout(cloud, 240)
        assertEquals(first, cloud.layoutSnapshot())
        save(cloud, "word-cloud-long-large-rtl-after")
    }

    @Test fun aPhraseThatExactlyFitsAtTheMinimumSizeIsNotDroppedByFittingMargins() = host { activity, context ->
        val phrase = "天地玄黄宇宙洪荒日月盈昃"
        val paint = TextPaint().apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = sp(context, 10f)
        }
        val glyph = Rect()
        paint.getTextBounds(phrase, 0, phrase.length, glyph)
        val largeGlyph = Rect()
        paint.textSize = sp(context, 41f)
        paint.getTextBounds(phrase, 0, phrase.length, largeGlyph)
        val formerFit = paint.textSize * glyph.width() / largeGlyph.width() * .99f
        assertTrue("The fixture must exercise the former below-floor rejection", formerFit < sp(context, 10f))
        val width = glyph.width() + (34 * context.resources.displayMetrics.density).toInt()
        val cloud = WordCloudView(context).apply { setWords(mapOf(phrase to 100L, "风" to 1L)) }
        activity.setContentView(cloud)
        layout(cloud, width)
        val word = cloud.layoutSnapshot().single { it.word == phrase }
        assertEquals(phrase, word.word)
        assertTrue("The complete phrase must remain readable", word.textSize >= sp(context, 10f))
        assertInk(cloud)
        layout(cloud, width - 1)
        assertTrue("Only a genuinely narrower canvas should omit the phrase", cloud.layoutSnapshot().none { it.word == phrase })
        assertTrue(cloud.contentDescription.toString().contains(phrase + ": 100"))
    }

    @Test fun denseCloudsRemainBoundedAndPaintTheirCompleteGlyphsInBothThemes() {
        for ((name, dark, scale) in listOf(Triple("light", false, 1f),
            Triple("dark", true, 1f), Triple("large-rtl", false, 1.6f))) {
            host(dark, scale) { activity, context ->
                val input = (0 until 90).associate { "词" + (0x4E00 + it).toChar() to it + 1L }
                val cloud = WordCloudView(context).apply {
                    layoutDirection = if (scale > 1f) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    accessibilityTitle = "字词频次"
                    setWords(input)
                }
                activity.setContentView(cloud)
                layout(cloud, if (scale > 1f) 240 else 320)
                val words = cloud.layoutSnapshot()
                assertTrue(words.size in 6..64)
                assertEquals(64, cloud.contentDescription.toString().lineSequence().count { ": " in it })
                assertTrue(words.all { it.word in input })
                assertInk(cloud)
                save(cloud, "word-cloud-dense-" + name + "-after")
            }
        }
    }

    /** Check the actual glyph ink, including fallback-font bearings, rather than line-box estimates. */
    private fun assertInk(cloud: WordCloudView) {
        val words = cloud.layoutSnapshot()
        assertTrue(words.isNotEmpty())
        val area = RectF(0f, 0f, cloud.width.toFloat(), cloud.height.toFloat())
        val gap = 3 * cloud.resources.displayMetrics.density
        val paint = TextPaint()
        val background = cloud.context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer)
        val bitmap = Bitmap.createBitmap(cloud.width, cloud.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(background)
            cloud.draw(Canvas(bitmap))
            words.forEachIndexed { index, word ->
                paint.typeface = if (word.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                paint.textSize = word.textSize
                val glyph = Rect()
                paint.getTextBounds(word.word, 0, word.word.length, glyph)
                val ink = RectF(word.originX + glyph.left, word.baseline + glyph.top,
                    word.originX + glyph.right, word.baseline + glyph.bottom)
                assertTrue(word.word + " ink outside the canvas: " + ink, area.contains(ink))
                assertEquals(ink.left, word.bounds.left, .02f)
                assertEquals(ink.top, word.bounds.top, .02f)
                assertEquals(ink.right, word.bounds.right, .02f)
                assertEquals(ink.bottom, word.bounds.bottom, .02f)
                val padded = RectF(ink).apply { inset(-gap, -gap) }
                words.drop(index + 1).forEach { next ->
                    val nextPadded = RectF(next.bounds).apply { inset(-gap, -gap) }
                    assertFalse(word.word + " crowds " + next.word, RectF.intersects(padded, nextPadded))
                }
                var painted = false
                for (y in floor(ink.top).toInt() until ceil(ink.bottom).toInt()) {
                    for (x in floor(ink.left).toInt() until ceil(ink.right).toInt()) {
                        if (bitmap.getPixel(x, y) != background) painted = true
                    }
                }
                assertTrue(word.word + " must actually paint visible ink", painted)
            }
        } finally { bitmap.recycle() }
    }

    private fun save(view: View, name: String) {
        val directory = System.getenv("INPUT_STATS_PREVIEW_DIR") ?: return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(view.context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
            view.draw(Canvas(bitmap))
            File(directory, name + ".png").apply { parentFile!!.mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }
}
