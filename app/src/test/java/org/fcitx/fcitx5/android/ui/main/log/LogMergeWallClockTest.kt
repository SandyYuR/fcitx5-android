/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import org.fcitx.fcitx5.android.utils.LogFileManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 持久历史只有当地墙上时间，没有任何地方记着它写于哪个时区。
 *
 * 设备改过时区之后，读取端按当前时区重新解释这些行，最新的行可能被挪到未来：实时 reader
 * 拿到一个未来的起点，页面一直空到时钟追上；与 logcat 里那批孪生行的时间也不再落进去重
 * 容差，同一条事件显示两遍。[normalizePersistedWallClock] 用文件的最后修改时间反推写入
 * 时区，把历史搬回一条能自洽的时间线。
 */
class LogMergeWallClockTest {

    private lateinit var originalZone: TimeZone
    private lateinit var dir: File

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        dir = File(System.getProperty("java.io.tmpdir"), "log-merge-wall-clock-" + System.nanoTime())
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
        dir.deleteRecursively()
    }

    private fun line(wallClock: String, message: String = "warm start"): String =
        "$wallClock  1234  1235 I RimeLifecycle: $message"

    /** 把 [wallClock]（在 [zone] 里）当成真实时刻，返回它的 epoch。 */
    private fun epochOf(wallClock: String, zone: TimeZone): Long {
        val parts = wallClock.split("-", " ", ":", ".")
        val calendar = Calendar.getInstance(zone)
        calendar.clear()
        calendar.set(
            2026, parts[0].toInt() - 1, parts[1].toInt(),
            parts[2].toInt(), parts[3].toInt(), parts[4].toInt()
        )
        calendar.set(Calendar.MILLISECOND, parts[5].toInt())
        return calendar.timeInMillis
    }

    /** 写一个日志文件，并把 mtime 设成它最后一行的真实写入时刻。 */
    private fun logFile(lines: List<String>, mtimeMillis: Long): File =
        File(dir, "app-log-20260615.txt").apply {
            writeText(lines.joinToString("\n") + "\n")
            setLastModified(mtimeMillis)
        }

    @Test
    fun historyWrittenInAnotherZoneIsMovedOntoTheCurrentTimeline() {
        val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
        val writtenAt = epochOf("06-15 10:30:00.000", shanghai)
        val lines = listOf(
            line("06-15 10:29:58.000", "first"),
            line("06-15 10:30:00.000", "last")
        )
        val groups = listOf(LogFileManager.LogFileContent(logFile(lines, writtenAt), lines))

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val normalized = normalizePersistedWallClock(groups, writtenAt + 60_000)

        // UTC+8 的 10:30 就是 UTC 的 02:30，历史按 UTC 重新渲染。
        assertEquals(
            listOf(line("06-15 02:29:58.000", "first"), line("06-15 02:30:00.000", "last")),
            normalized
        )
    }

    @Test
    fun historyWrittenInTheCurrentZoneIsLeftUntouched() {
        val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
        val writtenAt = epochOf("06-15 10:30:00.000", shanghai)
        val lines = listOf(line("06-15 10:30:00.000"))
        val groups = listOf(LogFileManager.LogFileContent(logFile(lines, writtenAt), lines))

        TimeZone.setDefault(shanghai)

        assertEquals(lines, normalizePersistedWallClock(groups, writtenAt + 60_000))
    }

    /**
     * mtime 被外部改过（文件被拷进来、整目录恢复）时反推不出真实偏移：必须原样返回，
     * 不能按一个凭空猜出来的偏移把整份历史挪走。
     */
    @Test
    fun anImplausibleModificationTimeLeavesTheHistoryAlone() {
        val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
        val writtenAt = epochOf("06-15 10:30:00.000", shanghai)
        val lines = listOf(line("06-15 10:30:00.000"))
        // 偏移会反推成 +11 分钟——不是任何真实时区，判定为不可信。
        val groups = listOf(
            LogFileManager.LogFileContent(logFile(lines, writtenAt + 11 * 60_000), lines)
        )

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        assertEquals(lines, normalizePersistedWallClock(groups, writtenAt + 60_000))
    }

    @Test
    fun linesWithoutAThreadtimePrefixAreCopiedVerbatim() {
        val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
        val writtenAt = epochOf("06-15 10:30:00.000", shanghai)
        val lines = listOf("--------- beginning of main", line("06-15 10:30:00.000"))
        val groups = listOf(LogFileManager.LogFileContent(logFile(lines, writtenAt), lines))

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val normalized = normalizePersistedWallClock(groups, writtenAt + 60_000)

        assertEquals("--------- beginning of main", normalized.first())
        assertEquals(line("06-15 02:30:00.000"), normalized.last())
        assertEquals(2, normalized.size)
    }
}
