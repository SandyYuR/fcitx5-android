/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.stats

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class StatsDataTest {
    @Test fun periodsUseLocalCalendarBoundariesAndMondayWeeks() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }.parse("2026-11-07 23:15")!!.time
        assertEquals(StatsRange("2026-11-07", "2026-11-08"), StatsRange.today(now, zone))
        assertEquals(StatsRange("2026-11-02", "2026-11-09"), StatsRange.week(now, zone))
        assertEquals(StatsRange("2026-11-01", "2026-12-01"), StatsRange.month(now, zone))
        assertEquals(StatsRange("2026-01-01", "2027-01-01"), StatsRange.year(now, zone))
        assertFalse(StatsRange.week(now, zone).contains("2026-11-09"))
        assertTrue(StatsRange.all().contains("2020-01-01"))
        assertThrows(IllegalArgumentException::class.java) { StatsRange("2026-02-30", "2026-03-01") }
    }

    @Test fun onlyEntireHanCommitsCountAndVocabularyNeverIncludesMixedText() {
        assertEquals(3, StatsText.characters("𠮷野家"))
        for (text in listOf("中文1", "中文abc", "中文。", "中文 ", "🙂", "", "/命令")) {
            assertEquals(text, 0, StatsText.characters(text))
            assertTrue(StatsText.words(text).isEmpty())
        }
        assertEquals(listOf("中文", "中", "文"), StatsText.words("中文"))
        assertEquals(64, StatsText.words("中".repeat(100)).size)
        assertEquals(0, StatsText.characters("中".repeat(StatsLimits.MAX_COMMIT_UNITS + 1)))
    }

    @Test fun keyHeatAndChineseCommitsAreIndependentAndCountsSaturate() {
        val bucket = StatsBucket("2026-11-07", "wanxiang")
        bucket.key(false); bucket.key(true)
        bucket.heat("中")
        bucket.commit(2, 5, "中文")
        assertEquals(2L, bucket.totals.keys)
        assertEquals(1L, bucket.totals.deletions)
        assertEquals(mapOf("中" to 1L), bucket.heat)
        assertEquals(mapOf(5 to 1L), bucket.codeLengths)
        assertEquals(mapOf(2 to 1L), bucket.wordLengths)
        assertEquals(2.5, bucket.totals.averageCodeLength, 0.001)
        bucket.words["中"] = Long.MAX_VALUE
        bucket.commit(1, 2, "中")
        assertEquals(Long.MAX_VALUE, bucket.words["中"])
    }

    @Test fun wordAndHeatMapsRespectTheirCapacityLimits() {
        val bucket = StatsBucket("2026-11-07", "wanxiang")
        repeat(600) { index -> bucket.commit(1, 2, (0x4e00 + index).toChar().toString()); bucket.heat("K$index") }
        assertEquals(StatsLimits.MAX_WORDS, bucket.words.size)
        assertEquals(StatsLimits.MAX_KEYS, bucket.heat.size)
        assertEquals(600L, bucket.totals.characters)
    }

    @Test fun peakUsesWholeSpeedSegmentsReachingTenSecondsAndKeepsItsAssociatedMetrics() {
        val window = StatsSpeedWindow()
        assertNull(window.add(StatsSpeedSample(5000, 10, 10, 20)))
        val peak = window.add(StatsSpeedSample(5000, 20, 20, 40))!!
        assertEquals(180.0, peak.cpm, 0.001)
        assertEquals(3.0, peak.kps, 0.001)
        assertEquals(2.0, peak.codeLength, 0.001)
        val next = window.add(StatsSpeedSample(15000, 30, 90, 90))!!
        assertEquals(15000L, next.millis)
        assertEquals(120.0, next.cpm, 0.001)
        val combined = combineTotals(listOf(StatsTotals(peakCpm = 180.0, peakKps = 3.0, peakCodeLength = 2.0),
            StatsTotals(peakCpm = 120.0, peakKps = 6.0, peakCodeLength = 3.0)))
        assertEquals(3.0, combined.peakKps, 0.001)
    }

    @Test fun importsRejectMalformedRangesCountsVocabularyAndLayoutBeforeReplacement() {
        val schema = StatsSchema("wanxiang", "万象")
        val bucket = StatsBucket("2026-11-07", "wanxiang")
        val valid = StatsArchive(schemas = listOf(schema), buckets = listOf(bucket))
        assertEquals(valid, StatsArchive.decode(StatsArchive.json.encodeToString(valid)))
        for (invalid in listOf(valid.copy(schemas = listOf(schema, schema)),
            valid.copy(buckets = listOf(bucket.copy(day = "2026-02-30"))),
            valid.copy(buckets = listOf(bucket.copy(totals = StatsTotals(characters = -1)))),
            valid.copy(buckets = listOf(bucket.copy(words = linkedMapOf("secret123" to 1L)))))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            StatsArchive.validateLayout(StatsLayout(geometry = listOf(StatsKeyGeometry("a", Float.NaN, 0f, 1f, 1f))))
        }
    }
}
