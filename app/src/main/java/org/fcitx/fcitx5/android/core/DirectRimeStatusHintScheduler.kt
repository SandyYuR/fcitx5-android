/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Transient hint state and timers stay on the backend's existing native owner. */
internal class DirectRimeStatusHintScheduler(
    private val scope: CoroutineScope,
    private val uptimeMillis: () -> Long,
    private val canPresent: (Long) -> Boolean,
    private val expirePresentation: (Long) -> Unit,
) {
    private val lifetime = StatusHintLifetime()
    private var job: Job? = null
    private var generation = 0L
    var text = ""
        private set
    val id get() = lifetime.id

    fun clear() {
        job?.cancel()
        job = null
        text = ""
        lifetime.clear()
        generation = 0
    }

    fun show(generation: Long, text: String) {
        clear()
        this.text = text
        this.generation = generation
        val id = lifetime.start(uptimeMillis())
        scheduleExpiry(generation, id)
    }

    fun presented(hintId: Long, frameUptimeMillis: Long) {
        if (canPresent(generation) &&
            lifetime.presented(hintId, frameUptimeMillis.coerceAtMost(uptimeMillis()))) {
            scheduleExpiry(generation, hintId)
        }
    }

    private fun scheduleExpiry(generation: Long, hintId: Long) {
        job?.cancel()
        job = scope.launch {
            delay(lifetime.remaining(uptimeMillis()))
            if (lifetime.id != hintId) return@launch
            job = null
            text = ""
            lifetime.clear()
            if (!canPresent(generation)) return@launch
            // The callback only expires the panel; it never refreshes native state or commits.
            expirePresentation(generation)
        }
    }
}
