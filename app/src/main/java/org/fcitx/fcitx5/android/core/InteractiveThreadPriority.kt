/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.os.Process
import timber.log.Timber

/** Raise only synchronous editor work; deployment, sync and candidate scans keep normal priority. */
internal class InteractiveThreadPriority {
    var lastPriority = Process.THREAD_PRIORITY_DEFAULT
        private set
    private var available = true

    fun <T> run(block: () -> T): T {
        if (!available) return block()
        val previous: Int
        try {
            previous = Process.getThreadPriority(Process.myTid())
            if (previous > Process.THREAD_PRIORITY_DISPLAY) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            }
            lastPriority = minOf(previous, Process.THREAD_PRIORITY_DISPLAY)
        } catch (e: RuntimeException) {
            // A vendor's priority policy must never reject input or repeatedly spam logs.
            available = false
            Timber.w(e, "Cannot raise Rime input thread priority")
            return block()
        }
        return try {
            block()
        } finally {
            if (previous > Process.THREAD_PRIORITY_DISPLAY) {
                try {
                    Process.setThreadPriority(previous)
                } catch (e: RuntimeException) {
                    available = false
                    Timber.w(e, "Cannot restore Rime thread priority")
                }
            }
        }
    }
}
