package org.fcitx.fcitx5.android.core

import org.junit.Assert.*
import org.junit.Test

class StatusHintLifetimeTest {
    @Test fun delayedFirstFrameGetsTheFullVisibleLifetime() {
        val hint = StatusHintLifetime(); val id = hint.start(100)
        assertEquals(6667L, hint.remaining(3433))
        assertTrue(hint.presented(id, 3433))
        assertEquals(1500L, hint.remaining(3433))
        assertEquals(1L, hint.remaining(4932))
        assertEquals(0L, hint.remaining(4933))
    }

    @Test fun aHintThatNeverDrawsStillHasABoundedLifetime() {
        val hint = StatusHintLifetime(); hint.start(100)
        assertEquals(1L, hint.remaining(10_099))
        assertEquals(0L, hint.remaining(10_100))
    }

    @Test fun repeatedFramesAndTwoViewsCannotKeepExtendingTheHint() {
        val hint = StatusHintLifetime(); val id = hint.start(0)
        assertTrue(hint.presented(id, 100))
        assertFalse(hint.presented(id, 1000))
        assertEquals(600L, hint.remaining(1000))
    }

    @Test fun anOldFrameCannotChangeTheReplacementHintsDeadline() {
        val hint = StatusHintLifetime(); val old = hint.start(0); val current = hint.start(100)
        assertNotEquals(old, current)
        assertFalse(hint.presented(old, 500))
        assertEquals(9600L, hint.remaining(500))
        assertTrue(hint.presented(current, 500))
    }

    @Test fun clearingForTypingOrANewEditorInvalidatesQueuedFrames() {
        val hint = StatusHintLifetime(); val id = hint.start(0)
        hint.clear()
        assertEquals(0L, hint.id)
        assertEquals(0L, hint.remaining(100))
        assertFalse(hint.presented(id, 100))
    }

    @Test fun reconnectingToANewBackendCannotReuseAnOldHintId() {
        val previous = StatusHintLifetime().start(0)
        val hint = StatusHintLifetime(); val current = hint.start(100)
        assertNotEquals(previous, current)
        assertFalse(hint.presented(previous, 200))
    }

    @Test fun aFrameAfterTheFallbackDeadlineCannotResurrectAStaleHint() {
        val hint = StatusHintLifetime(); val id = hint.start(0)
        assertFalse(hint.presented(id, 10_000))
        assertEquals(0L, hint.remaining(10_001))
    }

    @Test fun panelEqualityIncludesThePresentationIdentity() {
        val panel = FcitxEvent.InputPanelEvent.Data()
        assertNotEquals(panel.copy(statusHintId = 1), panel.copy(statusHintId = 2))
        assertNotEquals(panel.copy(statusHintId = 1).hashCode(), panel.copy(statusHintId = 2).hashCode())
        assertEquals(panel, panel.copy())
    }

    @Test fun aDelayedOwnerCallbackUsesTheOriginalFrameTime() {
        val hint = StatusHintLifetime(); val id = hint.start(100)
        assertTrue(hint.presented(id, 1000)) // This frame callback reaches the owner only at t=3000.
        assertEquals(0L, hint.remaining(3000))
    }

    @Test fun aFrameFromBeforeTheHintWasCreatedIsIgnored() {
        val hint = StatusHintLifetime(); val id = hint.start(100)
        assertFalse(hint.presented(id, 99))
        assertEquals(10_000L, hint.remaining(100))
    }
}
