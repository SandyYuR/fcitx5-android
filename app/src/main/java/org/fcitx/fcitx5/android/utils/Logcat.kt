/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import timber.log.Timber

class Logcat(
    val pid: Int? = Process.myPid(),
    private val createProcess: (Array<String>) -> java.lang.Process = { Runtime.getRuntime().exec(it) }
) : CoroutineScope by CoroutineScope(Dispatchers.IO) {

    private val processLock = Any()
    private var generation = 0L
    private var process: java.lang.Process? = null
    private var emittingJob: Job? = null

    private val flow: MutableSharedFlow<String> = MutableSharedFlow()

    /**
     * Subscribe to this flow to receive log in app
     * Nothing would be emitted until [initLogFlow] was called
     */
    val logFlow: SharedFlow<String> by lazy { flow.asSharedFlow() }

    /**
     * Get a snapshot of logcat
     */
    fun getLogAsync(): Deferred<Result<List<String>>> = async {
        runCatching {
            val snapshotProcess = Runtime.getRuntime().exec(logcatArgv("-d"))
            try {
                snapshotProcess.inputStream.bufferedReader().use { it.readLines() }
            } finally {
                // Reap the child and close its pipes; otherwise the process object and its
                // three file descriptors linger until GC.
                runCatching { snapshotProcess.waitFor() }
                snapshotProcess.destroy()
            }
        }
    }

    /**
     * Clear logcat
     */
    fun clearLog(): Job =
        launch {
            runCatching {
                val clearProcess = Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
                try {
                    clearProcess.waitFor()
                } finally {
                    clearProcess.destroy()
                }
            }
        }

    /**
     * Create a process reading logcat, sending lines to [logFlow]
     */
    fun initLogFlow(): Job = synchronized(processLock) {
        if (emittingJob?.isActive == true) errorState(R.string.exception_logcat_created)
        val expected = ++generation
        launch(start = CoroutineStart.LAZY) {
            var child: java.lang.Process? = null
            try {
                coroutineContext.ensureActive()
                val created = createProcess(logcatArgv())
                child = created
                synchronized(processLock) {
                    // shutdown can win while Runtime.exec is still creating the child.
                    if (expected != generation) throw CancellationException("Log reader stopped")
                    process = created
                }
                val reader = created.inputStream.bufferedReader()
                try {
                    reader.lineSequence().asFlow().cancellable().collect { flow.emit(it) }
                } finally {
                    created.destroy()
                    reader.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Logcat reader failed")
            } finally {
                // Old readers cannot clear or destroy a newer reader's process.
                synchronized(processLock) {
                    if (process === child) process = null
                    if (expected == generation) emittingJob = null
                }
                child?.destroy()
            }
        }.also { emittingJob = it; it.start() }
    }

    /**
     * Destroy the reading process
     */
    fun shutdownLogFlow() {
        val (current, job) = synchronized(processLock) {
            ++generation
            (process to emittingJob).also { process = null; emittingJob = null }
        }
        job?.cancel()
        // Kill before closing a pipe that may have a blocking read in progress.
        // Process.destroy/stream cleanup must not block the UI detaching this view.
        if (current != null) launch { current.destroy() }
    }

    /**
     * Build the logcat argv, omitting `--pid` when [pid] is null instead of passing an
     * empty string as a bogus argument.
     */
    private fun logcatArgv(vararg extraArgs: String): Array<String> = buildList {
        add("logcat")
        pid?.let { add("--pid=$it") }
        add("-v")
        add("threadtime")
        addAll(extraArgs)
    }.toTypedArray()

    companion object {
        val default by lazy { Logcat() }
    }
}
