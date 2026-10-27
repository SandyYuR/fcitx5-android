package org.fcitx.fcitx5.android.input

import org.junit.Assert.*
import org.junit.Test

class EditorSessionTrackerTest {
    private val editor = EditorSessionTracker.Editor("example.app", 1, 1, 0)
    @Test fun noEditorHasNoToken() { assertNull(EditorSessionTracker().capture()) }
    @Test fun startCreatesCurrentToken() {
        val tracker = EditorSessionTracker()
        assertTrue(tracker.start(editor, false))
        assertTrue(tracker.capture()!!.isCurrent())
    }
    @Test fun sameEditorRestartPreservesVoiceWork() {
        val tracker = EditorSessionTracker()
        tracker.start(editor, false)
        val old = tracker.capture()!!
        assertFalse(tracker.start(editor, true))
        assertTrue(old.isCurrent())
    }
    @Test fun anotherFieldWithTheSameInputClassInvalidatesOldWork() {
        val tracker = EditorSessionTracker()
        tracker.start(editor, false)
        val old = tracker.capture()!!
        tracker.start(editor.copy(fieldId = 2), false)
        assertFalse(old.isCurrent())
    }
    @Test fun aFreshStartInvalidatesEvenIdenticalEditorInfo() {
        val tracker = EditorSessionTracker()
        tracker.start(editor, false)
        val old = tracker.capture()!!
        tracker.start(editor, false)
        assertFalse(old.isCurrent())
    }
    @Test fun restartWithChangedIdentityInvalidatesOldWork() {
        val tracker = EditorSessionTracker()
        tracker.start(editor, false)
        val old = tracker.capture()!!
        assertTrue(tracker.start(editor.copy(packageName = "another.app"), true))
        assertFalse(old.isCurrent())
    }
    @Test fun changingInputTypeInvalidatesEvenARestart() {
        val tracker = EditorSessionTracker()
        tracker.start(editor, false)
        val old = tracker.capture()!!
        tracker.start(editor.copy(inputType = 129), true)
        assertFalse(old.isCurrent())
    }
    @Test fun finishInvalidatesEveryTokenAndHidesTheEditor() {
        val tracker = EditorSessionTracker()
        tracker.start(editor, false)
        val old = tracker.capture()!!
        tracker.end()
        assertFalse(old.isCurrent())
        assertNull(tracker.capture())
    }
    @Test fun restartAfterFinishCannotResurrectAnOldToken() {
        val tracker = EditorSessionTracker()
        tracker.start(editor, false)
        val old = tracker.capture()!!
        tracker.end()
        tracker.start(editor, true)
        assertFalse(old.isCurrent())
        assertTrue(tracker.capture()!!.isCurrent())
    }
}
