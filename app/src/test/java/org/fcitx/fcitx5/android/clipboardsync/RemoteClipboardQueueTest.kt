/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemoteClipboardQueueTest {
    @Test fun slowFirstMessageCannotBeOvertakenAndInflightBytesCountTowardBudget() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        val queue = RemoteClipboardQueue<String>(scope, maxBytes = 4, weight = { it.length.toLong() }) {
            if (it == "AA") gate.await()
            delivered += it
        }
        try {
            assertTrue(queue.offer("AA"))
            assertTrue(queue.offer("BB"))
            assertFalse(queue.offer("C"))
            assertTrue(delivered.isEmpty())
            gate.complete(Unit)
            assertEquals(listOf("AA", "BB"), delivered)
            assertTrue(queue.offer("CC"))
            assertEquals(listOf("AA", "BB", "CC"), delivered)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun disconnectDropsOldPendingAndInflightMessagesAndBoundsSmallMessageBursts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val delivered = mutableListOf<Int>()
        val queue = RemoteClipboardQueue<Int>(scope, maxBytes = 1024, maxItems = 2, weight = { 1 }) {
            gate.await(); delivered += it
        }
        assertTrue(queue.offer(1))
        assertTrue(queue.offer(2))
        repeat(128) { assertFalse(queue.offer(3)) }
        queue.close()
        gate.complete(Unit)
        assertFalse(queue.offer(4))
        assertTrue(delivered.isEmpty())
        scope.cancel()
    }
}
