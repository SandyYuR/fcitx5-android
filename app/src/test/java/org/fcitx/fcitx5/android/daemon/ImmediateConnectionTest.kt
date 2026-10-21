/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.daemon

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ImmediateConnectionTest {
    @Test(timeout = 5000) fun cachedReadsDoNotWaitBehindAnOccupiedWorker() {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val caller = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + worker)
        try {
            scope.launch { entered.countDown(); release.await() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val value = caller.submit<Boolean> {
                val thread = Thread.currentThread()
                runImmediatelyInContext(scope.coroutineContext) { Thread.currentThread() === thread }
            }
            assertTrue("A cache read must return on its caller while the worker is busy", value.get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown(); scope.cancel(); caller.shutdownNow(); worker.close()
        }
    }

    @Test fun stoppingTheLifecycleStillCancelsImmediateReads() {
        val lifecycle = SupervisorJob().apply { cancel() }
        var entered = false
        try {
            runImmediatelyInContext(lifecycle + Dispatchers.Default) { entered = true }
            fail("Stopped lifecycle must not run an immediate operation")
        } catch (_: CancellationException) {
            assertFalse(entered)
        }
    }

    @Test(timeout = 5000) fun nativeWorkStillUsesItsExplicitOwnerDispatcher() {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val lifecycle = SupervisorJob()
        val caller = Thread.currentThread()
        try {
            val thread = runImmediatelyInContext(lifecycle + Dispatchers.Default) {
                withContext(worker) { Thread.currentThread() }
            }
            assertNotSame(caller, thread)
            assertSame(thread, runBlocking(worker) { Thread.currentThread() })
        } finally { lifecycle.cancel(); worker.close() }
    }
}
