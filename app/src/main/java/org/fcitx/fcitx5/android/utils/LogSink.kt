/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * 应用日志写入器，将日志异步写入文件
 * 
 * 日志格式模拟 logcat threadtime：
 * MM-DD HH:mm:ss.SSS  PID  TID LEVEL TAG: message
 * 
 * 特点：
 * - 异步写入，不阻塞调用线程
 * - 线程安全（使用单线程 Executor）
 * - 自动切换文件（当日期变化或文件超过大小限制）
 * - 批量写入，性能优化
 * 
 * 使用方式：
 * 1. 在 Application.onCreate() 中调用 init(context)
 * 2. 在 Logx 中集成调用 log() 方法
 * 3. 应用关闭时调用 close()（可选）
 */
object LogSink {
    private var initialized = false
    private lateinit var context: Context
    
    /** 单线程 Executor 保证写入操作串行执行 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LogSink-Writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    
    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    
    /**
     * 初始化日志写入器
     * 必须在使用前调用，建议在 Application.onCreate() 中调用
     */
    fun init(context: Context) {
        this.context = context.applicationContext
        initialized = true
        
        // 启动时清理旧日志
        executor.execute {
            try {
                LogFileManager.cleanupOldLogs(context)
            } catch (e: Exception) {
                // 忽略清理错误
            }
        }
    }
    
    /**
     * 写入一条日志
     * 
     * @param level 日志级别字符（V/D/I/W/E/F）
     * @param tag 日志标签
     * @param message 日志消息
     * @param throwable 可选的异常对象，会写入堆栈信息
     */
    fun log(level: Char, tag: String, message: String, throwable: Throwable? = null) {
        if (!initialized) return
        
        executor.execute {
            try {
                ensureWriter()
                
                // 写入日志行
                val line = formatLogLine(level, tag, message)
                writer?.write(line)
                writer?.newLine()
                
                // 如果有异常，写入堆栈
                throwable?.let {
                    val stackTrace = it.stackTraceToString()
                    writer?.write(stackTrace)
                    writer?.newLine()
                }
                
                // 定期 flush（每次写入后）
                writer?.flush()
            } catch (e: Exception) {
                // 忽略写入错误，避免日志写入失败导致应用崩溃
            }
        }
    }
    
    /**
     * 确保 writer 可用，如果需要则切换到新文件
     */
    private fun ensureWriter() {
        val file = LogFileManager.getCurrentLogFile(context)
        
        // 如果文件变化了（例如新的一天或文件超过大小限制），关闭旧 writer
        if (file != currentFile) {
            writer?.close()
            writer = file.bufferedWriter(Charsets.UTF_8, bufferSize = 8192).apply {
                // append mode
                val append = file.exists()
                if (!append) {
                    file.createNewFile()
                }
            }
            currentFile = file
        }
    }
    
    /**
     * 格式化日志行，模拟 logcat threadtime 格式
     * 格式：MM-DD HH:mm:ss.SSS  PID  TID LEVEL TAG: message
     */
    private fun formatLogLine(level: Char, tag: String, message: String): String {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val millis = calendar.get(Calendar.MILLISECOND)
        
        val pid = android.os.Process.myPid()
        val tid = android.os.Process.myTid()
        
        // 格式：MM-DD HH:mm:ss.SSS  PID  TID LEVEL TAG: message
        return String.format(
            "%02d-%02d %02d:%02d:%02d.%03d %5d %5d %c %s: %s",
            month, day, hour, minute, second, millis,
            pid, tid, level, tag, message
        )
    }
    
    /**
     * 强制刷新缓冲区
     * 一般不需要手动调用，每次写入后会自动 flush
     */
    fun flush() {
        executor.execute {
            try {
                writer?.flush()
            } catch (e: Exception) {
                // 忽略错误
            }
        }
    }
    
    /**
     * 关闭日志写入器
     * 一般不需要手动调用，系统会在进程结束时自动清理
     */
    fun close() {
        executor.execute {
            try {
                writer?.close()
                writer = null
                currentFile = null
            } catch (e: Exception) {
                // 忽略错误
            }
        }
        executor.shutdown()
    }
}
