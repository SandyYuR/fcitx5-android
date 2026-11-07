/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.stats

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class StatsSettings(val loaded: Boolean = false, val enabled: Boolean = false,
    val wordFrequencyEnabled: Boolean = false, val error: String? = null, val rejectedEvents: Long = 0)

/** Text-free metadata from admitted, ordered Rime DOWN events. */
data class StatsKeySample(val schemaId: String, val schemaName: String, val keyId: String,
    val isDeletion: Boolean, val composing: Boolean, val wallTimeMs: Long, val monotonicTimeMs: Long)

/** Local calendar dates, inclusive start and exclusive end. Null bounds select all dates. */
@Serializable
data class StatsRange(val fromDay: String? = null, val untilDay: String? = null) {
    init {
        require((fromDay == null) == (untilDay == null))
        if (fromDay != null) {
            require(validDay(fromDay) && validDay(untilDay!!))
            require(fromDay < untilDay)
        }
    }
    fun contains(day: String) = fromDay == null || day >= fromDay && day < untilDay!!

    companion object {
        fun all() = StatsRange()
        fun today(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()) = period(Calendar.DAY_OF_MONTH, now, zone)
        fun week(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()) = period(Calendar.WEEK_OF_YEAR, now, zone)
        fun month(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()) = period(Calendar.MONTH, now, zone)
        fun year(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()) = period(Calendar.YEAR, now, zone)
        fun day(timestamp: Long, zone: TimeZone = TimeZone.getDefault()): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }.format(java.util.Date(timestamp))
        internal fun validDay(day: String): Boolean = day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && runCatching {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
            format.parse(day) != null
        }.getOrDefault(false)
        private fun period(field: Int, now: Long, zone: TimeZone): StatsRange {
            val start = Calendar.getInstance(zone, Locale.ROOT).apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                when (field) {
                    Calendar.WEEK_OF_YEAR -> add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
                    Calendar.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
                    Calendar.YEAR -> { set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 1) }
                }
            }
            val from = day(start.timeInMillis, zone)
            start.add(field, 1)
            return StatsRange(from, day(start.timeInMillis, zone))
        }
    }
}

@Serializable
data class StatsTotals(val characters: Long = 0, val commits: Long = 0, val keys: Long = 0,
    val deletions: Long = 0, val totalCodeLength: Long = 0, val activeMillis: Long = 0,
    val speedCharacters: Long = 0, val peakCpm: Double = 0.0, val peakKps: Double = 0.0,
    val peakCodeLength: Double = 0.0) {
    val averageCpm: Double get() = if (activeMillis > 0) speedCharacters * 60_000.0 / activeMillis else 0.0
    val averageCodeLength: Double get() = if (characters > 0) totalCodeLength.toDouble() / characters else 0.0
    val deletionRatio: Double get() = if (keys > 0) deletions.toDouble() / keys else 0.0
}

@Serializable data class StatsSchema(val id: String, val name: String, val careerCharacters: Long = 0)
@Serializable data class StatsDay(val day: String, val totals: StatsTotals)
@Serializable data class StatsKeyGeometry(val label: String, val x: Float, val y: Float, val width: Float, val height: Float)
@Serializable data class StatsLayout(val rows: List<List<String>> = emptyList(), val geometry: List<StatsKeyGeometry> = emptyList())
data class StatsSnapshot(val range: StatsRange, val schemaId: String?, val schemas: List<StatsSchema>,
    val totals: StatsTotals, val daily: List<StatsDay>, val codeLengths: Map<Int, Long>,
    val wordLengths: Map<Int, Long>, val keyHeatmap: Map<String, Long>, val words: Map<String, Long>,
    val careerCharacters: Long = 0, val layout: StatsLayout? = null, val recordingEpoch: Long = 0)
