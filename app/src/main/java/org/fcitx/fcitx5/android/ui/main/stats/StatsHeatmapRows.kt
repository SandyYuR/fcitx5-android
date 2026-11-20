/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import org.fcitx.fcitx5.android.data.stats.StatsKeyGeometry
import kotlin.math.abs

/**
 * Groups stored key geometry into the rows a user sees.
 *
 * New records carry the row container index, which is the only reliable row identity: side bar
 * keys are laid out in their own containers and can sit a pixel away from the main row. Records
 * written before rows were stored fall back to clustering by y, which repairs those snapshots
 * instead of splitting them on a sub-pixel difference.
 */
internal object StatsHeatmapRows {
    /** Keys of one stored row may differ by a fraction of a pixel after rounding. */
    const val ROW_TOLERANCE = 4f

    fun rows(geometry: List<StatsKeyGeometry>): List<List<StatsKeyGeometry>> {
        if (geometry.isEmpty()) return emptyList()
        val indexed = geometry.filter { it.row >= 0 }
        if (indexed.isEmpty()) return legacyRows(geometry)
        val result = indexed.groupBy { it.row }.toSortedMap().values
            .map { row -> row.toMutableList() }.toMutableList()
        val loose = geometry.filter { it.row < 0 }
        if (loose.isNotEmpty()) {
            // Mixed records: attach unnumbered keys to the closest numbered row, otherwise keep
            // them as their own row so nothing is silently dropped.
            loose.groupBy { it.y }.toSortedMap().forEach { (_, keys) ->
                val y = keys.minOf { it.y }
                val target = result.indices
                    .filter { abs(result[it].minOf { key -> key.y } - y) <= ROW_TOLERANCE }
                    .minByOrNull { abs(result[it].minOf { key -> key.y } - y) }
                if (target != null) result[target] += keys else result += keys.toMutableList()
            }
        }
        return result.map { row -> row.sortedBy { it.x } }
    }

    private fun legacyRows(geometry: List<StatsKeyGeometry>): List<List<StatsKeyGeometry>> {
        val sorted = geometry.sortedBy { it.y }
        val rows = mutableListOf<MutableList<StatsKeyGeometry>>()
        var rowStart = sorted.first().y
        var row = mutableListOf<StatsKeyGeometry>()
        sorted.forEach { key ->
            if (key.y - rowStart > ROW_TOLERANCE) {
                rows += row
                row = mutableListOf()
                rowStart = key.y
            }
            row += key
        }
        if (row.isNotEmpty()) rows += row
        return rows.map { it.sortedBy { it.x } }
    }
}
