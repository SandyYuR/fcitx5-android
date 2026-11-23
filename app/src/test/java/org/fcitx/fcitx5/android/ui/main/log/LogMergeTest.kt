/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * 去重与时间线归并的断言。
 *
 * 两种行形状都取自实测：LogSink 写 %c %s: %s（tag 后紧接冒号），
 * logcat 渲染 threadtime 时会把 tag 用空格补齐到 8 列（I Tprobe  : msg）。
 */
class LogMergeTest {

    private val now = 1_700_000_000_000L

    /** LogSink 写出的那一行：tag 后不留补齐空格。 */
    private fun persisted(
        offsetMillis: Long,
        level: Char = 'I',
        tag: String = "RimeLifecycle",
        message: String = "startup phase=assets",
        pid: Int = 1234,
        tid: Int = 1235
    ): String = prefix(offsetMillis, pid, tid) + " $level $tag: $message"

    /** logcat 重放同一事件时的那一行：tag 被空格补齐到 8 列。 */
    private fun logcat(
        offsetMillis: Long,
        level: Char = 'I',
        tag: String = "RimeLifecycle",
        message: String = "startup phase=assets",
        pid: Int = 1234,
        tid: Int = 1235
    ): String = prefix(offsetMillis, pid, tid) + " $level ${tag.padEnd(8)}: $message"

    private fun prefix(offsetMillis: Long, pid: Int, tid: Int): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now + offsetMillis
        return String.format(
            Locale.US,
            "%02d-%02d %02d:%02d:%02d.%03d %5d %5d",
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
            calendar.get(Calendar.MILLISECOND),
            pid,
            tid
        )
    }

    private fun merge(persistedLines: List<String>, snapshotLines: List<String>) =
        mergePersistedAndLogcat(persistedLines, snapshotLines, now)

    @Test
    fun aPersistedEventIsNotShownTwiceWhenLogcatStillBuffersIt() {
        val merged = merge(
            persistedLines = listOf(persisted(-2_000)),
            snapshotLines = listOf(logcat(-2_000))
        )
        assertEquals(1, merged.lines.size)
        assertEquals(persisted(-2_000), merged.lines.single())
    }

    @Test
    fun recordsThatOnlyLogcatHasAreKept() {
        val native = logcat(-3_000, level = 'D', tag = "nativeloader", message = "Load libfoo.so")
        val merged = merge(
            persistedLines = listOf(persisted(-2_000)),
            snapshotLines = listOf("--------- beginning of main", native, logcat(-2_000))
        )
        assertEquals(listOf("--------- beginning of main", native, persisted(-2_000)), merged.lines)
    }

    @Test
    fun aTagPaddedByLogcatStillMatchesItsPersistedTwin() {
        // Tprobe 只有 6 个字符，logcat 会补 2 个空格；LogSink 不补。
        val merged = merge(
            persistedLines = listOf(persisted(-1_000, tag = "Tprobe", message = "boundary probe")),
            snapshotLines = listOf(logcat(-1_000, tag = "Tprobe", message = "boundary probe"))
        )
        assertEquals(1, merged.lines.size)
    }

    @Test
    fun repeatedIdenticalEventsCancelOneForOne() {
        val line = persisted(-1_000, message = "same message twice")
        val twin = logcat(-1_000, message = "same message twice")
        val merged = merge(
            persistedLines = listOf(line, line),
            snapshotLines = listOf(twin, twin, twin)
        )
        // 持久历史里出现两次，就只从快照里消掉两份，第三份留在快照里。
        assertEquals(3, merged.lines.size)
        assertEquals(line, merged.lines[0])
        assertEquals(line, merged.lines[1])
        assertEquals(twin, merged.lines[2])
    }

    @Test
    fun anEventFromAnotherProcessIsNotTreatedAsADuplicate() {
        val merged = merge(
            persistedLines = listOf(persisted(-1_000, pid = 1234)),
            snapshotLines = listOf(logcat(-1_000, pid = 9999))
        )
        assertEquals(2, merged.lines.size)
    }

    @Test
    fun olderSnapshotEntriesCannotBePlacedAfterNewerPersistedOnes() {
        val older = logcat(-60_000, tag = "nativeloader", message = "early")
        val newer = persisted(-1_000)
        val merged = merge(persistedLines = listOf(newer), snapshotLines = listOf(older))
        assertEquals(listOf(older, newer), merged.lines)
    }

    @Test
    fun linesWithoutAThreadtimePrefixFollowTheirPredecessor() {
        val native = logcat(-120_000, tag = "nativeloader", message = "native")
        val frame = "\tat com.example.Foo.bar(Foo.kt:1)"
        val merged = merge(
            persistedLines = listOf(persisted(-1_000)),
            snapshotLines = listOf(native, frame)
        )
        assertEquals(listOf(native, frame, persisted(-1_000)), merged.lines)
    }

    @Test
    fun resumeFromIsOneMillisAfterTheNewestLineInEitherSource() {
        val later = logcat(-2_000, tag = "nativeloader", message = "later")
        val merged = merge(persistedLines = listOf(persisted(-5_000)), snapshotLines = listOf(later))
        val newest = parseLogLine(later, now)
        assertNotNull(newest)
        assertEquals(newest!!.epochMillis + 1, merged.resumeFrom)
    }

    @Test
    fun withoutAnyParsableLineThereIsNoResumeBoundary() {
        val merged = merge(
            persistedLines = emptyList(),
            snapshotLines = listOf("--------- beginning of main")
        )
        assertEquals(listOf("--------- beginning of main"), merged.lines)
        assertNull(merged.resumeFrom)
    }

    @Test
    fun oldestLogTimeSkipsUnparsableLines() {
        val oldest = oldestLogTime(
            listOf("--------- beginning of main", "\tat com.example.Foo.bar(Foo.kt:1)", persisted(-9_000)),
            now
        )
        assertEquals(parseLogLine(persisted(-9_000), now)!!.epochMillis, oldest)
        assertNull(oldestLogTime(listOf("--------- beginning of main"), now))
    }

    @Test
    fun decemberLinesReadInJanuaryStayBeforeJanuaryLines() {
        // 1 月 2 日读到 12 月 31 日的行：threadtime 没有年份，不能补成本年（那会是未来）。
        val january = 1_704_110_400_000L
        val december = parseLogLine("12-31 23:00:00.000  1234  1235 I RimeLifecycle: old", ThreadtimeClock(january))
        assertNotNull(december)
        assertTrue(december!!.epochMillis < january)
    }

    @Test
    fun aParsedLineKeepsAColonInsideTheMessage() {
        val parsed = parseLogLine(
            "11-24 10:00:00.000  1234  1235 W RimeLifecycle: phase=assets: retry",
            now
        )
        assertNotNull(parsed)
        assertEquals("RimeLifecycle", parsed!!.tag)
        assertEquals("phase=assets: retry", parsed.message)
    }
}
