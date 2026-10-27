/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.junit.Assert.*
import org.junit.Test

class FloatingVoiceResultGateTest {
    private val editor = EditorSessionTracker.Editor("app", 1, 1, 0)
    private fun tracker() = EditorSessionTracker().apply { start(editor, false) }

    @Test fun unsolicitedOrUntaggedBroadcastsCannotDeliver() {
        val tracker = tracker(); val gate = FloatingVoiceResultGate()
        assertFalse(gate.deliver(null, false) { fail() })
        val token = gate.begin(tracker.capture()!!)
        assertFalse(gate.deliver(null, false) { fail() })
        assertFalse(gate.deliver("other", true) { fail() })
        assertTrue(gate.deliver(token, false) {})
    }

    @Test fun partialsCanRepeatAndTheFinalIsConsumedOnce() {
        val tracker = tracker(); val gate = FloatingVoiceResultGate()
        val token = gate.begin(tracker.capture()!!); var deliveries = 0
        repeat(3) { assertTrue(gate.deliver(token, false) { deliveries++ }) }
        assertTrue(gate.deliver(token, true) { deliveries++ })
        assertFalse(gate.deliver(token, true) { fail() })
        assertFalse(gate.deliver(token, false) { fail() })
        assertEquals(4, deliveries)
    }

    @Test fun switchingEditorsInvalidatesOldVoiceResults() {
        val tracker = tracker(); val gate = FloatingVoiceResultGate()
        val token = gate.begin(tracker.capture()!!)
        tracker.start(editor.copy(fieldId = 2), false)
        assertFalse(gate.deliver(token, true) { fail() })
    }

    @Test fun aSameEditorRestartKeepsTheActiveVoiceRequest() {
        val tracker = tracker(); val gate = FloatingVoiceResultGate()
        val token = gate.begin(tracker.capture()!!)
        tracker.start(editor, true)
        assertTrue(gate.deliver(token, true) {})
    }

    @Test fun endingAndReopeningTheSameEditorRejectsItsPreviousRequest() {
        val tracker = tracker(); val gate = FloatingVoiceResultGate()
        val token = gate.begin(tracker.capture()!!)
        tracker.end(); tracker.start(editor, false)
        assertFalse(gate.deliver(token, true) { fail() })
    }

    @Test fun anotherVoiceRequestInTheSameEditorSupersedesTheOldOne() {
        val tracker = tracker(); val gate = FloatingVoiceResultGate()
        val first = gate.begin(tracker.capture()!!); val second = gate.begin(tracker.capture()!!)
        assertNotEquals(first, second)
        assertFalse(gate.deliver(first, true) { fail() })
        assertTrue(gate.deliver(second, true) {})
    }

    @Test fun cancellationInvalidatesResultsEvenWhenTheEditorIsStillActive() {
        val tracker = tracker(); val gate = FloatingVoiceResultGate()
        val token = gate.begin(tracker.capture()!!); gate.clear()
        assertFalse(gate.deliver(token, false) { fail() })
        assertFalse(gate.deliver(token, true) { fail() })
    }

    @Test fun aFinalCallbackCanStartANewRequestWithoutOldCleanupCancellingIt() {
        val tracker = tracker(); val gate = FloatingVoiceResultGate()
        val token = gate.begin(tracker.capture()!!); var next = ""
        assertTrue(gate.deliver(token, true) { next = gate.begin(tracker.capture()!!) })
        assertTrue(gate.deliver(next, true) {})
    }
}
