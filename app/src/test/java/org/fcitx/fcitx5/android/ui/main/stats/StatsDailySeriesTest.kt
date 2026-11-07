/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import org.fcitx.fcitx5.android.data.stats.StatsDay
import org.fcitx.fcitx5.android.data.stats.StatsTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsDailySeriesTest {
    private fun day(date: LocalDate, characters: Long = 1) = StatsDay(date.toString(), StatsTotals(characters = characters))

    @Test fun shortPeriodsRetainChronologicalDailyValues() {
        val days = listOf(day(LocalDate.of(2026, 11, 7), 9), day(LocalDate.of(2026, 11, 1), 2))
        for (period in 0..2) {
            assertEquals(linkedMapOf("2026-11-01" to 2L, "2026-11-07" to 9L), StatsDailySeries.build(days, period))
        }
    }

    @Test fun aWholeYearKeepsEveryDayAndTheLatestMonthWithinTheChartLimit() {
        val start = LocalDate.of(2026, 1, 1)
        val days = (0L until 365L).map { day(start.plusDays(it), it + 1) }
        val series = StatsDailySeries.build(days.reversed(), 3)
        assertEquals(12, series.size)
        assertEquals("2026-12", series.keys.last())
        assertEquals(days.sumOf { it.totals.characters }, series.values.sum())
        assertEquals(days.takeLast(31).sumOf { it.totals.characters }, series["2026-12"])
        assertTrue(series.size <= 64)
    }

    @Test fun allTimeUsesMonthsUpToTwentyFourAndYearsBeyondThat() {
        val start = LocalDate.of(2024, 1, 1)
        val months = (0L until 24L).flatMap { month ->
            (0L until 5L).map { offset -> day(start.plusMonths(month).plusDays(offset), month + 1) }
        }
        val monthly = StatsDailySeries.build(months, 4)
        assertEquals(24, monthly.size)
        assertEquals("2025-12", monthly.keys.last())
        assertEquals(months.sumOf { it.totals.characters }, monthly.values.sum())
        assertEquals(120L, monthly["2025-12"])

        val longer = months + day(LocalDate.of(2026, 1, 1), 100)
        val yearly = StatsDailySeries.build(longer, 4)
        assertEquals(listOf("2024", "2025", "2026"), yearly.keys.toList())
        assertEquals(longer.sumOf { it.totals.characters }, yearly.values.sum())
        assertEquals(100L, yearly["2026"])
    }

    @Test fun aggregationUsesTheSameSaturatingCountsAsStatistics() {
        val days = listOf(day(LocalDate.of(2026, 1, 1), Long.MAX_VALUE), day(LocalDate.of(2026, 1, 2), 1))
        assertEquals(mapOf("2026-01" to Long.MAX_VALUE), StatsDailySeries.build(days, 3))
        assertTrue(StatsDailySeries.build(emptyList(), 4).isEmpty())
    }

    @Test fun archivesCoveringMoreThanSixtyFourYearsKeepTheirNewestDataAndTotal() {
        for (yearCount in listOf(65, 128, 129)) {
            val days = (0 until yearCount).map { year -> day(LocalDate.of(1900 + year, 1, 1), year + 1L) }
            val series = StatsDailySeries.build(days.reversed(), 4)
            assertTrue(series.size <= 64)
            assertTrue(series.keys.first().startsWith("1900"))
            assertTrue(series.keys.last().endsWith((1900 + yearCount - 1).toString()))
            assertEquals(days.sumOf { it.totals.characters }, series.values.sum())
            val yearsPerBar = (yearCount + 63) / 64
            val finalBarSize = if (yearCount % yearsPerBar == 0) yearsPerBar else yearCount % yearsPerBar
            assertEquals(days.takeLast(finalBarSize).sumOf { it.totals.characters }, series.values.last())
        }
    }
}
