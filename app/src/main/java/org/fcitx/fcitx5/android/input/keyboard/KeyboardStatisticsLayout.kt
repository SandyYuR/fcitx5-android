/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.data.stats.StatsKeyGeometry
import org.fcitx.fcitx5.android.data.stats.StatsLayout
import java.util.Locale

/** Converts already laid-out keycaps into an immutable snapshot, never on the per-key input path. */
internal object KeyboardStatisticsLayout {
    fun label(definition: KeyDef): String? {
        if (definition is PlaceholderKey || definition.behaviors.isEmpty()) return null
        val raw = when (definition) {
            is AlphabetKey -> definition.character
            is SpaceKey, is MiniSpaceKey -> "Space"
            is BackspaceKey -> "Backspace"
            is ReturnKey -> "Enter"
            is CapsKey -> "Shift"
            is LanguageKey -> "Language"
            is LayoutSwitchKey, is ImageLayoutSwitchKey -> "Layout"
            is CommaKey -> ","
            is SymbolKey -> "."
            is MacroKey -> definition.displayText?.takeIf(String::isNotBlank) ?: definition.label
            else -> (definition.appearance as? KeyDef.Appearance.Text)?.displayText
                ?: definition.iconSlot ?: "Key"
        }
        // Record the visible key name, never a macro's expanded text or clipboard payload.
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 32 || trimmed.any(Char::isISOControl)) return null
        return if (trimmed.length == 1) trimmed.lowercase(Locale.ROOT) else trimmed
    }

    /**
     * Converts the current key layout into a snapshot.
     *
     * [rowContainers] are the keyboard's key rows in visual order. Keys are grouped by the row
     * container that holds them, so a side bar key that sits a pixel lower than the main row still
     * lands on the row the user sees. Keys outside any row container (side bar items, floating
     * keys, or keyboards without row containers) join the nearest row; without row containers the
     * older parent-based grouping is used.
     */
    fun capture(root: ViewGroup, keys: Collection<KeyView>, rowContainers: List<View> = emptyList()): StatsLayout {
        if (!root.isAttachedToWindow || root.width <= 0 || root.height <= 0) return StatsLayout()
        val location = IntArray(2).also(root::getLocationInWindow)
        val density = root.resources.displayMetrics.density
        val rowViews = rowContainers.filter { it !== root && it.isAttachedToWindow }
        val rowLocation = IntArray(2)
        val rowTops = IntArray(rowViews.size)
        val rowBottoms = IntArray(rowViews.size)
        rowViews.forEachIndexed { index, row ->
            row.getLocationInWindow(rowLocation)
            rowTops[index] = rowLocation[1] - location[1]
            rowBottoms[index] = rowTops[index] + row.height
        }
        // Row buckets keyed by row index. Parent-first order is irrelevant: buckets are sorted
        // below, and rows that keep no keys are dropped instead of leaving blank lines.
        val buckets = LinkedHashMap<Int, MutableList<StatsKeyGeometry>>()
        val parentTops = HashMap<View, Int>()
        keys.filter { it.statisticsKeyLabel != null && it.isShown && it.isEnabled && belongsTo(it, root) }
            .forEach { key ->
                key.updateBounds()
                val rawTop = key.bounds.top - location[1]
                val bounds = Rect(key.bounds).apply {
                    offset(-location[0], -location[1])
                    inset(key.hMargin, key.vMargin)
                }
                if (!bounds.intersect(0, 0, root.width, root.height) || bounds.isEmpty) return@forEach
                val parent = key.parent as? View
                var index = if (rowViews.isEmpty()) -1 else rowViews.indexOfFirst { it === parent }
                if (index < 0 && rowViews.isNotEmpty()) index = nearestRow(rawTop, key.bounds.bottom - location[1], rowTops, rowBottoms)
                // Non-row parents keep their own band: with no row containers that is the old
                // parent grouping, and in mixed layouts it sorts after the known rows by position.
                val bucket = if (index >= 0) index else {
                    val top = parentTops.getOrPut(parent ?: root) { parentOrRootTop(parent, root, location, rowLocation) }
                    10_000 + top.coerceAtLeast(0)
                }
                val y = if (index >= 0) (rowTops[index] / density).coerceAtLeast(0f) else
                    (parentTops[parent ?: root] ?: rawTop).coerceAtLeast(0) / density
                buckets.getOrPut(bucket) { mutableListOf() } += StatsKeyGeometry(key.statisticsKeyLabel!!,
                    bounds.left.toFloat() / root.width, y, bounds.width().toFloat() / root.width,
                    bounds.height() / density, if (index >= 0) index else -1)
            }
        var remaining = 128
        val bounded = buckets.entries.sortedBy { it.key }.take(32).mapNotNull { (_, row) ->
            row.sortedBy { it.x }.take(remaining).also { remaining -= it.size }
                .takeIf(List<StatsKeyGeometry>::isNotEmpty)
        }
        return StatsLayout(bounded.map { row -> row.map { it.label } }, bounded.flatten())
    }

    private fun parentOrRootTop(parent: View?, root: ViewGroup, rootLocation: IntArray, scratch: IntArray): Int {
        (parent ?: root).getLocationInWindow(scratch)
        return scratch[1] - rootLocation[1]
    }

    /**
     * Picks the row a key outside the row containers belongs to: the row it overlaps most, or the
     * closest one when it overlaps none. Touching bands (a key that ends exactly where the next
     * row starts) are not a tie: overlap decides first.
     */
    private fun nearestRow(top: Int, bottom: Int, rowTops: IntArray, rowBottoms: IntArray): Int {
        var best = 0
        var bestDistance = Long.MAX_VALUE
        val center = top + bottom
        for (index in rowTops.indices) {
            val overlap = minOf(bottom, rowBottoms[index]) - maxOf(top, rowTops[index])
            val distance = if (overlap > 0) -overlap.toLong() else {
                val gap = maxOf(rowTops[index] - bottom, top - rowBottoms[index], 0)
                (gap.toLong() shl 20) + kotlin.math.abs(center - rowTops[index] - rowBottoms[index]) / 2
            }
            if (distance < bestDistance) { bestDistance = distance; best = index }
        }
        return best
    }

    private fun belongsTo(view: View, root: View): Boolean {
        var parent = view.parent
        while (parent is View) { if (parent === root) return true; parent = parent.parent }
        return false
    }
}
