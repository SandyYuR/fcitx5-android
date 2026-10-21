/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.*
import org.junit.Test

class InputKeyGateTest {
    @Test fun keysPressedDuringStartupSurviveUntilTheirEditorIsReady() {
        val gate = InputKeyGate()
        gate.starting()
        gate.invalidate(1)
        gate.prepare(1, true)
        val first = gate.capture()!!
        val second = gate.capture()!!
        assertFalse(gate.canDeliver(first))
        gate.running()
        assertFalse("Runtime readiness alone must not bypass editor binding", gate.canDeliver(first))
        gate.resolve(1, true)
        assertTrue(gate.canDeliver(first))
        gate.release(first)
        assertFalse(gate.canDeliver(first))
        assertTrue(gate.canDeliver(second))
    }

    @Test fun oldKeysCannotCrossEditorsEvenIfTheOldBindCompletesLate() {
        val gate = InputKeyGate()
        gate.starting(); gate.prepare(1, true)
        val old = gate.capture()!!
        gate.invalidate(2); gate.prepare(2, true)
        val current = gate.capture()!!
        gate.running(); gate.resolve(1, true)
        assertFalse(gate.canDeliver(old))
        assertFalse(gate.canDeliver(current))
        gate.resolve(2, true)
        assertFalse(gate.canDeliver(old))
        assertTrue(gate.canDeliver(current))
    }

    @Test fun aLongStartupExpiresWaitingKeysInsteadOfInsertingThemMuchLater() {
        var now = 0L
        val gate = InputKeyGate(clockNanos = { now }, maxWaitNanos = 100)
        gate.starting(); gate.prepare(1, true)
        val pending = gate.capture()!!
        now = 101
        gate.running(); gate.resolve(1, true)
        assertFalse(gate.canDeliver(pending))
        val ready = gate.capture()!!
        now += 1000
        assertTrue("Normal input does not inherit the startup timeout", gate.canDeliver(ready))
    }

    @Test fun queueCapacityIsReturnedOnCancellationAndCannotBeCorruptedByAnOldTicket() {
        val gate = InputKeyGate(maxWaitingKeys = 1)
        gate.starting(); gate.prepare(1, true)
        val old = gate.capture()!!
        var reason = ""
        assertNull(gate.capture { reason = it })
        assertEquals("queue_full", reason)
        gate.release(old); gate.release(old)
        assertNotNull(gate.capture())
        gate.invalidate(2); gate.prepare(2, true)
        assertNotNull(gate.capture())
        gate.release(old)
        assertNull("Releasing an old ticket must not free this editor's capacity", gate.capture())
    }

    @Test fun deploymentNeverReplaysKeysCapturedBeforeOrDuringIt() {
        val gate = InputKeyGate()
        gate.running(); gate.prepare(1, true); gate.resolve(1, true)
        val old = gate.capture()!!
        gate.block(); gate.invalidate(2); gate.prepare(2, true)
        assertNull(gate.capture())
        gate.resolve(2, true); gate.running()
        assertFalse(gate.canDeliver(old))
        assertTrue(gate.canDeliver(gate.capture()!!))
    }

    @Test fun nonEditorsFailedStartsAndStoppedRuntimesDoNotAdmitKeys() {
        val gate = InputKeyGate()
        gate.prepare(1, true)
        assertNull(gate.capture())
        gate.starting(); gate.prepare(2, false)
        assertNull(gate.capture())
        gate.prepare(3, true)
        val pending = gate.capture()!!
        gate.running(false); gate.resolve(3, true)
        assertNull(gate.capture())
        assertFalse(gate.canDeliver(pending))
        gate.starting(); gate.running(); gate.resolve(3, false)
        assertNull(gate.capture())
        gate.stop()
        assertFalse(gate.canDeliver(pending))
    }
}
