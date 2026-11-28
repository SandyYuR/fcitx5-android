/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 应用日志写入器，将日志异步写入文件
 *
 * 日志格式模拟 logcat threadtime：
 * MM-DD HH:mm:ss.SSS  PID  TID LEVEL TAG: message
 *
 * 特点：
 * - 普通日志异步写入；错误日志在队列满时背压，避免静默丢失
 * - 有界队列与单个排空任务保持写入顺序
 * - 自动切换文件（当日期变化或文件超过大小限制）
 * - 批量写入，性能优化
 *
 * 使用方式：
 * 1. 在 Application.onCreate() 中调用 init(context)
 * 2. 在 Logx 中集成调用 log() 方法
 * 3. 应用关闭时调用 close()（可选）
 */
object LogSink {
    @Volatile
    private var initialized = false
    private lateinit var context: Context

    /** 单线程 Executor 保证写入操作串行执行 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LogSink-Writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private val pending = LogSinkWriteQueue(
        executor = executor,
        flush = { writer?.flush() },
        reportDropped = { count ->
            writeEvent('W', "LogSink", "Dropped $count noncritical log events because the writer queue was full",
                null, System.currentTimeMillis(), android.os.Process.myTid())
        }
    )

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null

    /** 上次执行保留策略的时间；只在 writer 线程上读写 */
    private var lastCleanupAt = 0L

    /** 保留策略的执行间隔：清理原先只在 init 和文件轮转时跑，进程长期存活时会一直不生效 */
    private val cleanupIntervalMs = 5 * 60 * 1000L

    /**
     * 初始化日志写入器
     * 必须在使用前调用，建议在 Application.onCreate() 中调用
     */
    fun init(context: Context) {
        // 这里不强制换成 applicationContext：调用方可能传的是 device-protected context，
        // 而日志目录的存储位置由 LogFileManager 统一决定（Direct Boot 前也可写）。
        this.context = context
        // 先排入清理，再发布初始化状态；保护重复初始化时仍打开的日志文件。
        initialized = pending.control {
            try {
                lastCleanupAt = System.currentTimeMillis()
                LogFileManager.cleanupOldLogs(context, currentFile)
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

        // 立即捕获事件时间和线程 ID（在进入 executor 之前）
        val timestamp = System.currentTimeMillis()
        val tid = android.os.Process.myTid()

        pending.log(
            critical = level == 'E' || level == 'F' || throwable != null,
            characters = tag.length.toLong() + message.length
        ) { writeEvent(level, tag, message, throwable, timestamp, tid) }
    }
    private fun writeEvent(level: Char, tag: String, message: String, throwable: Throwable?, timestamp: Long, tid: Int) {
        ensureWriter()
        for (line in formatLogLines(level, tag, message, throwable, timestamp, tid)) {
            writer?.write(line)
            writer?.newLine()
        }
    }


    /**
     * 确保 writer 可用，如果需要则切换到新文件。
     *
     * [currentFile] 一起传下去：它仍是当天的、也还没写满时可以原样复用，
     * 省掉每条日志一次目录扫描。
     */
    private fun ensureWriter() {
        val file = LogFileManager.getCurrentLogFile(context, currentFile)

        // 如果文件变化了（例如新的一天或文件超过大小限制），关闭旧 writer
        if (file != currentFile) {
            writer?.close()
            writer = FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8)
            currentFile = file
            // 刚轮转出来的文件必须立即参与保留策略，不受节流限制
            lastCleanupAt = 0L
        }

        maybeCleanup(file)
    }

    /**
     * 定期执行保留策略。清理必须跳过正在写的文件，否则会删掉仍被 writer 打开的
     * 目录项，之后的日志全部写进已 unlink 的文件里，直到进程重启或跨天。
     */
    private fun maybeCleanup(activeFile: File) {
        val now = System.currentTimeMillis()
        if (now - lastCleanupAt < cleanupIntervalMs) return
        lastCleanupAt = now
        LogFileManager.cleanupOldLogs(context, activeFile)
    }

    /**
     * 把一条事件渲染成一行或多行 threadtime 记录。
     *
     * 事件里的换行（消息自带的多行文本、Throwable 的堆栈）必须拆成各自独立、各自带前缀的行。
     * 以前堆栈是作为裸行写在事件行之后的：它没有级别和 tag，读取端 [logLevelRegex] 解析不出
     * 级别，于是级别过滤会删掉事件头却留下全部栈帧（无级别行恒通过级别过滤），"rime" 内容
     * 过滤又反过来只留事件头、把诊断帧全删掉——同一个异常被两种过滤切成两半。
     * 逐行带上前缀后，级别、内容、搜索三种过滤对整条异常要么全留要么全删，
     * 导出的文件也真的符合头部声明的 threadtime 格式。
     */
    internal fun formatLogLines(
        level: Char,
        tag: String,
        message: String,
        throwable: Throwable?,
        timestamp: Long,
        tid: Int
    ): List<String> {
        val stack = throwable?.stackTraceToString()?.trimEnd('\n')
        val payload = when {
            stack.isNullOrEmpty() -> message
            message.isEmpty() -> stack
            else -> message + "\n" + stack
        }
        // 热路径上的绝大多数日志是单行：不为了找换行而先切分整个字符串
        if (payload.indexOf('\n') < 0) return listOf(formatLogLine(level, tag, payload, timestamp, tid))
        return payload.split('\n').map { formatLogLine(level, tag, it, timestamp, tid) }
    }

    /**
     * 格式化日志行，模拟 logcat threadtime 格式
     * 格式：MM-DD HH:mm:ss.SSS  PID  TID LEVEL TAG: message
     *
     * 必须固定 Locale.US：默认 locale 使用非 ASCII 数字时（如 ar-EG 的 ٠١٢…），
     * String.format 会把月/日/时分秒/毫秒/PID/TID 换成当地数字，
     * 而读取端 logLevelRegex 只认 ASCII 数字，整批日志会解析不出级别，
     * 进而按 Info 着色并绕过级别过滤。
     */
    internal fun formatLogLine(
        level: Char,
        tag: String,
        message: String,
        timestamp: Long,
        tid: Int
    ): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }

        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val millis = calendar.get(Calendar.MILLISECOND)

