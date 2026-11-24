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

/**
 * 管理应用日志文件的存储、滚动和清理
 *
 * 日志文件存储在 noBackupFilesDir/logs/ 目录下，不会被系统备份。
 * 文件命名格式：app-log-yyyyMMdd.txt（每天一个文件）
 * 单文件超过大小限制时，自动创建带序号的新文件（app-log-yyyyMMdd-1.txt）
 *
 * 保留策略：
 * - 保留最近 3 天的日志
 * - 总大小不超过 30 MB
 */
object LogFileManager {
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_PREFIX = "app-log-"
    private const val LOG_FILE_EXTENSION = ".txt"

    /** 单个日志文件的最大大小（10 MB） */
    private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024

    /** 所有日志文件的总大小限制（30 MB） */
    private const val MAX_TOTAL_SIZE_BYTES = 30 * 1024 * 1024

    /** 保留日志的最长时间（3 天） */
    private const val MAX_AGE_DAYS = 3

    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    /**
     * 文件名里隐含的排序键：日期 + 序号，序号 0 表示当天的基准文件。
     *
     * 目录顺序必须由名字决定，不能靠 lastModified：保留策略删掉基准文件后编号文件仍会
     * 继续被写，修改时间的先后就不再等于日志时间的先后。
     */
    private data class LogFileKey(val date: String, val index: Int)

    private fun fileName(date: String, index: Int): String =
        if (index == 0) "$LOG_FILE_PREFIX$date$LOG_FILE_EXTENSION"
        else "$LOG_FILE_PREFIX$date-$index$LOG_FILE_EXTENSION"

    private fun fileKey(name: String): LogFileKey? {
        if (!name.startsWith(LOG_FILE_PREFIX) || !name.endsWith(LOG_FILE_EXTENSION)) return null
        val stem = name.substring(LOG_FILE_PREFIX.length, name.length - LOG_FILE_EXTENSION.length)
        val dash = stem.indexOf('-')
        val date = if (dash < 0) stem else stem.substring(0, dash)
        if (date.length != 8 || !date.all { it.isDigit() }) return null
        if (dash < 0) return LogFileKey(date, 0)
        val index = stem.substring(dash + 1).toIntOrNull() ?: return null
        return LogFileKey(date, index)
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
     * @param active 调用方仍打开着的文件，可省略：它还是今天的、也没写满时直接复用，
     *              省掉一次目录扫描（每条日志都会调用这里）。
     */
    fun getCurrentLogFile(context: Context, active: File? = null): File {
        val logDir = getLogDir(context)
        val today = dateFormat.format(Date())

        // 快路径：当前文件仍是今天的、也还存在、也没写满，就不必再看目录。
        // 只有序号最大的文件会被写入，所以它没写满时不可能存在更高的序号。
        if (active != null && active.exists() && active.length() <= MAX_FILE_SIZE_BYTES &&
            fileKey(active.name)?.date == today
        ) {
            return active
        }

        val todays = logDir.listFiles()
            ?.mapNotNull { file ->
                fileKey(file.name)?.takeIf { it.date == today }?.let { file to it.index }
            }
            ?.sortedBy { it.second }
            ?: emptyList()
        val highest = todays.lastOrNull() ?: return File(logDir, fileName(today, 0))

        return if (highest.first.length() > MAX_FILE_SIZE_BYTES) {
            File(logDir, fileName(today, highest.second + 1))
        } else {
            highest.first
        }
    }

    /**
     * 获取所有日志文件，按日期与序号降序排列（最新的在前）。
     *
     * 顺序由文件名决定而不是修改时间：保留策略可能删掉基准文件、让编号文件继续被写，
     * 修改时间的先后就不再等于日志时间的先后。
     */
    fun getAllLogFiles(context: Context): List<File> {
        val logDir = getLogDir(context)
        return logDir.listFiles()
            ?.mapNotNull { file -> fileKey(file.name)?.let { it to file } }
            ?.sortedWith(
                compareByDescending<Pair<LogFileKey, File>> { it.first.date }
                    .thenByDescending { it.first.index }
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
     * 读取所有日志文件的内容，返回日志行列表
     * 按日期与序号排序，最新的文件最后读取
     */
    fun readAllLogs(context: Context): List<String> {
        val logFiles = getAllLogFiles(context).reversed() // 从最旧到最新
        val lines = mutableListOf<String>()

        logFiles.forEach { file ->
            try {
                file.bufferedReader().use { reader ->
                    lines.addAll(reader.readLines())
                }
            } catch (e: Exception) {
                // 忽略读取错误，继续读取其他文件
            }
        }

        return lines
    }
}
