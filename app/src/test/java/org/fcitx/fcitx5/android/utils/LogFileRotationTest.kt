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

/**
 * 日志文件的轮转与目录顺序。
 *
 * 两者必须只由文件名决定：保留策略会在编号文件仍被写入时删掉当天的基准文件，
 * 一旦轮转回头复用基准文件、或者目录顺序还按 lastModified 排，日志时间线就会错位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogFileRotationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    private val dir: File get() = LogFileManager.getLogDir(context)

    private fun base() = File(dir, "app-log-$today.txt")
    private fun numbered(index: Int) = File(dir, "app-log-$today-$index.txt")

    /** 只改长度不写数据：RandomAccessFile.setLength 在 NTFS 上是稀疏扩展，瞬间完成。 */
    private fun grow(file: File, size: Long) {
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    /** 刚好超过单文件上限，下一次调用必须轮转。 */
    private fun fillToLimit(file: File) = grow(file, MAX_FILE_SIZE_BYTES + 1)

    @Test
    fun aFullBaseFileRotatesToTheFirstNumberedFile() {
        fillToLimit(base())
        assertEquals(numbered(1).name, LogFileManager.getCurrentLogFile(context).name)
    }

    @Test
    fun rotationKeepsMovingForwardInsteadOfReusingEarlierIndexes() {
        fillToLimit(base())
        assertEquals(numbered(1).name, LogFileManager.getCurrentLogFile(context).name)
        fillToLimit(numbered(1))
        assertEquals(numbered(2).name, LogFileManager.getCurrentLogFile(context).name)
    }

    /**
     * 回归：清理在编号文件仍是活动文件时删掉了基准文件。以前会先把基准文件重新建出来，
     * 新事件因此排到编号文件之前；等新基准写满，搜索又会回头返回那个没写满的旧编号文件，
     * 把更新的日志追加到旧内容后面。
     */
    @Test
    fun aRetentionDeletedBaseFileIsNotRecreatedWhileANumberedFileIsActive() {
        fillToLimit(base())
        val active = LogFileManager.getCurrentLogFile(context)
        assertEquals(numbered(1).name, active.name)
        active.appendText("still being written\n")

        assertTrue(base().delete())

        assertEquals(numbered(1).name, LogFileManager.getCurrentLogFile(context).name)
        // writer 记着自己的活动文件，快路径也必须留在它上面，不能改去写新建的基准文件。
        assertEquals(numbered(1).name, LogFileManager.getCurrentLogFile(context, active).name)
        assertFalse("Base file must not be resurrected", base().exists())
    }

    /**
     * 同样的错位也会出现在旧版本留下的目录里：基准文件小而编号文件更大。
     * 活动的必须是序号最大的那个，否则新事件会写进排在它前面的文件。
     */
    @Test
    fun theHighestNumberedFileKeepsReceivingEventsEvenWhenTheBaseFileIsSmall() {
        base().appendText("stale, was recreated by the older rotation\n")
        numbered(1).appendText("older session\n")
        assertEquals(numbered(1).name, LogFileManager.getCurrentLogFile(context).name)
    }

    @Test
    fun filesAreOrderedByNameNotByModificationTime() {
        base().appendText("day start\n")
        numbered(1).appendText("later\n")
        numbered(2).appendText("latest\n")
        // 保留策略删掉基准文件后编号文件继续被写，修改时间与日志时间就不再同序。
        assertTrue(base().setLastModified(System.currentTimeMillis()))
        assertTrue(numbered(1).setLastModified(System.currentTimeMillis() - 2_000))
        assertTrue(numbered(2).setLastModified(System.currentTimeMillis() - 4_000))

        assertEquals(
            listOf(numbered(2).name, numbered(1).name, base().name),
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
        assertEquals(base().name, LogFileManager.getCurrentLogFile(context).name)
    }

    private companion object {
        /** 与 LogFileManager.MAX_FILE_SIZE_BYTES 一致：单个日志文件的 10 MB 上限。 */
        const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024
    }
}
