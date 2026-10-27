package org.fcitx.fcitx5.android.input.keyboard

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.junit.Assert.*
import org.junit.Test

class MacroInputControllerTest {
    private val ctrl = KeyRef.Fcitx("Ctrl_L")
    private val key = KeyRef.Fcitx("a")
    private val events = mutableListOf<String>()
    private val controller = MacroInputController { events += "release failed" }
    private fun press(execution: MacroInputController.Execution, key: KeyRef, name: String): MacroInputController.Press =
        execution.press(key).also { it.send({ events += "down:$name" }, { events += "up:$name:$it" }) }

    @Test fun successfulDownOnlyMacroRemainsHeldUntilAnExplicitUp() {
        val downJob = Job()
        val down = controller.begin(downJob) { true }
        press(down, ctrl, "ctrl")
        down.finish(completed = true)
        downJob.complete()
        assertEquals(listOf("down:ctrl"), events)
        val up = controller.begin(Job()) { true }
        up.release(ctrl) { fail("lost intentional hold") }
        up.finish(completed = true)
        assertEquals(listOf("down:ctrl", "up:ctrl:false"), events)
    }

    @Test fun failedExecutionReleasesItsOwnKeysInReverseOrder() {
        val execution = controller.begin(Job()) { true }
        press(execution, ctrl, "ctrl")
        press(execution, key, "a")
        execution.finish(completed = false)
        assertEquals(listOf("down:ctrl", "down:a", "up:a:true", "up:ctrl:true"), events)
    }

    @Test fun failureDoesNotReleaseAnotherMacrosIntentionalHold() {
        val previous = controller.begin(Job()) { true }
        press(previous, ctrl, "prior ctrl")
        previous.finish(completed = true)
        val current = controller.begin(Job()) { true }
        press(current, key, "a")
        current.finish(completed = false)
        assertFalse(events.any { it.startsWith("up:prior ctrl") })
        controller.cancelAll()
        assertEquals("up:prior ctrl:true", events.last())
    }

    @Test fun cancellingTheControllerCancelsJobsAndReleasesCompletedHolds() {
        val previous = controller.begin(Job()) { true }
        press(previous, ctrl, "held")
        previous.finish(completed = true)
        val job = Job()
        val current = controller.begin(job) { true }
        press(current, key, "running")
        controller.cancelAll()
        assertTrue(job.isCancelled)
        assertEquals(listOf("up:running:true", "up:held:true"), events.takeLast(2))
        current.finish(completed = false)
        assertEquals(4, events.size)
    }

    @Test fun editorChangePreventsTheNextTextOrKeyStep() = runBlocking {
        val tracker = EditorSessionTracker()
        tracker.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)
        val token = tracker.capture()!!
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            val execution = controller.begin(coroutineContext.job, token::isCurrent)
            try {
                press(execution, ctrl, "ctrl")
                awaitCancellation()
            } finally { execution.finish(completed = false) }
        }
        tracker.start(EditorSessionTracker.Editor("app", 2, 1, 0), false)
        controller.cancelAll()
        job.join()
        assertEquals(listOf("down:ctrl", "up:ctrl:true"), events)
        assertTrue(runCatching { controller.begin(Job(), token::isCurrent) }.isFailure)
    }

    @Test fun normalTapReleasesTheExactPressEvenWhenAnotherMacroUsesTheSameKey() {
        val first = controller.begin(Job()) { true }
        val firstPress = press(first, key, "first")
        val second = controller.begin(Job()) { true }
        press(second, key, "second")
        firstPress.release()
        assertEquals("up:first:false", events.last())
        assertFalse(events.any { it.startsWith("up:second") })
        second.finish(completed = false)
        assertEquals("up:second:true", events.last())
    }

    @Test fun syntheticShiftIsReleasedFromTheOriginalPressSnapshot() {
        val execution = controller.begin(Job()) { true }
        val press = execution.press(KeyRef.Fcitx("A"))
        press.send({ events += "shift down" }, { events += "shift up" })
        press.send({ events += "A down" }, { events += "A up" })
        execution.release(KeyRef.Fcitx("A")) { fail("recomputed the press") }
        assertEquals(listOf("shift down", "A down", "A up", "shift up"), events)
    }

    @Test fun aDispatchFailureStillReleasesThePartiallySentPress() {
        val execution = controller.begin(Job()) { true }
        val press = execution.press(key)
        assertTrue(runCatching {
            press.send({ events += "accepted down"; error("dispatch failed") }, { events += "cleanup" })
        }.isFailure)
        execution.finish(completed = false)
        assertEquals(listOf("accepted down", "cleanup"), events)
    }

    @Test fun releaseFailuresDoNotSkipOtherModifiersOrKeys() {
        val execution = controller.begin(Job()) { true }
        val press = execution.press(key)
        press.send({}, { events += "shift up" })
        press.send({}, { events += "key up"; error("connection closed") })
        execution.finish(completed = false)
        assertEquals(listOf("key up", "shift up", "release failed"), events)
    }

    @Test fun unknownExplicitUpStillReachesItsFallback() {
        val execution = controller.begin(Job()) { true }
        execution.release(ctrl) { events += "explicit up" }
        assertEquals(listOf("explicit up"), events)
    }

    @Test fun staleExecutionCannotSendAKeyOrExplicitUp() {
        var current = true
        val execution = controller.begin(Job()) { current }
        current = false
        assertTrue(runCatching { press(execution, key, "a") }.isFailure)
        assertTrue(runCatching { execution.release(ctrl) { fail("stale up dispatched") } }.isFailure)
        assertTrue(events.isEmpty())
    }
}
