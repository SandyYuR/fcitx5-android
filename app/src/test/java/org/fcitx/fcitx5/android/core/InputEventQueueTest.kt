package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class InputEventQueueTest {
    private fun panel(text: String) = FcitxEvent.InputPanelEvent(
        FcitxEvent.InputPanelEvent.Data(FormattedText(arrayOf(text), intArrayOf(0), 0),
            FormattedText.Empty, FormattedText.Empty, emptyArray()))
    private fun commit(text: String) = FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data(text, -1))

    @Test fun withoutStatisticsOrBacklogTheOriginalListIsDeliveredDirectly() = runBlocking {
        val queue = InputEventQueue()
        val display = listOf(panel("n"))
        queue.offer(display)
        assertSame(display, queue.receive())
        val sideEffect = listOf(commit("你"), panel(""))
        queue.offer(sideEffect)
        assertSame(sideEffect, queue.receive())
    }

    @Test fun aSlowUiRetainsOnlyTheLatestPresentationBetweenEditorEvents() = runBlocking {
        val queue = InputEventQueue()
        repeat(10_000) { queue.offer(listOf(panel("$it"))) }
        queue.offer(listOf(FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data())))
        val paged = FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data.Empty)
        queue.offer(listOf(paged))
        assertEquals(listOf(panel("9999"), paged), queue.receive())
    }

    @Test fun commitsAndReadyEventsKeepTheirOrderAndTheirAssociatedState() = runBlocking {
        val queue = InputEventQueue()
        queue.offer(listOf(panel("n")))
        queue.offer(listOf(panel("ni")))
        queue.offer(listOf(commit("你"), panel("")))
        queue.offer(listOf(FcitxEvent.ReadyEvent()))
        queue.offer(listOf(panel("h")))
        queue.offer(listOf(commit("好"), panel("")))
        assertEquals(listOf(panel("ni")), queue.receive())
        assertEquals(listOf(commit("你"), panel("")), queue.receive())
        assertEquals(listOf(FcitxEvent.ReadyEvent()), queue.receive())
        assertEquals(listOf(panel("h")), queue.receive())
        assertEquals(listOf(commit("好"), panel("")), queue.receive())
    }

    @Test fun interactiveAdmissionWaitsForTheConsumerWithoutBlockingReads() = runBlocking {
        val queue = InputEventQueue(highWatermark = 1)
        queue.offer(listOf(commit("你")))
        var admitted = false
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val input = launch(start = CoroutineStart.UNDISPATCHED) {
                queue.awaitCapacity()
                withContext(owner) { admitted = true }
            }
            assertFalse(admitted)
            // A collector can ask native for a candidate page before it resumes draining events.
            assertEquals("candidate page", withTimeout(2000) { withContext(owner) { "candidate page" } })
            assertEquals(listOf(commit("你")), queue.receive())
            withTimeout(2000) { input.join() }
            assertTrue(admitted)
        } finally {
            owner.close()
        }
    }

    @Test fun statisticsStayOrderedWhileIntermediatePresentationStillCoalesces() = runBlocking {
        val queue = InputEventQueue()
        val data = InputOperationStatistics(1, "wanxiang", "万象", 3, false, true, 1000, 1000)
        val first = FcitxEvent.StatisticsKeyEvent(data, 'n'.code)
        val second = FcitxEvent.StatisticsKeyEvent(data, 'i'.code)
        queue.offer(listOf(first, panel("n")))
        queue.offer(listOf(second, panel("ni")))
        queue.offer(listOf(commit("你"), panel("")))
        val received = queue.receive()
        assertEquals(listOf(first, second), (received.first() as FcitxEvent.StatisticsKeysEvent).keys)
        assertEquals(panel("ni"), received.last())
        assertEquals(2, received.size)
        assertEquals(listOf(commit("你"), panel("")), queue.receive())
    }

    @Test fun aBurstOfStatisticsDoesNotAddPresentationBackpressureAndOverflowIsExplicit() = runBlocking {
        val queue = InputEventQueue(highWatermark = 2, statisticsLimit = 100)
        repeat(10_000) { index ->
            val data = InputOperationStatistics(1, "wanxiang", "万象", 3, false, true, index.toLong(), index.toLong())
            queue.offer(listOf(FcitxEvent.StatisticsKeyEvent(data, 'n'.code).apply { inputSession = 7 }, panel("$index")))
        }
        // There is still only one presentation batch, as when recording is disabled.
        withTimeout(1000) { queue.awaitCapacity() }
        queue.offer(listOf(commit("不能丢失")))
        val received = queue.receive()
        val metrics = received.first() as FcitxEvent.StatisticsKeysEvent
        assertEquals(100, metrics.keys.size)
        assertEquals(9900, metrics.dropped)
        assertEquals(7, metrics.inputSession)
        assertEquals(panel("9999"), received.last())
        assertEquals(listOf(commit("不能丢失")), queue.receive())
    }

    @Test fun commitsForwardedKeysAndLifecycleEventsRemainHardBoundariesWithStatistics() = runBlocking {
        val queue = InputEventQueue()
        fun key(sym: Int) = FcitxEvent.StatisticsKeyEvent(InputOperationStatistics(1, "schema", "方案", 1,
            false, false, 1, 1), sym)
        val first = key('a'.code)
        val second = key('b'.code)
        val forwarded = FcitxEvent.KeyEvent(FcitxEvent.KeyEvent.Data(KeySym('c'.code), KeyStates(0u), 'c'.code, false, 0))
        queue.offer(listOf(first, commit("一"), panel("")))
        queue.offer(listOf(second, panel("b")))
        queue.offer(listOf(forwarded))
        queue.offer(listOf(FcitxEvent.ReadyEvent()))
        assertEquals(listOf(first, commit("一"), panel("")), queue.receive())
        assertEquals(listOf(second), (queue.receive().first() as FcitxEvent.StatisticsKeysEvent).keys)
        assertEquals(listOf(forwarded), queue.receive())
        assertEquals(listOf(FcitxEvent.ReadyEvent()), queue.receive())
    }

    @Test fun obsoleteStatisticsEpochsDoNotOccupyTheNewEpochBuffer() = runBlocking {
        val queue = InputEventQueue(statisticsLimit = 1)
        fun key(epoch: Long) = FcitxEvent.StatisticsKeyEvent(InputOperationStatistics(epoch, "schema", "方案", 1,
            false, false, 1, 1), 1).apply { inputSession = 1 }
        queue.offer(listOf(key(1), panel("old")))
        queue.offer(listOf(key(1), panel("old")))
        val current = key(2)
        queue.offer(listOf(current, panel("current")))
        val stats = queue.receive().first() as FcitxEvent.StatisticsKeysEvent
        assertEquals(listOf(current), stats.keys)
        assertEquals(0, stats.dropped)
        assertEquals(2, stats.epoch)
    }
}
