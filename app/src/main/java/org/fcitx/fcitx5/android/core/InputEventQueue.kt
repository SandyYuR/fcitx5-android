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
internal class InputEventQueue(private val highWatermark: Int = 64, private val statisticsLimit: Int = 1024) {
    private val lock = Any()
    private val batches = ArrayDeque<PendingBatch>()
    private val available = Channel<Unit>(Channel.CONFLATED)
    private val size = MutableStateFlow(0)

    init { require(highWatermark > 0 && statisticsLimit > 0) }

    private inner class PendingBatch(private val initial: List<FcitxEvent<*>>, val coalescible: Boolean) {
        private var presentation: LinkedHashMap<String, FcitxEvent<*>>? = null
        private var statistics: ArrayList<FcitxEvent.StatisticsKeyEvent>? = null
        private var generation = 0L
        private var epoch = 0L
        private var dropped = 0L

        init {
            if (coalescible && initial.any { it is FcitxEvent.StatisticsKeyEvent }) {
                presentation = linkedMapOf()
                collect(initial)
            }
        }

        fun append(batch: List<FcitxEvent<*>>) {
            check(coalescible)
            if (presentation == null) {
                presentation = linkedMapOf()
                collect(initial)
            }
            collect(batch)
        }

        private fun collect(batch: List<FcitxEvent<*>>) {
            for (event in batch) {
                if (event is FcitxEvent.StatisticsKeyEvent) {
                    val keys = statistics ?: ArrayList<FcitxEvent.StatisticsKeyEvent>().also { statistics = it }
                    // Old editor/recording epochs are rejected at delivery anyway. Do not
                    // let their optional metrics occupy the current editor's bounded buffer.
                    if (keys.isNotEmpty() && (generation != event.inputSession || epoch != event.statistics.epoch)) {
                        keys.clear(); dropped = 0
                    }
                    generation = event.inputSession; epoch = event.statistics.epoch
                    if (keys.size < statisticsLimit) keys.add(event)
                    else if (dropped < Long.MAX_VALUE) dropped++
                } else {
                    val key = checkNotNull(stateKey(event))
                    presentation!!.remove(key)
                    presentation!![key] = event
                }
            }
        }

        fun take(): List<FcitxEvent<*>> {
            // The common no-backlog, statistics-disabled path keeps the original list;
            // it allocates neither a presentation map nor a statistics collection.
            val state = presentation ?: return initial
            val result = ArrayList<FcitxEvent<*>>(state.size + 1)
            val keys = statistics
            if (!keys.isNullOrEmpty()) result += FcitxEvent.StatisticsKeysEvent(keys, epoch, dropped).apply {
                inputSession = generation
            }
            result.addAll(state.values)
            return result
        }
    }

    suspend fun awaitCapacity() {
        if (size.value >= highWatermark) size.first { it < highWatermark }
    }

    fun offer(batch: List<FcitxEvent<*>>) {
        if (batch.isEmpty()) return
        synchronized(lock) {
            val previous = batches.lastOrNull()
            val coalescible = batch.all { it is FcitxEvent.StatisticsKeyEvent || stateKey(it) != null }
            if (previous?.coalescible == true && coalescible) {
                previous.append(batch)
            } else {
                batches.addLast(PendingBatch(batch, coalescible))
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
                    return batch.take()
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
