/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.utils.settingsColor
import java.text.NumberFormat
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** A bounded typographic cloud. Geometry is cached; drawing frames only paint the accepted words. */
class WordCloudView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    internal data class Placement(
        val word: String,
        val count: Long,
        val bounds: RectF,
        val baseline: Float,
        val textSize: Float,
        val bold: Boolean,
        val color: Int,
        val originX: Float,
    )

    private data class Entry(val word: String, val count: Long)
    private data class Glyph(val size: Float, val ink: Rect)
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var entries = emptyList<Entry>()
    private var placements = emptyList<Placement>()
    var emptyText: CharSequence = ""
        set(value) { field = value; updateAccessibility(); invalidate() }
    var accessibilityTitle: CharSequence = ""
        set(value) { field = value; updateAccessibility() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    fun setWords(words: Map<String, Long>) {
        val next = words.asSequence().filter { (word, count) ->
            count > 0 && word.isNotBlank() && word.codePointCount(0, word.length) <= 64 && word.none(Char::isISOControl)
        }.map { Entry(it.key, it.value) }
            .sortedWith(compareByDescending<Entry> { it.count }.thenBy { it.word }).take(64).toList()
        if (next != entries) { entries = next; arrange(); invalidate() }
        updateAccessibility()
    }

    internal fun layoutSnapshot(): List<Placement> = placements.map { it.copy(bounds = RectF(it.bounds)) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(320).toInt(), widthMeasureSpec), resolveSize(dp(280).toInt(), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { arrange() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrange(); updateAccessibility(); invalidate()
    }

    private fun updateAccessibility() {
        val number = NumberFormat.getIntegerInstance()
        contentDescription = listOf(accessibilityTitle.toString(), if (entries.isEmpty()) emptyText.toString()
            else entries.joinToString("\n") { "${it.word}: ${number.format(it.count)}" })
            .filter(String::isNotBlank).joinToString("\n")
    }

    private fun arrange() {
        placements = emptyList()
        val area = RectF(paddingLeft + dp(14), paddingTop + dp(16),
            width - paddingRight - dp(14), height - paddingBottom - dp(16))
        if (area.width() <= 0 || area.height() <= 0 || entries.isEmpty()) return
        val primary = context.settingsColor(androidx.appcompat.R.attr.colorPrimary)
        val ink = context.settingsColor(com.google.android.material.R.attr.colorOnSurface)
        val palette = intArrayOf(
            primary, ColorUtils.blendARGB(primary, ink, .24f),
            ColorUtils.blendARGB(primary, ink, .48f), ink,
        )
        val result = ArrayList<Placement>()
        val occupied = ArrayList<RectF>()
        val low = ln(entries.last().count.toDouble() + 1.0)
        val spread = ln(entries.first().count.toDouble() + 1.0) - low
        val gap = dp(3)
        entries.forEachIndexed { index, entry ->
            val weight = if (spread <= 0.0) .25 else
                sqrt(((ln(entry.count.toDouble() + 1.0) - low) / spread).coerceIn(0.0, 1.0))
            val size = sp((12 + 29 * weight).toFloat()).coerceAtMost(height * .22f)
            val bold = index < 6
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val glyph = fitGlyph(entry.word, size, area.width() - 2 * gap, area.height() - 2 * gap)
                ?: return@forEachIndexed
            val box = findPosition(glyph.ink, area, occupied, gap, index) ?: return@forEachIndexed
            result += Placement(entry.word, entry.count, box, box.top - glyph.ink.top,
                glyph.size, bold, palette[index % palette.size], box.left - glyph.ink.left)
            occupied += RectF(box).apply { inset(-gap, -gap) }
        }
        placements = result
    }

    /** Fit long phrases before packing. Ordinary words retain their frequency-based size. */
    private fun fitGlyph(word: String, desired: Float, maxWidth: Float, maxHeight: Float): Glyph? {
        if (maxWidth <= 0 || maxHeight <= 0 || desired <= 0) return null
        var size = desired
        val regularMinimum = minOf(sp(12f), desired)
        val narrowMinimum = minOf(sp(10f), regularMinimum)
        val preferredWidth = maxWidth * .78f
        val ink = Rect()
        repeat(24) {
            paint.textSize = size
            paint.getTextBounds(word, 0, word.length, ink)
            if (ink.isEmpty) return null
            val hardFit = minOf(1f, maxWidth / ink.width(), maxHeight / ink.height())
            if (hardFit < 1f) {
                val fitted = size * hardFit * .99f
                if (fitted < narrowMinimum) {
                    // A safety margin must not reject a word that fits exactly at the floor.
                    paint.textSize = narrowMinimum
                    paint.getTextBounds(word, 0, word.length, ink)
                    return if (!ink.isEmpty && ink.width() <= maxWidth && ink.height() <= maxHeight)
                        Glyph(narrowMinimum, Rect(ink)) else null
                }
                size = fitted
            } else if (ink.width() > preferredWidth && size > regularMinimum + .01f) {
                size = maxOf(regularMinimum, size * .9f)
            } else {
                return Glyph(size, Rect(ink))
            }
        }
        return null
    }

    /** A golden-angle search forms an oval without aligning words to a rectangular grid. */
    private fun findPosition(ink: Rect, area: RectF, occupied: List<RectF>, gap: Float, rank: Int): RectF? {
        val phase = rank * .125
        val rotationX = cos(phase).toFloat()
        val rotationY = sin(phase).toFloat()
        val step = dp(5.5f)
        val padded = RectF()
        for (sample in searchPath) {
            val x = area.centerX() + (sample.x * rotationX - sample.y * rotationY) * step
            val y = area.centerY() + (sample.x * rotationY + sample.y * rotationX) * step * .62f
            val left = x - ink.width() / 2f
            val top = y - ink.height() / 2f
            padded.set(left - gap, top - gap, left + ink.width() + gap, top + ink.height() + gap)
            if (!area.contains(padded) || occupied.any { RectF.intersects(it, padded) }) continue
            return RectF(left, top, left + ink.width(), top + ink.height())
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (placements.isEmpty()) {
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 14 * resources.displayMetrics.scaledDensity
            paint.color = context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            val text = TextUtils.ellipsize(emptyText, paint, (width - paddingLeft - paddingRight - dp(20)).coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, (width - paint.measureText(text)) / 2, (height - paint.ascent() - paint.descent()) / 2, paint)
            return
        }
        placements.forEach { word ->
            paint.typeface = if (word.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textSize = word.textSize; paint.color = word.color
            canvas.drawText(word.word, word.originX, word.baseline, paint)
        }
    }

    private fun dp(value: Int) = value * resources.displayMetrics.density
    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        // Cache the expensive trigonometry once. Each word visits at most 700 candidates.
        val searchPath = List(700) { index ->
            val angle = index * Math.PI * (3 - sqrt(5.0))
            val radius = sqrt(index.toDouble())
            PointF((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
        }
    }
}