        val pid = android.os.Process.myPid()

        // 格式：MM-DD HH:mm:ss.SSS  PID  TID LEVEL TAG: message
        return String.format(
            Locale.US,
            "%02d-%02d %02d:%02d:%02d.%03d %5d %5d %c %s: %s",
            month, day, hour, minute, second, millis,
            pid, tid, level, tag, message
        )
    }

    /**
     * 清空全部持久日志，并在文件真正删除之后才返回。
     *
     * 必须在 writer 的线程上串行执行：先关闭并重置 writer，再删文件。调用方若直接删文件，
     * 只会删掉目录项，旧的 BufferedWriter 仍指向那个已 unlink 的 inode，之后所有日志都写进
     * 一个谁也读不到的文件，直到进程重启或跨天换文件。
     *
     * 同样因为删除排在 writer 队列里，本函数返回之前文件可能还在磁盘上：调用方必须等它返回
     * 再去重读历史，否则刚清掉的行会被重新发布到页面和导出里。
     */
    suspend fun clear() {
        if (!initialized) return
        val completed = CompletableDeferred<Unit>()
        try {
            val accepted = pending.control {
                try {
                    try {
                        writer?.close()
                    } catch (e: Exception) {
                        // 忽略关闭错误，文件仍要被删掉
                    }
                    writer = null
                    currentFile = null
                    // 清空后允许立即重新清理
                    lastCleanupAt = 0L
                    try {
                        LogFileManager.getAllLogFiles(context).forEach { it.delete() }
                    } catch (e: Exception) {
                        // 忽略删除错误
                    }
                } finally {
                    // 保证等待方一定被唤醒，任何一个删除异常都不能把调用方挂在这里
                    completed.complete(Unit)
                }
            }
            if (!accepted) return
        } catch (e: RejectedExecutionException) {
            // writer 已 shutdown，队列里不会再有真实工作，等下去没有意义
            return
        }
        completed.await()
    }

    /**
     * 强制刷新缓冲区
     * 每批写入后自动 flush；显式调用在此前已接收的事件之后执行
     */
    fun flush() {
        pending.control {
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
        initialized = false
        pending.close {
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

/**
 * One executor task drains ordered batches. At most [capacity] entries wait behind the active batch;
 * noncritical payloads additionally share a character budget. Errors and control barriers wait for
 * room instead of being dropped, so the input hot path stays nonblocking for ordinary diagnostics.
 * The callbacks run only on the writer and must not recursively enqueue work.
 */
internal class LogSinkWriteQueue(
    private val executor: Executor,
    private val capacity: Int = 256,
    private val maxCharacters: Long = 256 * 1024L,
    private val batchSize: Int = 64,
    private val flush: () -> Unit,
    private val reportDropped: (Long) -> Unit
) {
    private data class Work(val characters: Long, val action: () -> Unit)
    private val lock = java.lang.Object()
    private val queue = ArrayDeque<Work>()
    private var queuedCharacters = 0L
    private var dropped = 0L
    private var scheduled = false
    private var accepting = true

    init {
        require(capacity > 0 && maxCharacters > 0 && batchSize > 0)
    }

    fun log(critical: Boolean, characters: Long, action: () -> Unit): Boolean =
        enqueue(critical, characters, closing = false, action)

    fun control(action: () -> Unit): Boolean = enqueue(true, 0, closing = false, action)

    fun close(action: () -> Unit): Boolean = enqueue(true, 0, closing = true, action)

    private fun enqueue(critical: Boolean, characters: Long, closing: Boolean, action: () -> Unit): Boolean {
        require(characters >= 0)
        var interrupted = false
        try {
            return synchronized(lock) {
                if (!accepting) return@synchronized false
                if (!critical && (queue.size >= capacity || characters > maxCharacters - queuedCharacters)) {
                    if (dropped < Long.MAX_VALUE) dropped++
                    return@synchronized false
                }
                while (queue.size >= capacity && accepting) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        // An error or export/clear boundary must not disappear on cancellation.
                        interrupted = true
                    }
                }
                if (!accepting) return@synchronized false
                queue.addLast(Work(characters, action))
                queuedCharacters += characters
                if (closing) accepting = false
                if (!scheduled) {
                    scheduled = true
                    try {
                        executor.execute { drain() }
                    } catch (_: RejectedExecutionException) {
                        accepting = false
                        scheduled = false
                        queue.clear()
                        queuedCharacters = 0
                        lock.notifyAll()
                        return@synchronized false
                    }
                }
                true
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun drain() {
        while (true) {
            val batch = ArrayList<Work>(batchSize)
            val lost: Long
            synchronized(lock) {
                if (queue.isEmpty() && dropped == 0L) {
                    scheduled = false
                    return
                }
                repeat(minOf(batchSize, queue.size)) {
                    val item = queue.removeFirst()
                    queuedCharacters -= item.characters
                    batch.add(item)
                }
                lost = dropped
                dropped = 0
                lock.notifyAll()
            }
            // Report before barriers: a final close must never be followed by a reopened writer.
            if (lost != 0L) safely { reportDropped(lost) }
            batch.forEach { safely(it.action) }
            safely(flush)
        }
    }

    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            // Logging failures must not kill the drain task or strand control completions.
        }
    }
}
