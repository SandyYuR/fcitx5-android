/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ClipboardTaskQueueTest {
    private class Dispatcher : CoroutineDispatcher() {
        val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
    private class Fixture {
        val main = Dispatcher()
        val worker = Dispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val queue = ClipboardTaskQueue(main, worker)
        fun pump() {
            repeat(100) {
                if (main.pending.isEmpty() && worker.pending.isEmpty()) return
                main.drain(); worker.drain()
            }
            error("Unexpected busy loop")
        }
    }

    @Test fun prepareAndConsumeBothRunOnWorker() {
        val f = Fixture()
        var prepared = false
        var consumed = false
        f.queue.submit(f.scope, {
            assertSame(f.worker, currentCoroutineContext()[kotlin.coroutines.ContinuationInterceptor])
            prepared = true; "copy"
        }) {
            assertEquals("copy", it)
            assertSame(f.worker, currentCoroutineContext()[kotlin.coroutines.ContinuationInterceptor])
            consumed = true
        }
        f.main.drain()
        assertFalse(prepared)
        f.pump()
        assertTrue(prepared && consumed)
        f.scope.cancel()
    }

    @Test fun slowFirstNormalizationDoesNotAllowTheSecondCopyToOvertake() {
        val f = Fixture()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        f.queue.submit(f.scope, { release.await(); order += 1 }) {}
        f.queue.submit(f.scope, { order += 2 }) {}
        f.pump()
        assertTrue(order.isEmpty())
        release.complete(Unit); f.pump()
        assertEquals(listOf(1, 2), order)
        f.scope.cancel()
    }

    @Test fun aSlowNetworkDrainDoesNotBlockTheNextPersistentEnqueue() {
        val f = Fixture()
        val network = CompletableDeferred<Unit>()
        val enqueued = mutableListOf<Int>()
        f.queue.submit(f.scope, { enqueued += 1 }) { network.await() }
        f.queue.submit(f.scope, { enqueued += 2 }) {}
        f.pump()
        assertEquals(listOf(1, 2), enqueued)
        network.complete(Unit); f.pump(); f.scope.cancel()
    }

    @Test fun cancellationBeforeAdmissionDoesNotReadOrEnqueue() {
        val f = Fixture()
        f.queue.submit(f.scope, { fail("Cancelled work must not load rules") }) { fail("Cancelled work must not drain") }
        f.scope.cancel(); f.pump()
    }

    @Test fun cancellationBeforeWorkerStartsDoesNotReadOrEnqueue() {
        val f = Fixture()
        f.queue.submit(f.scope, { fail("Cancelled work must not load rules") }) { fail("Cancelled work must not drain") }
        f.main.drain(); f.scope.cancel(); f.pump()
    }

    @Test fun cancellingOneSuspendedCopyReleasesTheNextCopy() {
        val f = Fixture()
        val release = CompletableDeferred<Unit>()
        val first = f.queue.submit(f.scope, { release.await() }) { fail("Cancelled copy must not drain") }
        var enqueued = false
        f.queue.submit(f.scope, { enqueued = true }) {}
        f.pump(); assertFalse(enqueued)
        first.cancel(); f.pump(); assertTrue(enqueued)
        f.scope.cancel()
    }
}
