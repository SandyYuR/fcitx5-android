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
     * 获取当前应该写入的日志文件
     * 如果当前日期的文件已超过大小限制，返回带序号的新文件
     */
    fun getCurrentLogFile(context: Context): File {
        val logDir = getLogDir(context)
        val today = dateFormat.format(Date())
        val logFile = File(logDir, "${LOG_FILE_PREFIX}${today}${LOG_FILE_EXTENSION}")
        
        // 如果文件超过大小限制，创建新文件（带序号）
        if (logFile.exists() && logFile.length() > MAX_FILE_SIZE_BYTES) {
            var index = 1
            var newFile: File
            do {
                newFile = File(logDir, "${LOG_FILE_PREFIX}${today}-${index}${LOG_FILE_EXTENSION}")
                index++
            } while (newFile.exists() && newFile.length() > MAX_FILE_SIZE_BYTES)
            return newFile
        }
        
        return logFile
    }
    
    /**
     * 获取所有日志文件，按修改时间降序排列（最新的在前）
     */
    fun getAllLogFiles(context: Context): List<File> {
        val logDir = getLogDir(context)
        return logDir.listFiles { file ->
            file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_EXTENSION)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
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
     * 按文件修改时间排序，最新的文件最后读取
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
