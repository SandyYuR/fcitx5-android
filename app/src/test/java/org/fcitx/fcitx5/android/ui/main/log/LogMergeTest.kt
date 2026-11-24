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

    /**
     * 回归：同文事件靠身份计数抵消时没有时间约束，于是「历史读取完成之后、logcat 快照完成
     * 之前」发生的新事件会被一条早已滚出缓冲区的旧同文记录抵消掉，而 resumeFrom 又已经越过
     * 它，实时 reader 也不会补回来。
     *
     * onStartInput 的 restarting 诊断就是这种形状：同一时刻的主线程上可能重复打印同一句话。
     */
    @Test
    fun aNewIdenticalEventIsNotCancelledByAnOlderPersistedTwin() {
        val persistedLine = persisted(-600_000, message = "service.onStartInput restarting=false")
        val newEvent = logcat(0, message = "service.onStartInput restarting=false")
        val merged = merge(persistedLines = listOf(persistedLine), snapshotLines = listOf(newEvent))
        assertEquals(2, merged.lines.size)
        assertEquals(persistedLine, merged.lines[0])
        assertEquals(newEvent, merged.lines[1])
    }

    /**
     * LogSink 记的是 System.currentTimeMillis()，logd 记的是接收时刻，同一条事件两边的时间戳
     * 会差几毫秒到几百毫秒；邻域匹配必须容得下这个偏差，否则去重直接失效。
     */
    @Test
    fun aTwinADeviationApartIsStillTheSameEvent() {
        val merged = merge(
            persistedLines = listOf(persisted(-1_000)),
            snapshotLines = listOf(logcat(-1_000 + 350))
        )
        assertEquals(1, merged.lines.size)
        assertEquals(persisted(-1_000), merged.lines.single())
    }

    /**
     * 邻域不能宽到把两条真正的不同事件并成一条：同一句「同一毫秒」之外的同文打印必须都留下。
     */
    @Test
    fun identicalTextOutsideTheToleranceStaysTwoEvents() {
        val merged = merge(
            persistedLines = listOf(persisted(-1_000, message = "restarting=false")),
            snapshotLines = listOf(logcat(-1_000 + 1_500, message = "restarting=false"))
        )
        assertEquals(2, merged.lines.size)
    }

    /**
     * 回归：快照时间倒退时只重置游标、不重算窗口下界，扫描就只看上界，比邻域早得多的
     * 一条同文候选也会被当成「最接近」而消耗掉，新的同文事件跟着一起消失。
     *
     * 时间倒退本身是真实的：几个 logd 缓冲区交错输出时，快照里会出现比上一行更早的
     * 记录。
     */
    @Test
    fun aBackwardsSnapshotQueryCannotReachHistoryOutsideTheNeighbourhood() {
        val older = persisted(-5_000, message = "same message twice")
        val newer = logcat(0, message = "same message twice")
        val backwards = logcat(-1_000, message = "same message twice")
        val merged = merge(
            persistedLines = listOf(older),
            snapshotLines = listOf(newer, backwards)
        )
        // 两条快照行与 5 秒前的同文历史都不在 500 ms 邻域里，一条都不能被消掉。
        assertEquals(listOf(older, newer, backwards), merged.lines)
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

    /**
     * 回归：快照曾经以持久化历史的第一行为起点。首次运行、历史被清空，以及 Application
     * 在 LogSink 初始化之后、startup 标记之前打下的诊断都早于那一行，而且没有持久化孪生，
     * 一旦把历史起点当成 logcat 覆盖的起点，它们会整段从页面和导出里消失。
     */
    @Test
    fun startupDiagnosticsOlderThanTheFirstPersistedEventAreKept() {
        val early = logcat(-30_000, level = 'D', tag = "FcitxApp", message = "isDirectBootMode=false")
        val merged = merge(
            persistedLines = listOf(persisted(-1_000)),
            snapshotLines = listOf(early, logcat(-1_000))
        )
        assertEquals(listOf(early, persisted(-1_000)), merged.lines)
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

    /**
     * 回归：实时 reader 的起点不能落在未来。
     *
     * 持久历史只有当地墙上时间，改过时区或时钟之后最新那行可能被解释成几小时之后；
     * 拿它当 logcat -T 的起点，页面会一直空到时钟追上。
     */
    @Test
    fun theResumeBoundaryIsNeverInTheFuture() {
        val ahead = persisted(3 * 60 * 60 * 1000, message = "misread by a timezone change")
        val merged = merge(persistedLines = listOf(ahead), snapshotLines = emptyList())
        assertEquals(now + 1, merged.resumeFrom)
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
