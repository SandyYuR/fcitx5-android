/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

class DirectRimeStatusHintSchedulerTest {
    private val owner = HintTestOwner()
    private val scope = CoroutineScope(SupervisorJob() + owner)
    private var currentGeneration = 7L
    private var started = true
    private var clockReads = 0
    private val expired = mutableListOf<Long>()
    private val hint: DirectRimeStatusHintScheduler by lazy { DirectRimeStatusHintScheduler(scope, {
        clockReads++
        owner.now
    }, { started && it == currentGeneration }, {
        // Clearing happens before the backend is allowed to expire the panel.
        assertEquals("", hint.text)
        assertEquals(0L, hint.id)
        expired += it
    }) }

    @After fun close() { scope.cancel(); owner.runCurrent() }

    @Test fun anUnpresentedHintExpiresOnceAtTheBoundedDeadline() {
        hint.show(7, "中文")
        owner.runCurrent()
        owner.advanceTo(9_999)
        assertEquals("中文", hint.text)
        assertTrue(expired.isEmpty())

        owner.advanceTo(10_000)

        assertEquals(listOf(7L), expired)
        assertEquals("", hint.text)
        assertEquals(0L, hint.id)
        owner.advanceTo(20_000)
        assertEquals(listOf(7L), expired)
    }

    @Test fun aDelayedTimerUsesTheCreationDeadlineRatherThanAFullNewTimeout() {
        hint.show(7, "中文")
        // The owner has not entered the launch block while uptime continues to advance.
        owner.elapseWithoutRunning(4_000)
        owner.runCurrent()
        owner.advanceTo(9_999)
        assertEquals("中文", hint.text)

        owner.advanceTo(10_000)

        assertEquals(listOf(7L), expired)
    }

    @Test fun aDelayedFirstPresentationGetsItsFullVisibleLifetime() {
        hint.show(7, "中文")
        owner.runCurrent()
        owner.advanceTo(3_000)
        hint.presented(hint.id, 3_000)
        owner.runCurrent()
        owner.advanceTo(4_499)
        assertEquals("中文", hint.text)

        owner.advanceTo(4_500)

        assertEquals(listOf(7L), expired)
    }

    @Test fun repeatedFramesDoNotPostponeTheFirstPresentationDeadline() {
        hint.show(7, "中文")
        owner.runCurrent()
        val id = hint.id
        owner.advanceTo(100)
        hint.presented(id, 100)
        owner.runCurrent()
        owner.advanceTo(1_000)
        hint.presented(id, 1_000)
        owner.runCurrent()
        owner.advanceTo(1_599)
        assertEquals("中文", hint.text)

        owner.advanceTo(1_600)

        assertEquals(listOf(7L), expired)
    }

    @Test fun aDelayedOwnerCallbackKeepsTheOriginalFrameTimeAndExpiresImmediately() {
        hint.show(7, "中文")
        owner.runCurrent()
        owner.advanceTo(3_000)

        hint.presented(hint.id, 1_000)
        assertEquals("中文", hint.text)
        owner.runCurrent()

        assertEquals(listOf(7L), expired)
    }

    @Test fun aFutureFrameIsClampedToTheCurrentOwnerUptime() {
        hint.show(7, "中文")
        owner.runCurrent()
        owner.advanceTo(100)
        hint.presented(hint.id, 50_000)
        owner.runCurrent()
        owner.advanceTo(1_599)
        assertEquals("中文", hint.text)

        owner.advanceTo(1_600)

        assertEquals(listOf(7L), expired)
    }

    @Test fun framesBeforeCreationAndOldIdsCannotRescheduleTheReplacement() {
        hint.show(7, "旧提示")
        owner.runCurrent()
        val old = hint.id
        owner.advanceTo(200)
        hint.show(7, "新提示")
        owner.runCurrent()
        val replacement = hint.id
        hint.presented(replacement, 199)
        hint.presented(old, 200)
        owner.runCurrent()
        owner.advanceTo(10_199)
        assertEquals("新提示", hint.text)
        assertEquals(replacement, hint.id)

        owner.advanceTo(10_200)

        assertEquals(listOf(7L), expired)
    }

    @Test fun replacingAPresentedHintCancelsItsEarlierExpiry() {
        hint.show(7, "旧提示")
        owner.runCurrent()
        hint.presented(hint.id, 0)
        owner.runCurrent()
        owner.advanceTo(500)
        hint.show(7, "新提示")
        val replacement = hint.id
        owner.runCurrent()

        owner.advanceTo(1_500)

        assertEquals("新提示", hint.text)
        assertEquals(replacement, hint.id)
        assertTrue(expired.isEmpty())
        owner.advanceTo(10_500)
        assertEquals(listOf(7L), expired)
    }

    @Test fun clearingForTypingOrStoppingCancelsTimersAndInvalidatesQueuedFrames() {
        hint.show(7, "中文")
        owner.runCurrent()
        val old = hint.id

        hint.clear()
        hint.presented(old, 0)
        owner.runCurrent()
        owner.advanceTo(20_000)

        assertEquals("", hint.text)
        assertEquals(0L, hint.id)
        assertTrue(expired.isEmpty())
    }

    @Test fun anOldGenerationTimerClearsItsOwnStateWithoutExpiringTheNewEditorsPanel() {
        hint.show(7, "中文")
        owner.runCurrent()
        currentGeneration = 8
        hint.presented(hint.id, 0)

        owner.advanceTo(10_000)

        assertEquals("", hint.text)
        assertEquals(0L, hint.id)
        assertTrue(expired.isEmpty())
    }

    @Test fun aStoppedEngineRejectsPresentationBeforeReadingTheClockOrUpdatingThePanel() {
        hint.show(7, "中文")
        owner.runCurrent()
        started = false
        val before = clockReads

        hint.presented(hint.id, 0)
        assertEquals(before, clockReads)
        owner.advanceTo(10_000)

        assertEquals("", hint.text)
        assertEquals(0L, hint.id)
        assertTrue(expired.isEmpty())
    }
}

/** Drives actual coroutine dispatch/delay while sharing a virtual uptime with the production clock. */
@OptIn(InternalCoroutinesApi::class)
private class HintTestOwner : CoroutineDispatcher(), Delay {
    private data class Task(val at: Long, val order: Long, val block: Runnable, var cancelled: Boolean = false)
    private val tasks = PriorityQueue(compareBy<Task> { it.at }.thenBy { it.order })
    private var sequence = 0L
    var now = 0L
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) { schedule(now, block) }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val task = schedule(now + timeMillis, Runnable {
            if (continuation.isActive) continuation.resume(Unit)
        })
        continuation.invokeOnCancellation { task.cancelled = true }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val task = schedule(now + timeMillis, block)
        return object : DisposableHandle { override fun dispose() { task.cancelled = true } }
    }

    private fun schedule(at: Long, block: Runnable) = Task(at, ++sequence, block).also { tasks.add(it) }
    fun elapseWithoutRunning(timeMillis: Long) { require(timeMillis >= 0); now += timeMillis }
    fun runCurrent() = advanceTo(now)

    fun advanceTo(target: Long) {
        require(target >= now)
        while (tasks.isNotEmpty() && tasks.peek().at <= target) {
            val task = tasks.remove()
            now = maxOf(now, task.at)
            if (!task.cancelled) task.block.run()
        }
        now = target
    }
}
