/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 管理应用日志文件的存储、滚动和清理
 *
 * 日志文件存储在 noBackupFilesDir/logs/ 目录下，不会被系统备份。
 * 文件命名格式：
 * - app-log-yyyyMMdd[@±分钟].txt
 * - app-log-yyyyMMdd-序号[@±分钟].txt
 *
 * 名字里的 @±分钟 段是这份文件写入时相对 UTC 的时区偏移。threadtime 行只有当地墙上时间，
 * 读取端要把它还原成 epoch 就必须知道写入偏移；写进文件名以后，偏移随文件一起创建、轮转
 * 和删除，不需要额外的元数据文件，也不会出现「内容和 mtime 对不上」的反推。
 * 没有这一段的是旧版本留下的文件，读取端才退回按 mtime 反推（见 normalizePersistedWallClock）。
 *
 * 单文件超过大小限制时，自动创建带序号的新文件（app-log-yyyyMMdd-1.txt），序号只增不减。
 *
 * 保留策略：
 * - 保留最近 3 天的日志
 * - 总大小不超过 30 MB
 */
object LogFileManager {
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_PREFIX = "app-log-"
    private const val LOG_FILE_EXTENSION = ".txt"

    /** 文件名里偏移部分的起始字符，例如 app-log-20260615-1@480.txt。 */
    private const val OFFSET_MARK = '@'

    /** 单个日志文件的最大大小（10 MB） */
    private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024

    /** 所有日志文件的总大小限制（30 MB） */
    private const val MAX_TOTAL_SIZE_BYTES = 30 * 1024 * 1024

    /** 保留日志的最长时间（3 天） */
    private const val MAX_AGE_DAYS = 3

    /** 读取一次日志文件最多重试几轮：一直追不上写入器就放弃时间还原，而不是去猜。 */
    private const val READ_ATTEMPTS = 3

    /** 现实中的时区偏移范围（UTC-12 … UTC+14），超出即视为不可信。 */
    private const val MIN_OFFSET_MINUTES = -12 * 60
    private const val MAX_OFFSET_MINUTES = 14 * 60

    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    /**
     * 文件名里隐含的排序键：日期 + 序号，序号 0 表示当天的基准文件。
     *
     * [offsetMinutes] 是写这份文件时的时区偏移，旧版本文件没有这段元数据，为 null。
     *
     * 目录顺序必须由名字决定，不能靠 lastModified：保留策略删掉基准文件后编号文件仍会
     * 继续被写，修改时间的先后就不再等于日志时间的先后。
     */
    private data class LogFileKey(val date: String, val index: Int, val offsetMinutes: Int?)

    /** 当前默认时区在这一刻的偏移（分钟）。 */
    private fun currentOffsetMinutes(epochMillis: Long): Int =
        (TimeZone.getDefault().getOffset(epochMillis) / 60_000.0).roundToInt()

    private fun offsetLabel(offsetMinutes: Int): String =
        (if (offsetMinutes < 0) "-" else "+") + abs(offsetMinutes)

    /**
     * 文件名：[offsetMinutes] 为 null 时是旧版本的格式（没有偏移元数据）。
     *
     * 偏移必须落在文件名里，不能只放在内存或目录级的元数据文件里：同一个目录里可能同时
     * 存在好几个时区留下的文件，每份文件各自记着自己的偏移，删除文件时元数据也跟着走。
     */
    internal fun fileName(date: String, index: Int, offsetMinutes: Int?): String {
        val offset = if (offsetMinutes == null) "" else "$OFFSET_MARK${offsetLabel(offsetMinutes)}"
        return if (index == 0) {
            "$LOG_FILE_PREFIX${date}${offset}${LOG_FILE_EXTENSION}"
        } else {
            "$LOG_FILE_PREFIX${date}-${index}${offset}${LOG_FILE_EXTENSION}"
        }
    }

    private fun fileKey(name: String): LogFileKey? {
        if (!name.startsWith(LOG_FILE_PREFIX) || !name.endsWith(LOG_FILE_EXTENSION)) return null
        var stem = name.substring(LOG_FILE_PREFIX.length, name.length - LOG_FILE_EXTENSION.length)
        val mark = stem.indexOf(OFFSET_MARK)
        val offsetMinutes = if (mark < 0) null else parseOffsetMinutes(stem.substring(mark + 1))
        if (mark >= 0) {
            if (offsetMinutes == null) return null
            stem = stem.substring(0, mark)
        }
        val dash = stem.indexOf('-')
        val date = if (dash < 0) stem else stem.substring(0, dash)
        if (date.length != 8 || !date.all { it.isDigit() }) return null
        if (dash < 0) return LogFileKey(date, 0, offsetMinutes)
        val index = stem.substring(dash + 1).toIntOrNull() ?: return null
        return LogFileKey(date, index, offsetMinutes)
    }

