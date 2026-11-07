/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.stats

/** Null delay means an indefinite channel receive, with no timer or periodic wake-up. */
internal data class StatsActorWakeup(val delayMillis: Long?, val flush: Boolean = false, val finishSession: Boolean = false)

internal object StatsActorSchedule {
    const val FLUSH_DELAY_MILLIS = 1000L
    const val IDLE_SESSION_MILLIS = 5000L

    fun next(now: Long, enabled: Boolean, dirtySince: Long?, idleSessionSince: Long?, commandReady: Boolean = false): StatsActorWakeup {
        if (!enabled) return StatsActorWakeup(null)
        val flushAt = dirtySince?.let { deadline(it, FLUSH_DELAY_MILLIS) }
        // Input segmentation uses a strict > 5 s gap, including integer-millisecond boundaries.
        val sessionAt = idleSessionSince?.takeUnless { commandReady }?.let { deadline(it, IDLE_SESSION_MILLIS + 1) }
        val earliest = when {
            flushAt == null -> sessionAt
            sessionAt == null -> flushAt
            else -> minOf(flushAt, sessionAt)
        } ?: return StatsActorWakeup(null)
        return StatsActorWakeup(if (earliest <= now) 0 else earliest - now,
            flush = flushAt != null && now >= flushAt,
            finishSession = sessionAt != null && now >= sessionAt)
    }

    private fun deadline(since: Long, delay: Long): Long = if (since > Long.MAX_VALUE - delay) Long.MAX_VALUE else since + delay
}
