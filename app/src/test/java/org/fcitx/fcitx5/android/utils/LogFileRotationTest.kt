/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * 日志文件的轮转与目录顺序。
 *
 * 两者必须只由文件名决定：保留策略会在编号文件仍被写入时删掉当天的基准文件，
 * 一旦轮转回头复用基准文件、或者目录顺序还按 lastModified 排，日志时间线就会错位。
 *
 * 文件名还带着写入时的时区偏移（见 [LogFileManager.fileName]）：同一份文件里只能有一种
 * 偏移，时区变了就必须换一个文件，否则读取端只能拿末尾那个偏移去解释全部历史。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogFileRotationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    private val dir: File get() = LogFileManager.getLogDir(context)

    /** 当前默认时区的偏移，新文件一律按它命名。 */
    private val currentOffsetMinutes: Int
        get() = (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000.0).roundToInt()

    private fun todayFile(index: Int, offsetMinutes: Int = currentOffsetMinutes) =
        File(dir, LogFileManager.fileName(today, index, offsetMinutes))

    /** 旧版本留下的文件：名字里没有偏移。 */
    private fun legacyFile(index: Int) =
        File(dir, if (index == 0) "app-log-$today.txt" else "app-log-$today-$index.txt")

    /** 只改长度不写数据：RandomAccessFile.setLength 在 NTFS 上是稀疏扩展，瞬间完成。 */
    private fun grow(file: File, size: Long) {
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    /** 刚好超过单文件上限，下一次调用必须轮转。 */
    private fun fillToLimit(file: File) = grow(file, MAX_FILE_SIZE_BYTES + 1)

    @Test
    fun aFullBaseFileRotatesToTheFirstNumberedFile() {
        fillToLimit(todayFile(0))
        assertEquals(todayFile(1).name, LogFileManager.getCurrentLogFile(context).name)
    }

    @Test
    fun rotationKeepsMovingForwardInsteadOfReusingEarlierIndexes() {
        fillToLimit(todayFile(0))
        val first = LogFileManager.getCurrentLogFile(context)
        assertEquals(todayFile(1).name, first.name)

        fillToLimit(first)
        assertEquals(todayFile(2).name, LogFileManager.getCurrentLogFile(context).name)
    }

    /**
     * 回归：清理在编号文件仍是活动文件时删掉了基准文件。以前会先把基准文件重新建出来，
     * 新事件因此排到编号文件之前；等新基准写满，搜索又会回头返回那个没写满的旧编号文件，
     * 把更新的日志追加到旧内容后面。
     */
    @Test
    fun aRetentionDeletedBaseFileIsNotRecreatedWhileANumberedFileIsActive() {
        fillToLimit(todayFile(0))
        val active = LogFileManager.getCurrentLogFile(context)
        assertEquals(todayFile(1).name, active.name)
        active.appendText("still being written\n")

        assertTrue(todayFile(0).delete())

        assertEquals(todayFile(1).name, LogFileManager.getCurrentLogFile(context).name)
        // writer 记着自己的活动文件，快路径也必须留在它上面，不能改去写新建的基准文件。
        assertEquals(todayFile(1).name, LogFileManager.getCurrentLogFile(context, active).name)
        assertFalse("Base file must not be resurrected", todayFile(0).exists())
    }

    /**
     * 同样的错位也会出现在旧版本留下的目录里：基准文件小而编号文件更大。
     * 活动的必须是序号最大的那个，否则新事件会写进排在它前面的文件。
     */
    @Test
    fun theHighestNumberedFileKeepsReceivingEventsEvenWhenTheBaseFileIsSmall() {
        todayFile(0).appendText("stale, was recreated by the older rotation\n")
        todayFile(1).appendText("older session\n")
        assertEquals(todayFile(1).name, LogFileManager.getCurrentLogFile(context).name)
    }

    @Test
    fun filesAreOrderedByNameNotByModificationTime() {
        legacyFile(0).appendText("day start\n")
        legacyFile(1).appendText("later\n")
        legacyFile(2).appendText("latest\n")
        // 保留策略删掉基准文件后编号文件继续被写，修改时间与日志时间就不再同序。
        assertTrue(legacyFile(0).setLastModified(System.currentTimeMillis()))
        assertTrue(legacyFile(1).setLastModified(System.currentTimeMillis() - 2_000))
        assertTrue(legacyFile(2).setLastModified(System.currentTimeMillis() - 4_000))

        assertEquals(
            listOf(legacyFile(2).name, legacyFile(1).name, legacyFile(0).name),
            LogFileManager.getAllLogFiles(context).map { it.name }
        )
        assertEquals(
            listOf("day start", "later", "latest"),
            LogFileManager.readAllLogs(context).map { it.trim() }
        )
    }

    @Test
    fun filesFromOtherDaysAreNotMistakenForTodaysRotation() {
        File(dir, "app-log-19990101.txt").appendText("ancient\n")
        File(dir, "app-log-19990101-3.txt").appendText("ancient, rotated\n")
        assertEquals(todayFile(0).name, LogFileManager.getCurrentLogFile(context).name)
    }

    /** 时区没变时不能无谓地换文件：同一天同一偏移的活动文件要一直被复用。 */
    @Test
    fun todaysFileWithTheCurrentOffsetKeepsReceivingEvents() {
        todayFile(0).appendText("first\n")
        assertEquals(todayFile(0).name, LogFileManager.getCurrentLogFile(context).name)
        assertEquals(todayFile(0).name, LogFileManager.getCurrentLogFile(context, todayFile(0)).name)
    }

    /**
     * 时区改过之后，旧文件里的行属于上一个偏移。继续往里写，读取端只能拿末尾那个偏移
     * 解释全部行，旧历史会整体错开几个小时——所以即使没写满也要换文件。
     */
    @Test
    fun aFileWrittenInAnotherOffsetIsNotAppendedToEvenWhenItIsNotFull() {
        val otherOffset = if (currentOffsetMinutes == 0) 60 else 0
        val file = todayFile(0, otherOffset).apply { appendText("written in another zone\n") }

        val current = LogFileManager.getCurrentLogFile(context, file)
        assertEquals(todayFile(1).name, current.name)
        assertFalse(current.exists())
    }

    /**
     * 升级后的第一件事：旧版本文件没有偏移元数据，新事件必须落进一个带偏移的新文件，
     * 旧文件原样留着，交给读取端按 mtime 反推。
     */
    @Test
    fun aLegacyFileIsRotatedSoNewEventsCarryADefiniteOffset() {
        legacyFile(0).appendText("from an older version\n")
        assertEquals(todayFile(1).name, LogFileManager.getCurrentLogFile(context).name)
        assertEquals("from an older version\n", legacyFile(0).readText())
    }

    private companion object {
        /** 与 LogFileManager.MAX_FILE_SIZE_BYTES 一致：单个日志文件的 10 MB 上限。 */
        const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024
    }
}
