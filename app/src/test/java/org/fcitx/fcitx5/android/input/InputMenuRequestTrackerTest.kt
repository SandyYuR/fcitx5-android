/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InputMenuRequestTrackerTest {
    private val editor = EditorSessionTracker.Editor("fixture", 1, 1, 0)

    @Test fun aCurrentRequestReturnsItsData() = runBlocking {
        val tracker = InputMenuRequestTracker()
        assertEquals("menu", tracker.loadIfCurrent(tracker.newRequest { true }) { "menu" })
    }

    @Test fun aNewRequestDropsTheLateOldResult() = runBlocking {
        val tracker = InputMenuRequestTracker()
        val request = tracker.newRequest { true }
        val release = CompletableDeferred<String>()
        val old = async(start = CoroutineStart.UNDISPATCHED) { tracker.loadIfCurrent(request) { release.await() } }
        val current = tracker.newRequest { true }
        release.complete("old")
        assertNull(old.await())
        assertFalse(request.isCurrent())
        assertTrue(current.isCurrent())
    }

    @Test fun changingEditorDuringTheLoadDropsTheMenu() = runBlocking {
        val sessions = EditorSessionTracker().apply { start(editor, false) }
        val token = sessions.capture()!!
        val tracker = InputMenuRequestTracker()
        val request = tracker.newRequest(token::isCurrent)
        val release = CompletableDeferred<String>()
        val load = async(start = CoroutineStart.UNDISPATCHED) { tracker.loadIfCurrent(request) { release.await() } }
        sessions.start(editor.copy(fieldId = 2), false)
        release.complete("late")
        assertNull(load.await())
    }

    @Test fun sameEditorRestartKeepsTheMenuRequestValid() = runBlocking {
        val sessions = EditorSessionTracker().apply { start(editor, false) }
        val token = sessions.capture()!!
        val tracker = InputMenuRequestTracker()
        val request = tracker.newRequest(token::isCurrent)
        sessions.start(editor, true)
        assertEquals("menu", tracker.loadIfCurrent(request) { "menu" })
    }

    @Test fun replacedInputViewsCannotReceiveTheOldResult() = runBlocking {
        val original = Any()
        var current = original
        val tracker = InputMenuRequestTracker()
        val request = tracker.newRequest { current === original }
        assertNull(tracker.loadIfCurrent(request) { current = Any(); "old" })
    }

    @Test fun aRecycledAnchorCannotReceiveTheOldMenu() = runBlocking {
        var binding = Any()
        val original = binding
        val tracker = InputMenuRequestTracker()
        val request = tracker.newRequest { binding === original }
        assertNull(tracker.loadIfCurrent(request) { binding = Any(); "old" })
    }

    @Test fun invalidRequestsDoNotStartAnotherLoad() = runBlocking {
        val tracker = InputMenuRequestTracker()
        val request = tracker.newRequest { true }
        tracker.invalidate()
        assertNull(tracker.loadIfCurrent(request) { fail("Must not fetch stale actions"); "unreachable" })
    }

    @Test fun hidingTheInputInvalidatesThePendingResultAndClickPermit() = runBlocking {
        val tracker = InputMenuRequestTracker()
        val request = tracker.newRequest { true }
        assertNull(tracker.loadIfCurrent(request) { tracker.invalidate(); "late" })
        assertFalse(request.isCurrent())
    }

    @Test fun changedNativeCandidateVersionRejectsTheResultAndAction() = runBlocking {
        var version = 1L
        val tracker = InputMenuRequestTracker()
        val request = tracker.newRequest { version == 1L }
        assertNull(tracker.loadIfCurrent(request) { version++; "late" })
        assertFalse(request.isCurrent())
    }
}
