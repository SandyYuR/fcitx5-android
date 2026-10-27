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
}
