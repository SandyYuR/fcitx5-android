/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.fcitx.fcitx5.android.utils.settingsColor
import java.text.NumberFormat
import kotlin.math.ceil

/** Distribution bars and keyboard heat use the same counts, with geometry supplied by the actual keyboard. */
class StatsChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    data class HeatKey(val keyId: String, val label: String, val x: Float, val width: Float, val height: Float = 48f)
    internal data class HeatCell(val keyId: String, val label: String, val count: Long, val bounds: RectF)
    internal data class HeatText(val text: String, val textSize: Float, val x: Float, val baseline: Float,
        val bold: Boolean, val inkBounds: RectF)
    internal data class HeatTextLayout(val keyId: String, val label: HeatText, val percentage: HeatText)
    internal data class Bar(val label: String, val count: Long, val fraction: Float, val bounds: RectF, val stacked: Boolean)
    private data class Entry(val label: String, val count: Long)

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var entries = emptyList<Entry>()
    private var heatRows = emptyList<List<HeatKey>>()
    private var counts = emptyMap<String, Long>()
    private var heatCells = emptyList<HeatCell>()
    private var heatText = emptyList<HeatTextLayout>()
    private var bars = emptyList<Bar>()
    private var barDetails = emptyList<HeatText>()
    private var keyboardMode = false
    var emptyText: CharSequence = ""
        set(value) { field = value; updateAccessibility(); invalidate() }
    var accessibilityTitle: CharSequence = ""
        set(value) { field = value; updateAccessibility() }

    /** A horizontal scrolling parent can preserve readable percentages on narrow or split layouts. */
    val preferredMinimumWidth: Int
        get() {
            val percent = percentFormatter()
            val total = counts.values.sumOf { it.toDouble() }
            paint.typeface = Typeface.DEFAULT
            if (heatRows.isEmpty()) {
                if (!keyboardMode || entries.isEmpty()) return 0
                paint.textSize = minimumFrequencyTextSize()
                val number = NumberFormat.getIntegerInstance()
                val required = entries.maxOf { entry ->
                    textWidth("${number.format(entry.count)} · ${keyboardPercentage(entry.count, total, percent)}")
                }
                return ceil(required + paddingLeft + paddingRight + dp(4)).toInt()
            }
            paint.textSize = minimumHeatTextSize()
            val extent = heatRows.flatten().maxOf { it.x + it.width }
            val required = heatRows.flatten().maxOf { key ->
                val text = keyboardPercentage(counts[key.keyId] ?: 0, total, percent)
                // Match the compact key gutters and text insets used by the actual drawing.
                (textWidth(text) + 2 * (heatKeyInset() + heatTextInset())) * extent / key.width
            }
            return ceil(required + paddingLeft + paddingRight).toInt()
        }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; isFocusable = true }

    /** Caller order is retained, for example single-character / two-character / longer words. */
    fun setDistribution(values: Map<String, Long>) {
        keyboardMode = false
        counts = validCounts(values)
        entries = counts.entries.take(64).map { Entry(it.key, it.value) }
        heatRows = emptyList()
        changed()
    }

    /** Without a captured layout, show labelled frequency bars instead of inventing a keyboard shape. */
    fun setKeyboardHeatmap(values: Map<String, Long>, rows: List<List<HeatKey>> = emptyList()) {
        keyboardMode = true
        counts = validCounts(values)
        entries = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .map { Entry(it.key, it.value) }
        heatRows = rows.take(16).map { row ->
            row.take(48).filter { it.keyId.isNotBlank() && it.label.length <= 128 &&
                it.x.isFinite() && it.x >= 0 && it.width.isFinite() && it.width > 0 &&
                (it.x + it.width).isFinite() && it.height.isFinite() && it.height > 0 }
                .sortedBy { it.x }
        }.filter(List<HeatKey>::isNotEmpty)
        changed()
    }

    internal fun showKeyboardFrequencies(): Boolean {
        if (!keyboardMode || heatRows.isEmpty()) return false
        heatRows = emptyList()
        changed()
        return true
    }

    internal fun heatLayoutSnapshot() = heatCells.map { it.copy(bounds = RectF(it.bounds)) }
    internal fun heatTextSnapshot() = heatText.map { it.copy(
        label = it.label.copy(inkBounds = RectF(it.label.inkBounds)),
        percentage = it.percentage.copy(inkBounds = RectF(it.percentage.inkBounds))) }
    internal fun barLayoutSnapshot() = bars.map { it.copy(bounds = RectF(it.bounds)) }
    internal fun barDetailSnapshot() = barDetails.map { it.copy(inkBounds = RectF(it.inkBounds)) }

    private fun validCounts(values: Map<String, Long>) = values.filter { (label, count) ->
        label.isNotBlank() && label.length <= 128 && label.none(Char::isISOControl) && count >= 0
    }

    private fun changed() { updateAccessibility(); arrange(); requestLayout(); invalidate() }

    private fun updateAccessibility() {
        val number = NumberFormat.getIntegerInstance()
        val readable = if (heatRows.isNotEmpty()) heatRows.flatten().distinctBy { it.keyId }
            .map { Entry(it.label, counts[it.keyId] ?: 0) } else entries
        val details = if (readable.isEmpty()) emptyText.toString()
            else readable.joinToString("\n") { "${it.label}: ${number.format(it.count)}" }
        contentDescription = listOf(accessibilityTitle.toString(), details).filter(String::isNotBlank).joinToString("\n")
    }

    private fun lineHeight() = maxOf(dp(52), 36 * resources.displayMetrics.scaledDensity)
    private fun rowHeight(row: List<HeatKey>) = dp((row.maxOf { it.height } * 0.7f).coerceIn(30f, 96f))
    private fun heatKeyInset() = dp(.5f)
    private fun heatTextInset() = dp(.5f)
    private fun chartInset() = if (keyboardMode && heatRows.isNotEmpty()) 0f else dp(2)
    private fun barHeight(entry: Entry, availableWidth: Float): Pair<Float, Boolean> {
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 13 * resources.displayMetrics.scaledDensity
        val numberWidth = paint.measureText(NumberFormat.getIntegerInstance().format(entry.count) + " · 100%")
        val stacked = numberWidth + minOf(paint.measureText(entry.label), dp(60)) + dp(12) > availableWidth
        return (if (stacked) maxOf(lineHeight(), paint.fontSpacing * 2 + dp(26)) else lineHeight()) to stacked
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val naturalWidth = if (keyboardMode) preferredMinimumWidth else dp(320).toInt()
        val wantedWidth = resolveSize(naturalWidth, widthMeasureSpec)
        val availableWidth = (wantedWidth - paddingLeft - paddingRight - 2 * chartInset()).coerceAtLeast(0f)
        val body = if (heatRows.isNotEmpty()) heatRows.sumOf { rowHeight(it).toDouble() }.toFloat() + dp(4) * (heatRows.size - 1)
            else if (entries.isEmpty()) lineHeight() else entries.sumOf { barHeight(it, availableWidth).first.toDouble() }.toFloat()
        val wanted = (body + paddingTop + paddingBottom + dp(16)).toInt()
        setMeasuredDimension(wantedWidth, resolveSize(wanted, heightMeasureSpec))
        arrange()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { arrange() }
    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        // This callback also runs while View resolves relative padding. Reading padding
        // here would re-enter that resolution; the next measurement rebuilds geometry.
        requestLayout(); invalidate()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        changed()
    }

    private fun arrange() {
        heatCells = emptyList(); heatText = emptyList(); bars = emptyList(); barDetails = emptyList()
        val availableWidth = (measuredWidth - paddingLeft - paddingRight - 2 * chartInset()).coerceAtLeast(0f)
        if (availableWidth <= 0) return
        var top = paddingTop + dp(8)
        if (keyboardMode && heatRows.isNotEmpty()) {
            val extent = heatRows.flatten().maxOf { it.x + it.width }
            val result = ArrayList<HeatCell>()
            val requestedHeight = heatRows.sumOf { rowHeight(it).toDouble() }.toFloat() + dp(4) * (heatRows.size - 1)
            val availableHeight = (measuredHeight - paddingTop - paddingBottom - dp(16)).coerceAtLeast(0f)
            val scale = minOf(1f, availableHeight / requestedHeight)
            heatRows.forEach { row ->
                val cellHeight = rowHeight(row) * scale
                row.forEach { key ->
                    val left = paddingLeft + availableWidth * key.x / extent
                    val right = paddingLeft + availableWidth * (key.x + key.width) / extent
                    val box = RectF(left + heatKeyInset(), top, right - heatKeyInset(), top + cellHeight)
                    if (box.width() > 0 && box.height() > 0) result += HeatCell(key.keyId, key.label, counts[key.keyId] ?: 0, box)
                }
                top += cellHeight + dp(4) * scale
            }
            heatCells = result
            val maxCount = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
            val total = counts.values.sumOf { it.toDouble() }
            val percent = percentFormatter()
            heatText = heatCells.map { cell ->
                layoutHeatText(cell, keyboardPercentage(cell.count, total, percent),
                    cell.count.toDouble() / maxCount >= 0.6)
            }
        } else {
            val total = entries.sumOf { it.count.toDouble() }
            bars = entries.map { entry ->
                val (rowHeight, stacked) = barHeight(entry, availableWidth)
                val box = RectF(paddingLeft + dp(2), top, measuredWidth - paddingRight - dp(2), top + rowHeight)
                top = box.bottom
                Bar(entry.label, entry.count, if (total > 0) (entry.count / total).toFloat() else 0f, box, stacked)
            }
            barDetails = bars.map { layoutBarDetail(it, total) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (heatCells.isNotEmpty()) drawHeat(canvas)
        else if (bars.isNotEmpty()) drawBars(canvas)
        else drawEmpty(canvas)
    }

    private fun drawBars(canvas: Canvas) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val palette = intArrayOf(context.settingsColor(androidx.appcompat.R.attr.colorPrimary),
            context.settingsColor(com.google.android.material.R.attr.colorSecondary), context.settingsColor(com.google.android.material.R.attr.colorTertiary))
        bars.forEachIndexed { index, bar ->
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 13 * resources.displayMetrics.scaledDensity
            paint.color = context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            val detail = barDetails[index]
            paint.textSize = detail.textSize
            val detailWidth = paint.measureText(detail.text)
            paint.textSize = 13 * resources.displayMetrics.scaledDensity
            val labelWidth = if (bar.stacked) bar.bounds.width() else bar.bounds.width() - detailWidth - dp(12)
            val label = TextUtils.ellipsize(bar.label, paint, labelWidth.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
            val baseline = bar.bounds.top - paint.fontMetrics.ascent
            canvas.drawText(label, if (rtl) bar.bounds.right - paint.measureText(label) else bar.bounds.left, baseline, paint)
            paint.textSize = detail.textSize
            canvas.drawText(detail.text, detail.x, detail.baseline, paint)
            val track = RectF(bar.bounds.left, bar.bounds.bottom - dp(14), bar.bounds.right, bar.bounds.bottom - dp(6))
            paint.color = context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
            canvas.drawRoundRect(track, dp(4), dp(4), paint)
            val fill = RectF(track)
            if (rtl) fill.left = fill.right - fill.width() * bar.fraction else fill.right = fill.left + fill.width() * bar.fraction
            paint.color = palette[index % palette.size]
            canvas.drawRoundRect(fill, dp(4), dp(4), paint)
        }
    }

    private fun drawHeat(canvas: Canvas) {
        val maxCount = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        heatCells.forEachIndexed { index, cell ->
            val intensity = cell.count.toDouble() / maxCount
            val (surface, foreground) = when {
                intensity >= 0.6 -> androidx.appcompat.R.attr.colorPrimary to com.google.android.material.R.attr.colorOnPrimary
                intensity > 0.15 -> com.google.android.material.R.attr.colorPrimaryContainer to com.google.android.material.R.attr.colorOnPrimaryContainer
                intensity > 0 -> com.google.android.material.R.attr.colorSecondaryContainer to com.google.android.material.R.attr.colorOnSecondaryContainer
                else -> com.google.android.material.R.attr.colorSurfaceContainerHigh to com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            paint.color = context.settingsColor(surface)
            canvas.drawRoundRect(cell.bounds, dp(6), dp(6), paint)
            paint.color = context.settingsColor(foreground)
            val checkpoint = canvas.save()
            canvas.clipRect(cell.bounds)
            val text = heatText[index]
            listOf(text.label, text.percentage).forEach { line ->
                paint.typeface = if (line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                paint.textSize = line.textSize
                canvas.drawText(line.text, line.x, line.baseline, paint)
            }
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun percentFormatter() = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
    private fun keyboardPercentage(count: Long, total: Double, percent: NumberFormat = percentFormatter()): String {
        val fraction = if (total > 0) count / total else 0.0
        return if (count > 0 && fraction > 0 && fraction < 0.001) "<${percent.format(0.001)}" else percent.format(fraction)
    }
    // The keyboard miniature keeps its type and geometry independent of settings font scale.
    // Frequency bars and the rest of the statistics page retain normal scalable typography.
    private fun minimumHeatTextSize() = dp(10)
    private fun minimumFrequencyTextSize() = 10 * resources.displayMetrics.scaledDensity

    private fun layoutBarDetail(bar: Bar, total: Double): HeatText {
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 13 * resources.displayMetrics.scaledDensity
        val baseline = bar.bounds.top - paint.fontMetrics.ascent + if (bar.stacked) paint.fontSpacing else 0f
        val percentage = if (keyboardMode) keyboardPercentage(bar.count, total) else percentFormatter().format(bar.fraction)
        val raw = "${NumberFormat.getIntegerInstance().format(bar.count)} · $percentage"
        val detail = if (keyboardMode) {
            fitFullText(raw, bar.bounds.width(), minimumFrequencyTextSize())
            raw
        } else TextUtils.ellipsize(raw, paint, bar.bounds.width().coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
        val ink = Rect()
        paint.getTextBounds(detail, 0, detail.length, ink)
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) bar.bounds.left - minOf(0, ink.left)
            else bar.bounds.right - maxOf(paint.measureText(detail), ink.right.toFloat())
        return HeatText(detail, paint.textSize, x, baseline, false,
            RectF(x + ink.left, baseline + ink.top, x + ink.right, baseline + ink.bottom))
    }

    private fun fitFullText(text: String, width: Float, minimumSize: Float) {
        val preferred = paint.textSize
        paint.textSize = minOf(minimumSize, preferred)
        val floor = if (textWidth(text) <= width) paint.textSize else 0f
        paint.textSize = preferred
        repeat(3) {
            val measured = textWidth(text)
            if (measured > width && measured > 0) paint.textSize = maxOf(floor, paint.textSize * width / measured)
        }
    }

    /** Include overhanging glyphs, not just the cursor advance, when fitting text. */
    private fun textWidth(text: String): Float {
        val ink = Rect()
        paint.getTextBounds(text, 0, text.length, ink)
        return maxOf(paint.measureText(text), ink.right.toFloat()) - minOf(0f, ink.left.toFloat())
    }

    private fun layoutHeatText(cell: HeatCell, percentage: String, bold: Boolean): HeatTextLayout {
        val width = (cell.bounds.width() - 2 * heatTextInset()).coerceAtLeast(0f)
        val height = (cell.bounds.height() - dp(4)).coerceAtLeast(0f) / 2
        val size = dp(11)

        fun fit(text: String, isLabel: Boolean): Pair<String, Float> {
            paint.typeface = if (isLabel && bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textSize = size
            val metrics = paint.fontMetrics
            val heightScale = if (metrics.descent > metrics.ascent) height / (metrics.descent - metrics.ascent) else 1f
            paint.textSize = size * minOf(1f, heightScale)
            if (isLabel) {
                val preferred = paint.textSize
                val measured = textWidth(text)
                if (measured > width) paint.textSize = maxOf(minimumHeatTextSize(), preferred * width / measured)
                    .coerceAtMost(preferred)
                return TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString() to paint.textSize
            }
            // Percentages retain their precision and all characters. The scrolling parent's minimum
            // width normally keeps this at >= 10dp; exact external constraints still must not clip it.
            fitFullText(text, width, minimumHeatTextSize())
            return text to paint.textSize
        }

        fun line(text: String, textSize: Float, centerY: Float, isBold: Boolean): HeatText {
            paint.typeface = if (isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textSize = textSize
            val ink = Rect()
            paint.getTextBounds(text, 0, text.length, ink)
            val left = minOf(0f, ink.left.toFloat())
            val right = maxOf(paint.measureText(text), ink.right.toFloat())
            val x = cell.bounds.centerX() - (left + right) / 2
            val baseline = centerY - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
            return HeatText(text, textSize, x, baseline, isBold,
                RectF(x + ink.left, baseline + ink.top, x + ink.right, baseline + ink.bottom))
        }

        val (label, labelSize) = fit(cell.label, true)
        val (detail, detailSize) = fit(percentage, false)
        return HeatTextLayout(cell.keyId,
            line(label, labelSize, cell.bounds.centerY() - height / 2, bold),
            line(detail, detailSize, cell.bounds.centerY() + height / 2, false))
    }

    private fun drawEmpty(canvas: Canvas) {
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 14 * resources.displayMetrics.scaledDensity
        paint.color = context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val label = TextUtils.ellipsize(emptyText, paint, (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
        canvas.drawText(label, (width - paint.measureText(label)) / 2, (height - paint.ascent() - paint.descent()) / 2, paint)
    }

    private fun dp(value: Int) = value * resources.displayMetrics.density
    private fun dp(value: Float) = value * resources.displayMetrics.density
}

/** Uses the final measured viewport for screen hints, and the actual parent width for image exports. */
internal class StatsHeatmapContent(context: Context, val chart: StatsChartView, private val hint: View,
    private val sharedImage: Boolean = false) : LinearLayout(context) {
    private val viewport = if (sharedImage) null else HorizontalScrollView(context).apply {
        isFillViewport = true
        isHorizontalScrollBarEnabled = true
        addView(chart, ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    var usesFrequencyFallback = false
        private set

    init {
        orientation = VERTICAL
        hint.visibility = GONE
        addView(viewport ?: chart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * resources.displayMetrics.density).toInt()
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (sharedImage && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            if (availableWidth > 0 && chart.preferredMinimumWidth > availableWidth) {
                usesFrequencyFallback = chart.showKeyboardFrequencies() || usesFrequencyFallback
            }
            hint.visibility = if (usesFrequencyFallback) VISIBLE else GONE
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        viewport?.let {
            val visibility = if (chart.measuredWidth > it.measuredWidth - it.paddingLeft - it.paddingRight) VISIBLE else GONE
            if (hint.visibility != visibility) {
                hint.visibility = visibility
                // Include the hint in this layout pass, including detached preview/share render trees.
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }
}
