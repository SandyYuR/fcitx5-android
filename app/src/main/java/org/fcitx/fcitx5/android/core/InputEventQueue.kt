/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Coalesce presentation updates between ordered editor events. Never merge across a commit,
 * forwarded key or lifecycle event. Capacity waits happen before entering the native owner so
 * a collector can still request candidate data while draining the queue.
 */
internal class InputEventQueue(private val highWatermark: Int = 64) {
    private val lock = Any()
    private val batches = ArrayDeque<List<FcitxEvent<*>>>()
    private val available = Channel<Unit>(Channel.CONFLATED)
    private val size = MutableStateFlow(0)

    init { require(highWatermark > 0) }

    suspend fun awaitCapacity() {
        if (size.value >= highWatermark) size.first { it < highWatermark }
    }

    fun offer(batch: List<FcitxEvent<*>>) {
        if (batch.isEmpty()) return
        synchronized(lock) {
            val previous = batches.lastOrNull()
            if (previous != null && batch.all { stateKey(it) != null } &&
                previous.all { stateKey(it) != null }) {
                val latest = linkedMapOf<String, FcitxEvent<*>>()
                for (event in previous + batch) {
                    val key = checkNotNull(stateKey(event))
                    latest.remove(key)
                    latest[key] = event
                }
                batches.removeLast()
                batches.addLast(latest.values.toList())
            } else {
                batches.addLast(batch)
            }
            size.value = batches.size
        }
        available.trySend(Unit)
    }

    suspend fun receive(): List<FcitxEvent<*>> {
        while (true) {
            synchronized(lock) {
                if (batches.isNotEmpty()) {
                    val batch = batches.removeFirst()
                    size.value = batches.size
                    return batch
                }
            }
            available.receive()
        }
    }

    private fun stateKey(event: FcitxEvent<*>): String? = when (event) {
        is FcitxEvent.ClientPreeditEvent -> "preedit"
        is FcitxEvent.InputPanelEvent -> "panel"
        is FcitxEvent.CandidateListEvent, is FcitxEvent.PagedCandidateEvent -> "candidates"
        is FcitxEvent.IMChangeEvent -> "ime"
        is FcitxEvent.StatusAreaEvent -> "status"
        else -> null
    }
}
