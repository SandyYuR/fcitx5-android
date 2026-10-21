/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class CandidatePageReaderTest {
    @Test fun preservesOffsetsAndStopsAtTheActualEnd() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val words = (0 until 25).toList()
        val page = readCandidatePage(5, 48) { start, count ->
            calls += start to count
            words.drop(start).take(count)
        }
        assertEquals((5 until 25).toList(), page)
        assertEquals(listOf(5 to 8, 13 to 8, 21 to 8), calls)
    }

    @Test fun queuedKeyRunsBeforeTheNextCandidateBatch() = runBlocking {
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val inputs = CoroutineScope(SupervisorJob() + owner)
        val pendingKey = CompletableDeferred<Job>()
        val order = mutableListOf<String>()
        try {
            val page = readCandidatePage(0, 24) { start, count ->
                withContext(owner) {
                    order += "batch:$start"
                    if (start == 0) pendingKey.complete(inputs.launch { order += "key" })
                    List(count) { start + it }
                }
            }
            pendingKey.await().join()
            assertEquals((0 until 24).toList(), page)
            assertEquals(listOf("batch:0", "key", "batch:8", "batch:16"), order)
        } finally { inputs.cancel(); owner.close() }
    }

    @Test fun aChangedCompositionNeverReturnsMixedCandidates() = runBlocking {
        var calls = 0
        try {
            readCandidatePage(0, 48) { _, count ->
                if (++calls == 1) List(count) { "old" } else null
            }
            fail("A partial old page must not be published")
        } catch (_: CancellationException) { assertEquals(2, calls) }
    }

    @Test fun cancellationStopsFurtherNativeRequests() = runBlocking {
        var calls = 0
        val page = launch {
            readCandidatePage(0, 144) { _, count ->
                calls++
                currentCoroutineContext().cancel()
                List(count) { "word" }
            }
        }
        page.join()
        assertTrue(page.isCancelled)
        assertEquals(1, calls)
    }
}