    /** 解析 +480 / -300：不给符号或超出真实时区范围的值都不认。 */
    private fun parseOffsetMinutes(text: String): Int? {
        if (text.length < 2) return null
        val sign = when (text[0]) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val magnitude = text.substring(1).toIntOrNull() ?: return null
        val minutes = sign * magnitude
        return minutes.takeIf { it in MIN_OFFSET_MINUTES..MAX_OFFSET_MINUTES }
    }

    /**
     * 日志目录所在的存储 context。
     *
     * 必须用 device-protected 存储：LogActivity 声明了 directBootAware，而 Direct Boot
     * （首解锁之前）读不到 credential-protected 的 noBackupFilesDir，用默认 context 会让
     * 开机早期整段会话没有文件日志。device-protected 存储首解锁前后都可写，所以只保留
     * 一个目录，历史不会分裂成两份。
     */
    private fun storageContext(context: Context): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { context.createDeviceProtectedStorageContext() }.getOrDefault(context)
        } else {
            context
        }

    /**
     * 获取日志目录，如果不存在则创建
     */
    fun getLogDir(context: Context): File {
        val logDir = File(storageContext(context).noBackupFilesDir, LOG_DIR_NAME)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        return logDir
    }

    /**
     * 获取当前应该写入的日志文件。
     *
     * 当天文件超出大小限制时按序号轮转，轮转必须单调：只在**序号最大**的那个文件上继续
     * 写，写满了才启用下一个序号，绝不回头复用序号更小或不存在的文件。
     *
     * 基准文件（无序号）可能已经被保留策略删掉。此时若又把基准文件重新建出来，新事件就
     * 会落进排在编号文件之前的文件里；等它再写满，搜索又会返回那个没写满的旧编号文件，
     * 把更新的日志追加到旧内容后面，整个目录的时间顺序就错位了。序号只增不减，可以保证
     * 「文件名的顺序就是日志时间的顺序」。
     *
     * 时区改变同样要轮转：旧文件里的墙上时间属于上一个偏移，继续往里写会让同一份文件
     * 混着两种偏移，读取端只能拿末尾那个偏移去解释全部行，旧历史会整体错开几个小时。
     *
     * @param active 调用方仍打开着的文件，可省略：它还是今天的、写着同一个偏移、也没写满
     *              时直接复用，省掉一次目录扫描（每条日志都会调用这里）。
     */
    fun getCurrentLogFile(context: Context, active: File? = null): File {
        val logDir = getLogDir(context)
        val now = System.currentTimeMillis()
        val today = dateFormat.format(Date(now))
        val offsetMinutes = currentOffsetMinutes(now)

        // 快路径：当前文件仍是今天的、也还存在、也没写满、偏移也没变，就不必再看目录。
        // 只有序号最大的文件会被写入，所以它没写满时不可能存在更高的序号。
        if (active != null && active.exists() && active.length() <= MAX_FILE_SIZE_BYTES) {
            val key = fileKey(active.name)
            if (key != null && key.date == today && key.offsetMinutes == offsetMinutes) {
                return active
            }
        }

        val todays = logDir.listFiles()
            ?.mapNotNull { file -> fileKey(file.name)?.takeIf { it.date == today }?.let { file to it } }
            ?.sortedWith(
                compareBy<Pair<File, LogFileKey>> { it.second.index }
                    .thenBy { it.second.offsetMinutes ?: Int.MIN_VALUE }
            )
            ?: emptyList()
        val highest = todays.lastOrNull() ?: return File(logDir, fileName(today, 0, offsetMinutes))

        val full = highest.first.length() > MAX_FILE_SIZE_BYTES
        return if (full || highest.second.offsetMinutes != offsetMinutes) {
            File(logDir, fileName(today, highest.second.index + 1, offsetMinutes))
        } else {
            highest.first
        }
    }

    /**
     * 获取所有日志文件，按日期与序号降序排列（最新的在前）。
     *
     * 顺序由文件名决定而不是修改时间：保留策略可能删掉基准文件、让编号文件继续被写，
     * 修改时间的先后就不再等于日志时间的先后。
     *
     * 同一天可能同时留着旧版本的基准文件和带偏移的新基准文件，两者序号都是 0：偏移只用来
     * 给它们一个确定的先后，不参与「哪个该写着」的判断。
     */
    fun getAllLogFiles(context: Context): List<File> {
        val logDir = getLogDir(context)
        return logDir.listFiles()
            ?.mapNotNull { file -> fileKey(file.name)?.let { it to file } }
            ?.sortedWith(
                compareByDescending<Pair<LogFileKey, File>> { it.first.date }
                    .thenByDescending { it.first.index }
                    .thenByDescending { it.first.offsetMinutes ?: Int.MIN_VALUE }
            )
            ?.map { it.second }
            ?: emptyList()
    }

    /**
     * 清理旧日志文件
     * 1. 删除超过保留期限（3天）的文件
     * 2. 如果总大小超过限制（30MB），删除最旧的文件直到满足限制
     *
     * @param protectedFile 当前仍有 writer 打开的文件，任何情况下都不删：
     * 删掉已 unlink 的文件只会让之后的日志写进黑洞，直到进程重启。
     */
    fun cleanupOldLogs(context: Context, protectedFile: File? = null) {
        val logFiles = getAllLogFiles(context)
        val cutoffTime = System.currentTimeMillis() - (MAX_AGE_DAYS * 24 * 60 * 60 * 1000L)

        // 删除超过保留期限的文件
        logFiles.forEach { file ->
            if (file != protectedFile && file.lastModified() < cutoffTime) {
                file.delete()
            }
        }

        // 如果总大小超过限制，删除最旧的文件
        val remainingFiles = getAllLogFiles(context)
        var totalSize = remainingFiles.sumOf { it.length() }

        for (file in remainingFiles.reversed()) { // 从最旧的开始
            if (totalSize <= MAX_TOTAL_SIZE_BYTES) break
            // 正在写的文件不参与删除，也不把它的体积算作可回收空间
            if (file == protectedFile) continue
            totalSize -= file.length()
            file.delete()
        }
    }

    /**
     * 一个日志文件连同它读出的行，以及还原时间所需的元数据。
     *
     * [offsetMinutes] 来自文件名，是写入时区偏移的权威来源。旧版本文件没有这段元数据，
     * 为 null 时才退回用 [modifiedMillis] 反推。
     *
     * [modifiedMillis] 只在「内容与元数据来自同一份快照」时才有值：读取前后 mtime 与长度
     * 必须一致。写入器一直在追加时为 null，调用方必须放弃反推——拿着一个对不上的 mtime
     * 去算偏移，会把整份历史整体挪走。
     */
    internal data class LogFileContent(
        val file: File,
        val lines: List<String>,
        val offsetMinutes: Int?,
        val modifiedMillis: Long?
    )

    /**
     * 按日期与序号读取所有日志文件，最新的文件最后读取。
     *
     * 保留文件边界：每个文件的写入偏移要单独判断，一份文件里的偏移只对它自己的行负责。
     */
    internal fun readAllLogGroups(context: Context): List<LogFileContent> {
        val logFiles = getAllLogFiles(context).reversed() // 从最旧到最新

        return logFiles.mapNotNull { file ->
            readLogGroup(file, fileKey(file.name)?.offsetMinutes)
        }
    }

    /**
     * 读取一个日志文件，并尽量让内容和元数据来自同一份快照。
     *
     * 旧版本文件的偏移只能靠「最后一行的写入时刻」（也就是 mtime）反推。读取的这段时间里
     * 写入器又追加了一条，mtime 就变成了那条新事件的时刻，按它算出来的偏移会把整份历史
     * 整体挪走——真实时区根本没变也会错移。所以先记下 mtime 和长度，读完再核对一次；
     * 变了就重读，一直在写就返回 modifiedMillis = null，让调用方放弃反推。
     */
    private fun readLogGroup(file: File, offsetMinutes: Int?): LogFileContent? {
        var last: List<String>? = null
        repeat(READ_ATTEMPTS) {
            val before = file.lastModified()
            val beforeLength = file.length()
            val lines = readLines(file) ?: return null
            last = lines
            if (before > 0L && before == file.lastModified() && beforeLength == file.length()) {
                return LogFileContent(file, lines, offsetMinutes, before)
            }
        }
        // 写入器比读取快：不猜时间，宁可让这些行按原样出现
        val lines = last ?: return null
        return LogFileContent(file, lines, offsetMinutes, null)
    }

    private fun readLines(file: File): List<String>? = try {
        file.bufferedReader().use { it.readLines() }
    } catch (e: Exception) {
        // 忽略读取错误，继续读取其他文件
        null
    }

    /**
     * 读取所有日志文件的内容，返回日志行列表
     * 按日期与序号排序，最新的文件最后读取
     */
    fun readAllLogs(context: Context): List<String> =
        readAllLogGroups(context).flatMap { it.lines }
}
