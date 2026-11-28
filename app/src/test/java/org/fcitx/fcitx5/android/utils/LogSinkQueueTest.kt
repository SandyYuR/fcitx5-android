/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class LogSinkQueueTest {
    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) = synchronized(tasks) { tasks.addLast(command) }
        fun count(): Int = synchronized(tasks) { tasks.size }
        fun runAll() {
            while (true) {
                val task = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeFirst() } ?: return
                task.run()
            }
        }
    }

    @Test
    fun manyEventsUseOneTaskAndFlushOncePerBatch() {
        val executor = ManualExecutor()
        val events = mutableListOf<Int>()
        var flushes = 0
        val queue = LogSinkWriteQueue(executor, flush = { flushes++ }, reportDropped = { error("unexpected drop") })
        repeat(130) { index -> assertTrue(queue.log(false, 1) { events.add(index) }) }
        assertEquals(1, executor.count())
        executor.runAll()
        assertEquals((0 until 130).toList(), events)
        assertEquals(3, flushes)
        assertTrue(queue.log(false, 1) { events.add(130) })
        assertEquals(1, executor.count())
        executor.runAll()
        assertEquals(130, events.last())
    }

    @Test
    fun ordinaryPayloadHasCountAndCharacterBudgetsAndReportsAllDrops() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val drops = mutableListOf<Long>()
        val queue = LogSinkWriteQueue(executor, capacity = 2, maxCharacters = 4,
            flush = {}, reportDropped = { drops.add(it) })
        assertFalse(queue.log(false, 5) { error("oversized event retained") })
        assertTrue(queue.log(false, 3) { events.add("first") })
        assertFalse(queue.log(false, 2) { error("character budget ignored") })
        assertTrue(queue.log(false, 1) { events.add("second") })
        repeat(10) { assertFalse(queue.log(false, 0) { error("count budget ignored") }) }
        executor.runAll()
        assertEquals(listOf("first", "second"), events)
        assertEquals(listOf(12L), drops)
        assertTrue(queue.log(false, 4) { events.add("next") })
        executor.runAll()
        assertEquals(listOf("first", "second", "next"), events)
    }

    @Test
    fun errorsWaitForRoomAndRemainInOrderWithoutTruncation() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val queue = LogSinkWriteQueue(executor, capacity = 1, maxCharacters = 4,
            flush = {}, reportDropped = { error("error dropped") })
        assertTrue(queue.log(false, 1) { events.add("first") })
        val started = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val errorPayload = "full diagnostic stack".repeat(100)
        val producer = Thread {
            started.countDown()
            if (queue.log(true, errorPayload.length.toLong()) { events.add(errorPayload) }) accepted.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertFalse("full queue must apply backpressure instead of retaining more entries", accepted.await(100, TimeUnit.MILLISECONDS))
        executor.runAll()
        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        producer.join(5000)
        assertFalse(producer.isAlive)
        executor.runAll()
        assertEquals(listOf("first", errorPayload), events)
    }

    @Test
    fun clearExportAndCloseBarriersKeepOrderAndCloseRejectsNewEvents() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val queue = LogSinkWriteQueue(executor, flush = {}, reportDropped = { events.add("drop:$it") })
        queue.log(false, 1) { events.add("before clear") }
        queue.control { events.clear(); events.add("cleared") }
        queue.log(true, 1) { events.add("after clear") }
        queue.control { assertEquals(listOf("cleared", "after clear"), events); events.add("export flushed") }
        queue.close { events.add("closed") }
        assertFalse(queue.log(true, 1) { error("accepted after close") })
        assertFalse(queue.control { error("accepted barrier after close") })
        executor.runAll()
        assertEquals(listOf("cleared", "after clear", "export flushed", "closed"), events)
    }

    @Test
    fun dropReportRunsBeforeFinalCloseAndFailuresDoNotStrandTheDrain() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        var closed = false
        val queue = LogSinkWriteQueue(executor, maxCharacters = 1,
            flush = { throw IllegalStateException("disk flush failed") },
            reportDropped = { assertFalse(closed); events.add("drop:$it") })
        assertFalse(queue.log(false, 2) { error("retained oversized event") })
        queue.log(true, 2) { events.add("error"); throw IllegalStateException("disk write failed") }
        queue.close { closed = true; events.add("closed") }
        executor.runAll()
        assertEquals(listOf("drop:1", "error", "closed"), events)
        assertTrue(closed)
    }

    @Test
    fun executorRejectionFailsClosedAndNeverThrowsIntoTheLogger() {
        val queue = LogSinkWriteQueue(Executor { throw RejectedExecutionException("closed") },
            flush = {}, reportDropped = {})
        assertFalse(queue.log(true, 1) { error("must not execute") })
        assertFalse(queue.control { error("must not execute") })
        assertFalse(queue.close { error("must not execute") })
    }
}
