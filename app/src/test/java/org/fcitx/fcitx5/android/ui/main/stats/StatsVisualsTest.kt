/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
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
import java.text.NumberFormat
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class StatsVisualsTest {
    private fun host(dark: Boolean = false, fontScale: Float = 1f, block: (Activity, Context) -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val context = SettingsUiPreferences.themedContext(activity, SettingsUiPreferences.read(activity).copy(
                mode = if (dark) SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
                palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false))
            block(activity, context)
        } finally { controller.get().finish(); controller.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f) }
    }

    private fun attach(activity: Activity, view: View, width: Int = 320, height: Int? = null) {
        if (view.parent == null) activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height ?: 2000, if (height == null) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertTrue(view.isAttachedToWindow)
    }

    private fun assertCloudGeometry(cloud: WordCloudView) {
        val words = cloud.layoutSnapshot()
        assertTrue("The cloud should place actual words", words.isNotEmpty())
        assertTrue(words.size <= 64)
        for ((index, word) in words.withIndex()) {
            assertTrue(word.bounds.toString(), RectF(0f, 0f, cloud.width.toFloat(), cloud.height.toFloat()).contains(word.bounds))
            assertTrue(word.textSize > 0)
            words.drop(index + 1).forEach { next ->
                assertFalse("${word.word} intersects ${next.word}", RectF.intersects(word.bounds, next.bounds))
            }
        }
    }

    @Test fun cloudIsDeterministicBoundedAndAccessibleWithoutRecordingOrderArtifacts() = host { activity, context ->
        val input = (0 until 90).associate { "词${(0x4E00 + it).toChar()}" to (it + 1L) }
        val cloud = WordCloudView(context).apply { accessibilityTitle = "字词频次"; setWords(input) }
        attach(activity, cloud, height = 280)
        assertCloudGeometry(cloud)
        val before = cloud.layoutSnapshot()
        cloud.setWords(input.entries.reversed().associate { it.toPair() })
        assertEquals(before, cloud.layoutSnapshot())
        assertTrue(cloud.contentDescription.toString().contains("词${(0x4E00 + 89).toChar()}: 90"))
        assertFalse(cloud.contentDescription.toString().contains("词一: 1\n"))
        assertEquals(64, cloud.contentDescription.toString().lineSequence().count { ": " in it })
        cloud.setWords(mapOf("" to 8L, "坏\n词" to 9L, "负数" to -1L, "零" to 0L))
        cloud.emptyText = "还没有字词记录"
        assertTrue(cloud.layoutSnapshot().isEmpty())
        assertTrue(cloud.contentDescription.toString().contains("还没有字词记录"))
    }

    @Test fun cloudWeightsAndInkSurviveDarkModeLargeTextAndNarrowRtlLayouts() {
        for ((name, dark, scale) in listOf(Triple("light", false, 1f), Triple("dark", true, 1f), Triple("large-rtl", false, 1.6f))) {
            host(dark, scale) { activity, context ->
                val cloud = WordCloudView(context).apply {
                    layoutDirection = if (scale > 1) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    setWords(linkedMapOf("输入" to 200L, "你好" to 80L, "生活" to 1L, "学习" to 30L,
                        "世界" to 22L, "中文" to 12L, "明天" to 9L, "键盘" to 6L, "𠮷" to 3L))
                }
                attach(activity, cloud, if (scale > 1) 240 else 320, 280)
                assertCloudGeometry(cloud)
                val words = cloud.layoutSnapshot().associateBy { it.word }
                assertTrue(words.getValue("输入").bold)
                assertTrue(words.getValue("输入").textSize > words.getValue("生活").textSize)
                val pixels = render(cloud)
                try {
                    val background = context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer)
                    val ink = IntArray(pixels.width * pixels.height)
                    pixels.getPixels(ink, 0, pixels.width, 0, 0, pixels.width, pixels.height)
                    assertTrue("The actual view must draw text", ink.count { it != background } > 100)
                    save(pixels, "word-cloud-$name")
                } finally { pixels.recycle() }
                attach(activity, cloud, 200, 220)
                assertCloudGeometry(cloud)
            }
        }
    }

    @Test fun distributionHandlesLargeCountsAndDrawsFromTheReadingDirection() = host { activity, context ->
        val chart = StatsChartView(context)
        chart.setDistribution(linkedMapOf("第一项" to Long.MAX_VALUE, "第二项" to Long.MAX_VALUE, "零" to 0L))
        attach(activity, chart)
        val bars = chart.barLayoutSnapshot()
        assertEquals(listOf("第一项", "第二项", "零"), bars.map { it.label })
        assertEquals(0.5f, bars[0].fraction, 0.0001f)
        assertEquals(0.5f, bars[1].fraction, 0.0001f)
        assertEquals(0f, bars[2].fraction, 0f)
        chart.setDistribution(linkedMapOf("单字" to 1L, "双字词" to 3L))
        attach(activity, chart)
        val y = (chart.barLayoutSnapshot().first().bounds.bottom - 10).toInt()
        val primary = context.settingsColor(androidx.appcompat.R.attr.colorPrimary)
        val ltr = render(chart)
        try { assertEquals(primary, ltr.getPixel(20, y)); assertNotEquals(primary, ltr.getPixel(300, y)); save(ltr, "distribution-ltr") }
        finally { ltr.recycle() }
        chart.layoutDirection = View.LAYOUT_DIRECTION_RTL
        attach(activity, chart)
        val rtl = render(chart)
        try { assertEquals(primary, rtl.getPixel(300, y)); assertNotEquals(primary, rtl.getPixel(20, y)); save(rtl, "distribution-rtl") }
        finally { rtl.recycle() }
    }

    @Test fun heatmapUsesCapturedKeyPositionsWidthsAndCanonicalCounts() = host { activity, context ->
        val rows = listOf(
            listOf(StatsChartView.HeatKey("a", "A", 0f, 1f, 60f), StatsChartView.HeatKey("space", "空格", 1.5f, 2f, 60f),
                StatsChartView.HeatKey("b", "B", 3.5f, 0.5f, 60f)),
            listOf(StatsChartView.HeatKey("enter", "回车", 1f, 3f, 90f)),
        )
        val chart = StatsChartView(context).apply { setKeyboardHeatmap(mapOf("space" to 30L, "a" to 10L), rows) }
        attach(activity, chart)
        val cells = chart.heatLayoutSnapshot()
        assertEquals(4, cells.size)
        assertTrue(chart.barLayoutSnapshot().isEmpty())
        assertEquals(listOf(10L, 30L, 0L, 0L), cells.map { it.count })
        assertTrue(cells[1].bounds.left > cells[0].bounds.right)
        assertEquals(2f, (cells[1].bounds.width() + 1) / (cells[0].bounds.width() + 1), 0.01f)
        assertEquals(1.5f, cells[3].bounds.height() / cells[0].bounds.height(), 0.01f)
        assertTrue(chart.contentDescription.toString().contains("空格: 30"))
        for (cell in cells) assertTrue(RectF(0f, 0f, chart.width.toFloat(), chart.height.toFloat()).contains(cell.bounds))
        chart.layoutDirection = View.LAYOUT_DIRECTION_RTL
        attach(activity, chart)
        assertEquals("Captured physical geometry must not be mirrored by the settings language", cells, chart.heatLayoutSnapshot())
        render(chart).also { try { save(it, "keyboard-heat") } finally { it.recycle() } }

        chart.setKeyboardHeatmap(mapOf("a" to 10L, "space" to 30L))
        attach(activity, chart)
        assertTrue(chart.heatLayoutSnapshot().isEmpty())
        assertEquals(listOf("space", "a"), chart.barLayoutSnapshot().map { it.label })
    }

    private fun qwertyRows(split: Boolean = false): List<List<StatsChartView.HeatKey>> {
        fun key(id: String, label: String, x: Float, width: Float) =
            StatsChartView.HeatKey(id, label, x + if (split && x >= 5f) 1.25f else 0f, width)
        return listOf(
            "qwertyuiop".mapIndexed { index, c -> key(c.toString(), c.uppercase(), index.toFloat(), 1f) },
            "asdfghjkl".mapIndexed { index, c -> key(c.toString(), c.uppercase(), index + .5f, 1f) },
            listOf(key("shift", "⇧", 0f, 1.5f)) +
                "zxcvbnm".mapIndexed { index, c -> key(c.toString(), c.uppercase(), index + 1.5f, 1f) } +
                key("backspace", "⌫", 8.5f, 1.5f),
            listOf(key("space", "空格", 0f, 5f), key("enter", "回车", 5f, 5f)),
        )
    }

    private fun attachHeatmap(activity: Activity, chart: StatsChartView, width: Int = 320): HorizontalScrollView {
        val viewport = HorizontalScrollView(chart.context).apply {
            isFillViewport = true
            addView(chart, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        attach(activity, viewport, width)
        assertTrue("The scrolling canvas must honor the readable minimum", chart.width >= chart.preferredMinimumWidth)
        return viewport
    }

    private fun assertHeatText(chart: StatsChartView, counts: Map<String, Long>, bitmap: Bitmap) {
        val cells = chart.heatLayoutSnapshot().associateBy { it.keyId }
        val text = chart.heatTextSnapshot()
        assertEquals(cells.size, text.size)
        val total = counts.values.sumOf { it.toDouble() }
        val percent = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
        val minimumTextSize = 10 * chart.resources.displayMetrics.density
        val maxCount = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        text.forEach { item ->
            val cell = cells.getValue(item.keyId)
            val fraction = if (total > 0) (counts[item.keyId] ?: 0) / total else 0.0
            val expected = if (fraction > 0 && fraction < .001) "<${percent.format(.001)}" else percent.format(fraction)
            assertEquals("Percentage precision must be retained for ${cell.keyId}", expected, item.percentage.text)
            assertFalse(item.percentage.text.contains('…'))
            assertFalse(item.percentage.text.contains("..."))
            assertTrue("${cell.keyId}: ${item.percentage.textSize}", item.percentage.textSize >= minimumTextSize - .01f)
            for (line in listOf(item.label, item.percentage)) {
                assertTrue("${cell.keyId}: ${line.text} ink ${line.inkBounds} outside ${cell.bounds}",
                    cell.bounds.contains(line.inkBounds))
            }
            assertTrue("The key label and percentage must not overlap", item.label.inkBounds.bottom <= item.percentage.inkBounds.top)
            val intensity = cell.count.toDouble() / maxCount
            val surface = chart.context.settingsColor(when {
                intensity >= .6 -> androidx.appcompat.R.attr.colorPrimary
                intensity > .15 -> com.google.android.material.R.attr.colorPrimaryContainer
                intensity > 0 -> com.google.android.material.R.attr.colorSecondaryContainer
                else -> com.google.android.material.R.attr.colorSurfaceContainerHigh
            })
            val ink = item.percentage.inkBounds
            var painted = 0
            for (y in ink.top.toInt() until ceil(ink.bottom).toInt()) {
                for (x in ink.left.toInt() until ceil(ink.right).toInt()) {
                    if (bitmap.getPixel(x, y) != surface) painted++
                }
            }
            assertTrue("${cell.keyId}: the complete percentage must actually be painted", painted > 0)
        }
    }

    @Test fun thirtyKeyHeatmapKeepsCompletePercentagesAt320dpAndLargeFontScale() {
        for ((name, dark, scale) in listOf(Triple("light", false, 1f), Triple("dark", true, 1f), Triple("large", false, 1.6f))) {
            host(dark, scale) { activity, context ->
                val rows = qwertyRows()
                val keys = rows.flatten()
                assertEquals(30, keys.size)
                val counts = keys.mapIndexed { index, key -> key.keyId to when (index) {
                    0 -> 99L
                    1 -> 495L
                    else -> index - 1L
                } }.toMap()
                assertEquals(1000L, counts.values.sum())
                val chart = StatsChartView(context).apply { setKeyboardHeatmap(counts, rows) }
                val minimumBeforeMeasuring = chart.preferredMinimumWidth
                val viewport = attachHeatmap(activity, chart)
                assertEquals(320, viewport.width)
                assertEquals(minimumBeforeMeasuring, chart.preferredMinimumWidth)
                assertEquals(30, chart.heatLayoutSnapshot().size)
                assertEquals("The full keyboard must fit regardless of settings font scale", viewport.width, chart.width)
                render(chart).also {
                    try { assertHeatText(chart, counts, it); save(it, "keyboard-qwerty-$name") }
                    finally { it.recycle() }
                }
                render(viewport).also { try { save(it, "keyboard-qwerty-$name-320dp-viewport") } finally { it.recycle() } }
                val geometry = chart.heatLayoutSnapshot()
                val text = chart.heatTextSnapshot()
                chart.layoutDirection = View.LAYOUT_DIRECTION_RTL
                attach(activity, viewport)
                assertEquals("Settings direction must retain the captured physical keyboard", geometry, chart.heatLayoutSnapshot())
                assertEquals(text, chart.heatTextSnapshot())
            }
        }
    }

    @Test fun heatmapFitsZeroFullAndHugePercentagesWithoutOverflowOrEllipsis() = host(fontScale = 1.6f) { activity, context ->
        val rows = qwertyRows()
        val chart = StatsChartView(context).apply { setKeyboardHeatmap(emptyMap(), rows) }
        val viewport = attachHeatmap(activity, chart)
        for ((name, counts) in listOf("zero" to emptyMap(), "full" to mapOf("q" to Long.MAX_VALUE),
            "huge" to mapOf("q" to Long.MAX_VALUE, "w" to Long.MAX_VALUE))) {
            chart.setKeyboardHeatmap(counts, rows)
            attach(activity, viewport)
            render(chart).also {
                try { assertHeatText(chart, counts, it); save(it, "keyboard-qwerty-$name-large") }
                finally { it.recycle() }
            }
        }
        chart.setKeyboardHeatmap(mapOf("q" to Long.MAX_VALUE), rows)
        attach(activity, viewport)
        assertEquals("100%", chart.heatTextSnapshot().first().percentage.text)
    }

    @Test fun splitHeatmapKeepsItsPhysicalGapAndReadablePercentagesInRtl() = host(fontScale = 1.6f) { activity, context ->
        val rows = qwertyRows(split = true)
        val counts = rows.flatten().associate { it.keyId to 1L }
        val chart = StatsChartView(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setKeyboardHeatmap(counts, rows)
        }
        attachHeatmap(activity, chart)
        val cells = chart.heatLayoutSnapshot().associateBy { it.keyId }
        val left = cells.getValue("t").bounds
        val right = cells.getValue("y").bounds
        val unit = cells.getValue("q").bounds.width() + 1
        assertEquals("The captured center gap must survive scaling", 1.25f, (right.left - left.right - 1) / unit, .01f)
        render(chart).also {
            try { assertHeatText(chart, counts, it); save(it, "keyboard-qwerty-split-large-rtl") }
            finally { it.recycle() }
        }
    }

    @Test fun unusuallyNarrowCapturedKeysRequestMoreCanvasInsteadOfLosingThePercentage() = host(fontScale = 1.6f) { activity, context ->
        val rows = qwertyRows().mapIndexed { index, row ->
            if (index == 0) row.map { if (it.keyId == "q") it.copy(width = .4f) else it } else row
        }
        val counts = mapOf("q" to Long.MAX_VALUE)
        val chart = StatsChartView(context).apply { setKeyboardHeatmap(counts, rows) }
        val minimum = chart.preferredMinimumWidth
        val viewport = attachHeatmap(activity, chart)
        assertTrue(minimum > viewport.width)
        val cells = chart.heatLayoutSnapshot().associateBy { it.keyId }
        assertEquals(.4f, (cells.getValue("q").bounds.width() + 1) / (cells.getValue("w").bounds.width() + 1), .001f)
        render(chart).also { try { assertHeatText(chart, counts, it) } finally { it.recycle() } }
    }

    @Test
    @Config(qualifiers = "ar-rEG-w320dp-h800dp-mdpi")
    fun localizedRtlPercentagesRetainEveryFormattedCharacter() = host(fontScale = 1.6f) { activity, context ->
        val rows = qwertyRows()
        val counts = mapOf("q" to 99L, "w" to 901L, "e" to 1L)
        val chart = StatsChartView(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setKeyboardHeatmap(counts, rows)
        }
        attachHeatmap(activity, chart)
        render(chart).also {
            try { assertHeatText(chart, counts, it); save(it, "keyboard-qwerty-arabic-large-rtl") }
            finally { it.recycle() }
        }
    }

    @Test fun ordinaryPageWidthsFitTheKeyboardAndIgnoreSettingsFontScale() {
        for (width in listOf(256, 296)) {
            var geometry: List<StatsChartView.HeatCell>? = null
            var typography: List<StatsChartView.HeatTextLayout>? = null
            var chartHeight = 0
            for (scale in listOf(.85f, 1f, 1.6f, 2f)) host(fontScale = scale) { activity, context ->
                val rows = qwertyRows()
                // 256/296dp reproduce the inner widths of 320/360dp settings pages.
                val counts = rows.flatten().associate { it.keyId to 1L }
                val chart = StatsChartView(context).apply { setKeyboardHeatmap(counts, rows) }
                assertTrue(chart.preferredMinimumWidth <= width)
                val hint = TextView(context).apply { text = "左右滑动查看完整键位" }
                val content = StatsHeatmapContent(context, chart, hint)
                attach(activity, content, width)
                assertEquals(width, chart.width)
                assertEquals(View.GONE, hint.visibility)
                assertEquals(chart.width, (chart.parent as HorizontalScrollView).width)
                if (geometry == null) {
                    geometry = chart.heatLayoutSnapshot()
                    typography = chart.heatTextSnapshot()
                    chartHeight = chart.height
                } else {
                    assertEquals("Changing font scale must preserve heatmap geometry", geometry, chart.heatLayoutSnapshot())
                    assertEquals("Changing font scale must preserve heatmap text", typography, chart.heatTextSnapshot())
                    assertEquals(chartHeight, chart.height)
                }
                val cells = chart.heatLayoutSnapshot()
                assertEquals("Adjacent key gutters stay compact", 1f, cells[1].bounds.left - cells[0].bounds.right, .01f)
                render(chart).also {
                    try { assertHeatText(chart, counts, it); save(it, "keyboard-qwerty-${width}dp-scale-$scale-no-scroll") }
                    finally { it.recycle() }
                }
            }
        }
    }

    @Test fun heatmapAndFrequencyFallbackDistinguishTinyPositiveCountsFromZero() = host(fontScale = 1.6f) { activity, context ->
        val rows = qwertyRows()
        val counts = mapOf("q" to 1L, "w" to 9988L, "e" to 0L, "r" to 10L, "t" to 1L)
        val chart = StatsChartView(context).apply { setKeyboardHeatmap(counts, rows) }
        val viewport = attachHeatmap(activity, chart)
        val percentages = chart.heatTextSnapshot().associate { it.keyId to it.percentage.text }
        assertEquals("<0.1%", percentages.getValue("q"))
        assertEquals("0%", percentages.getValue("e"))
        assertEquals("0.1%", percentages.getValue("r"))
        assertEquals("99.9%", percentages.getValue("w"))
        render(chart).also { try { assertHeatText(chart, counts, it); save(it, "keyboard-qwerty-tiny-percentages-large") } finally { it.recycle() } }

        assertTrue(chart.showKeyboardFrequencies())
        attach(activity, viewport)
        val bars = chart.barLayoutSnapshot()
        val details = bars.zip(chart.barDetailSnapshot()).associate { (bar, detail) -> bar.label to detail }
        assertEquals("1 · <0.1%", details.getValue("q").text)
        assertEquals("0 · 0%", details.getValue("e").text)
        assertEquals("10 · 0.1%", details.getValue("r").text)
        assertEquals("9,988 · 99.9%", details.getValue("w").text)
        bars.forEach { bar ->
            val detail = details.getValue(bar.label)
            assertTrue(detail.textSize >= 10 * context.resources.displayMetrics.scaledDensity - .01f)
            assertTrue(bar.bounds.contains(detail.inkBounds))
        }

        chart.setKeyboardHeatmap(mapOf("q" to 1L, "w" to Long.MAX_VALUE, "e" to 0L), rows)
        attach(activity, viewport)
        val huge = chart.heatTextSnapshot().associate { it.keyId to it.percentage.text }
        assertEquals("<0.1%", huge.getValue("q"))
        assertEquals("0%", huge.getValue("e"))
    }

    @Test fun screenHeatmapShowsItsScrollHintInTheFirstMeasurePass() = host { activity, context ->
        val chart = StatsChartView(context).apply { setKeyboardHeatmap(mapOf("q" to 99L, "w" to 901L), qwertyRows()) }
        val hint = TextView(context).apply { text = "左右滑动查看完整键位" }
        val content = StatsHeatmapContent(context, chart, hint)
        attach(activity, content, width = 200)
        assertTrue(chart.width > content.width)
        assertEquals(View.VISIBLE, hint.visibility)
        assertTrue(hint.measuredHeight > 0)
        assertTrue("The first layout must reserve the hint's height", hint.bottom <= content.height)
        render(content).also { try { save(it, "keyboard-qwerty-light-with-scroll-hint") } finally { it.recycle() } }
        attach(activity, content, width = 320)
        assertEquals(chart.width, content.width)
        assertEquals(View.GONE, hint.visibility)
    }

    @Test fun screenHeatmapUpdatesTheHintWhenOnlyItsChildWidthChanges() = host(fontScale = 1.6f) { activity, context ->
        val rows = qwertyRows()
        val chart = StatsChartView(context).apply { setKeyboardHeatmap(emptyMap(), rows) }
        val hint = TextView(context).apply { text = "左右滑动查看完整键位" }
        val content = StatsHeatmapContent(context, chart, hint)
        attach(activity, content, width = 200)
        assertEquals(View.GONE, hint.visibility)
        chart.setKeyboardHeatmap(mapOf("q" to 99L, "w" to 901L), rows)
        attach(activity, content, width = 200)
        assertTrue(chart.width > content.width)
        assertEquals(View.VISIBLE, hint.visibility)
        assertTrue(hint.bottom <= content.height)
        chart.setKeyboardHeatmap(emptyMap(), rows)
        attach(activity, content, width = 200)
        assertEquals(View.GONE, hint.visibility)
    }

    private fun shareHeatmapPage(context: Context, chart: StatsChartView): Triple<LinearLayout, StatsHeatmapContent, TextView> {
        val inset = (16 * context.resources.displayMetrics.density).toInt()
        val margin = (7 * context.resources.displayMetrics.density).toInt()
        val hint = TextView(context).apply { text = "键盘布局过宽，以下按频次列出全部按键记录" }
        val heatmap = StatsHeatmapContent(context, chart, hint, sharedImage = true)
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset, inset, inset)
            addView(heatmap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset, inset, inset)
            addView(section, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = margin; rightMargin = margin
            })
        }
        return Triple(page, heatmap, hint)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
    fun sharedHeatmapUsesExactAvailableWidthBelowThe320dpDefaultAtThreeTimesDensity() = host { _, context ->
        assertEquals(3f, context.resources.displayMetrics.density, 0f)
        val rows = qwertyRows()
        val counts = rows.flatten().associate { it.keyId to 0L }
        val chart = StatsChartView(context).apply { setKeyboardHeatmap(counts, rows) }
        val (page, heatmap, hint) = shareHeatmapPage(context, chart)
        val width = measureStatsSharePage(page, StatsImageRenderer.MAX_WIDTH)
        page.layout(0, 0, width, page.measuredHeight)
        assertFalse(page.isAttachedToWindow)
        assertEquals(1080, width)
        assertFalse(heatmap.usesFrequencyFallback)
        assertEquals(View.GONE, hint.visibility)
        assertFalse(chart.parent is HorizontalScrollView)
        assertEquals(heatmap.width, chart.width)
        assertTrue("This reproduces the former 320dp child overflow", chart.width < 320 * context.resources.displayMetrics.density)
        assertTrue(chart.width >= chart.preferredMinimumWidth)
        assertEquals(30, chart.heatLayoutSnapshot().size)
        render(chart).also { try { assertHeatText(chart, counts, it) } finally { it.recycle() } }
        render(page).also { try { save(it, "keyboard-share-1080px-3x-complete") } finally { it.recycle() } }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
    fun sharedMissingLayoutRetainsAll128FrequenciesInsideTheImageAtThreeTimesDensity() = host { _, context ->
        val counts = (0 until 128).associate { "key$it" to it + 1L }
        val chart = StatsChartView(context).apply { setKeyboardHeatmap(counts) }
        val (page, heatmap, hint) = shareHeatmapPage(context, chart)
        val width = measureStatsSharePage(page, StatsImageRenderer.MAX_WIDTH)
        page.layout(0, 0, width, page.measuredHeight)
        assertEquals(1080, width)
        assertEquals(heatmap.width, chart.width)
        assertTrue(chart.width < 320 * context.resources.displayMetrics.density)
        assertFalse(chart.parent is HorizontalScrollView)
        assertFalse(heatmap.usesFrequencyFallback)
        assertEquals(View.GONE, hint.visibility)
        val bars = chart.barLayoutSnapshot()
        assertEquals(128, bars.size)
        assertEquals(counts.keys, bars.map { it.label }.toSet())
        bars.forEach { assertTrue(RectF(0f, 0f, chart.width.toFloat(), chart.height.toFloat()).contains(it.bounds)) }
        assertTrue(page.height <= StatsImageRenderer.MAX_HEIGHT)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
    fun sharedFrequencyFallbackKeepsHugeCountsAndPercentagesReadableAtLargeFontScale() = host(fontScale = 1.6f) { _, context ->
        val rows = qwertyRows().mapIndexed { index, row ->
            if (index == 0) row.map { if (it.keyId == "q") it.copy(width = .1f) else it } else row
        }
        val counts = mapOf("q" to Long.MAX_VALUE, "w" to Long.MAX_VALUE, "space" to 0L)
        val chart = StatsChartView(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setKeyboardHeatmap(counts, rows)
        }
        val (page, heatmap, _) = shareHeatmapPage(context, chart)
        val width = measureStatsSharePage(page, StatsImageRenderer.MAX_WIDTH)
        page.layout(0, 0, width, page.measuredHeight)
        assertTrue(heatmap.usesFrequencyFallback)
        val bars = chart.barLayoutSnapshot()
        val details = chart.barDetailSnapshot()
        val number = NumberFormat.getIntegerInstance()
        val percent = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
        assertEquals(3, details.size)
        details.forEachIndexed { index, detail ->
            val bar = bars[index]
            assertEquals("${number.format(bar.count)} · ${percent.format(bar.fraction)}", detail.text)
            assertFalse(detail.text.contains('…'))
            assertTrue(detail.textSize >= 10 * context.resources.displayMetrics.scaledDensity - .01f)
            assertTrue("${detail.text} outside ${bar.bounds}", bar.bounds.contains(detail.inkBounds))
        }
        assertTrue("The full large value must fit independently of the key label", details.first().textSize < 13 * context.resources.displayMetrics.scaledDensity)
        render(page).also { try { save(it, "keyboard-share-frequencies-huge-large-3x") } finally { it.recycle() } }
    }

    @Test fun sharedHeatmapAccountsForAllAncestorInsetsBeforeChoosingItsWidth() = host(fontScale = 1.6f) { _, context ->
        val rows = qwertyRows()
        val counts = mapOf("q" to 99L, "w" to 901L)
        val chart = StatsChartView(context).apply { setKeyboardHeatmap(counts, rows) }
        val minimum = chart.preferredMinimumWidth
        val (page, heatmap, hint) = shareHeatmapPage(context, chart)
        val width = measureStatsSharePage(page, 320)
        page.layout(0, 0, width, page.measuredHeight)
        assertEquals(minimum + 16 * 4 + 7 * 2, width)
        assertEquals(minimum, chart.width)
        assertFalse(heatmap.usesFrequencyFallback)
        assertEquals(View.GONE, hint.visibility)
        assertFalse(chart.parent is HorizontalScrollView)
        render(chart).also { try { assertHeatText(chart, counts, it) } finally { it.recycle() } }
    }

    @Test fun sharedHeatmapFallsBackToEveryRecordedKeyWhenItsGeometryExceedsTheImageWidth() = host { _, context ->
        val rows = qwertyRows().mapIndexed { index, row ->
            if (index == 0) row.map { if (it.keyId == "q") it.copy(width = .1f) else it } else row
        }
        val counts = linkedMapOf("q" to 999L).apply { (1 until 128).forEach { put("key$it", it.toLong()) } }
        val chart = StatsChartView(context).apply { setKeyboardHeatmap(counts, rows) }
        assertTrue(chart.preferredMinimumWidth > StatsImageRenderer.MAX_WIDTH)
        val (page, heatmap, hint) = shareHeatmapPage(context, chart)
        val width = measureStatsSharePage(page, 320)
        page.layout(0, 0, width, page.measuredHeight)
        assertEquals(StatsImageRenderer.MAX_WIDTH, width)
        assertTrue(heatmap.usesFrequencyFallback)
        assertEquals(View.VISIBLE, hint.visibility)
        assertFalse(chart.parent is HorizontalScrollView)
        assertTrue(chart.heatLayoutSnapshot().isEmpty())
        val bars = chart.barLayoutSnapshot()
        assertEquals(128, bars.size)
        assertEquals(counts.keys, bars.map { it.label }.toSet())
        assertEquals(counts.values.sum(), bars.sumOf { it.count })
        assertTrue("The final frequency row must fit in the exported image", bars.last().bounds.bottom <= chart.height)
        assertTrue(hint.bottom <= heatmap.height)
        assertTrue(page.height <= StatsImageRenderer.MAX_HEIGHT)
    }

    @Test fun narrowLargeTextDistributionMovesLongValuesOntoTheirOwnLine() = host(fontScale = 1.6f) { activity, context ->
        val chart = StatsChartView(context).apply {
            setDistribution(linkedMapOf("完整分布名称" to Long.MAX_VALUE, "另一项" to 1L))
        }
        attach(activity, chart, width = 240)
        val first = chart.barLayoutSnapshot().first()
        assertTrue(first.stacked)
        assertTrue(first.bounds.height() >= 70)
        assertTrue(chart.contentDescription.toString().contains("完整分布名称"))
        render(chart).also { try { save(it, "distribution-large") } finally { it.recycle() } }
    }

    private fun render(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(view.context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
        view.draw(canvas)
    }

    private fun save(bitmap: Bitmap, name: String) {
        val root = System.getenv("INPUT_STATS_PREVIEW_DIR") ?: return
        File(root, "$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
