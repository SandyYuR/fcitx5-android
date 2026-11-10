/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputDiagnosticsTest {
    @Test fun serviceLifecycleCountersSettleExactlyOnceWithAControllableClock() {
        var now = 100L
        val counters = linkedMapOf<String, MutableList<Long>>()
        val tracker = InputDiagnostics.ServiceTracker(true, { now }, InputDiagnostics.CounterSink { name, value ->
            counters.getOrPut(name) { mutableListOf() } += value
        })

        val completed = tracker.create("key")!!
        assertEquals(1L, completed.id)
        assertEquals("key", completed.category)
        assertEquals(listOf(1L), counters["rime.service.waiting"])
        now = 175L
        completed.started()
        assertEquals(listOf(1L, 0L), counters["rime.service.waiting"])
        assertEquals(listOf(1L), counters["rime.service.running"])
        assertEquals(listOf(75L), counters["rime.service.wait_ns"])
        completed.completed(null)
        completed.completed(CancellationException("late duplicate"))
        assertEquals(listOf(1L, 0L), counters["rime.service.running"])
        assertEquals(listOf(1L), counters["rime.service.completed"])
        assertNull(counters["rime.service.cancelled"])

        val rejected = tracker.create("tab")!!
        rejected.rejected()
        rejected.completed(CancellationException("queue closed"))
        assertEquals(listOf(1L), counters["rime.service.rejected"])
        assertEquals(listOf(1L), counters["rime.service.cancelled"])
        assertEquals(0L, counters.getValue("rime.service.waiting").last())
    }

    @Test fun disabledTrackerDoesNotReadTheClockOrCreateTaskState() {
        var clockReads = 0
        val tracker = InputDiagnostics.ServiceTracker(false, { clockReads++; 1L }, InputDiagnostics.CounterSink { _, _ -> })
        assertNull(tracker.create("key"))
        assertEquals(0, clockReads)
    }
}
