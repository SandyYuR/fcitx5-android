/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import org.fcitx.fcitx5.android.data.stats.StatsDay
import org.fcitx.fcitx5.android.data.stats.StatsLimits

/** Aggregate long ranges before the distribution view applies its category limit. */
internal object StatsDailySeries {
    fun build(days: List<StatsDay>, period: Int): Map<String, Long> {
        val labelLength = when (period) {
            3 -> 7 // A calendar year is shown by month.
            4 -> if (days.map { it.day.take(7) }.distinct().size <= 24) 7 else 4
            else -> 10 // Today, this week and this month keep their daily labels.
        }
        val totals = sortedMapOf<String, Long>()
        days.forEach { day ->
            val label = day.day.take(labelLength)
            totals[label] = StatsLimits.sum(totals[label] ?: 0L, day.totals.characters)
        }
        if (period == 4 && totals.size > 64) {
            // Imported archives may cover more than 64 years. Keep every year in
            // chronological ranges rather than letting the chart discard the tail.
            val yearsPerBar = (totals.size + 63) / 64
            return totals.entries.chunked(yearsPerBar).associate { years ->
                val first = years.first().key
                val last = years.last().key
                (if (first == last) first else "$first–$last") to
                    years.fold(0L) { total, year -> StatsLimits.sum(total, year.value) }
            }
        }
        return totals
    }
}
