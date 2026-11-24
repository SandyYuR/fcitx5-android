/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CancellationException
import org.fcitx.fcitx5.android.utils.LogTag
import org.fcitx.fcitx5.android.utils.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** Warms read-only file pages outside the interpreter and input queues. */
internal class DirectRimeAssetPrewarmer(private val context: Context, private val scope: CoroutineScope) {
    data class Assets(val profile: File, val shared: File, val relativePaths: Array<String>)

    private val io = Dispatchers.IO.limitedParallelism(1)
    private var visible = false
    private var typing = false
    private var task: Job? = null

    @Synchronized fun setVisible(value: Boolean): Boolean {
        if (visible == value) return false
        visible = value
        typing = false
        invalidate()
        return value
    }

    @Synchronized fun inputStarted() {
        if (!visible || typing) return
        typing = true
        invalidate()
    }

    @Synchronized fun invalidate() {
        task?.cancel()
        task = null
    }

    @Synchronized fun request(target: () -> Assets?) {
        if (!visible || typing || task != null) return
        // Resolve the active schema on the caller's native owner after the triggering publication.
        task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(START_DELAY_MS)
                val assets = target() ?: return@launch
                withContext(io) { warm(assets, coroutineContext) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logx.d(LogTag.RimeAssetPrewarm, "Optional asset prewarm stopped", e)
            }
        }.also { it.start() }
    }

    private fun budget(): Long {
        val activity = context.getSystemService(ActivityManager::class.java) ?: return 0
        val power = context.getSystemService(PowerManager::class.java) ?: return 0
        if (activity.isLowRamDevice || power.isPowerSaveMode ||
            Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return 0
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        if (memory.lowMemory) return 0
        return (memory.availMem - maxOf(memory.threshold, memory.totalMem / 8)).coerceIn(0, MAX_BYTES)
    }

    private fun warm(assets: Assets, jobContext: CoroutineContext) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        var remaining = budget()
        if (remaining <= 0) return
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
        if (pageSize <= 0) return
        val buffer by lazy { ByteArray(CHUNK_BYTES) }
        var read = 0L
        val priority = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull()
        if (priority != null && priority < Process.THREAD_PRIORITY_BACKGROUND) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        }
        try {
            for (path in assets.relativePaths) {
                jobContext.ensureActive()
                val local = assets.profile.resolve(path)
                val file = if (local.exists()) local else assets.shared.resolve(path)
                try {
                    val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or
                        OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
                    try {
                        val stat = Os.fstat(fd)
                        val size = stat.st_size
                        if (!OsConstants.S_ISREG(stat.st_mode) || size <= 0 || size > MAX_BYTES) continue
                        val pages = ByteArray(((size + pageSize - 1) / pageSize).toInt())
                        val map = Os.mmap(0, size, OsConstants.PROT_NONE, OsConstants.MAP_SHARED, fd, 0)
                        try { Os.mincore(map, size, pages) } finally { Os.munmap(map, size) }
                        // A large model has hot ranges throughout the file. Leave the whole file
                        // alone if there is insufficient headroom to read its missing pages.
                        val missing = pages.count { it.toInt() and 1 == 0 }.toLong() * pageSize
                        if (missing == 0L || missing > remaining || missing > budget()) continue
                        var page = 0
                        while (page < pages.size) {
                            jobContext.ensureActive()
                            if (pages[page].toInt() and 1 != 0) { page++; continue }
                            val start = page++
                            while (page < pages.size && pages[page].toInt() and 1 == 0 &&
                                (page - start) * pageSize < CHUNK_BYTES) page++
                            val offset = start.toLong() * pageSize
                            val count = minOf(size - offset, (page - start).toLong() * pageSize).toInt()
                            val countRead = Os.pread(fd, buffer, 0, count, offset)
                            read += countRead
                            remaining -= countRead
                            if (countRead != count) break
                            // Check device pressure periodically without a memory service call per chunk.
                            if (read % PRESSURE_CHECK_BYTES < countRead && budget() < pageSize) return
                        }
                    } finally { Os.close(fd) }
                } catch (_: ErrnoException) {
                    // A missing, changed, or unsupported file is left to the normal engine path.
                } catch (_: IOException) {
                    // An interrupted read must not affect the keyboard.
                }
            }
        } finally {
            if (priority != null && priority < Process.THREAD_PRIORITY_BACKGROUND) {
                runCatching { Process.setThreadPriority(priority) }
            }
            if (read > 0) Logx.i(LogTag.RimeAssetPrewarm, "bytes=$read cancelled=${!jobContext.isActive}")
        }
    }

    private companion object {
        const val START_DELAY_MS = 200L
        const val MAX_BYTES = 512L * 1024 * 1024
        const val CHUNK_BYTES = 1024 * 1024
        const val PRESSURE_CHECK_BYTES = 32L * 1024 * 1024
    }
}
