/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import java.util.concurrent.atomic.AtomicLong

/** Owner-thread state: keep an unseen hint bounded, then time its visible lifetime from presentation. */
internal class StatusHintLifetime {
    companion object { private val sequence = AtomicLong() }
    var id = 0L
        private set
    private var deadline = 0L
    private var startedAt = 0L
    private var presented = false

    fun start(now: Long): Long {
        id = sequence.incrementAndGet()
        presented = false
        startedAt = now
        deadline = now + 10_000
        return id
    }

    fun presented(hintId: Long, frameUptime: Long): Boolean {
        if (id == 0L || id != hintId || presented || frameUptime < startedAt || frameUptime >= deadline) return false
        presented = true
        deadline = frameUptime + 1500
        return true
    }

    fun remaining(now: Long) = if (id == 0L) 0L else (deadline - now).coerceAtLeast(0)
    fun clear() { id = 0; deadline = 0; presented = false }
}
