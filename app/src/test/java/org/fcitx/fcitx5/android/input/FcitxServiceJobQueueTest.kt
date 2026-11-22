/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input

import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.InputKeyGate
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestDelegate
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestShadow
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, FcitxDaemonTestShadow::class]
)
class FcitxServiceJobQueueTest {
    private val engineJob = SupervisorJob()
    private val engineScope = CoroutineScope(engineJob + Dispatchers.Default)
    private val api = Proxy.newProxyInstance(
        FcitxAPI::class.java.classLoader,
        arrayOf(FcitxAPI::class.java)
    ) { _, method, _ -> error("Unexpected engine API call: ${method.name}") } as FcitxAPI
    private fun connection(dispatcher: CoroutineDispatcher?) = object : FcitxConnection {
        override val interactiveDispatcher = dispatcher
        override val lifecycleScope = engineScope
        override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T =
            runBlocking { block(api) }
        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = block(api)
        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) = Unit
    }
    private val gate = InputKeyGate().apply {
        running()
        prepare(1L, true)
        resolve(1L, true)
    }
    private val releasedTickets = AtomicInteger()

    private val daemon = object : FcitxDaemonTestDelegate {
        override fun requireInputSession() = false
        override fun captureInputKey() = gate.capture()
        override fun canDeliverInputKey(ticket: InputKeyGate.Ticket) = gate.canDeliver(ticket)
        override fun releaseInputKey(ticket: InputKeyGate.Ticket) {
            gate.release(ticket)
            releasedTickets.incrementAndGet()
        }
    }

    @After fun tearDown() {
        engineScope.cancel()
        FcitxDaemonTestShadow.reset()
    }

    private fun service(dispatcher: CoroutineDispatcher? = null): FcitxInputMethodService {
        FcitxDaemonTestShadow.install(daemon)
        return Robolectric.buildService(FcitxInputMethodService::class.java).get().also {
            ReflectionHelpers.setField(it, "fcitx", connection(dispatcher))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun jobs(service: FcitxInputMethodService): Channel<Job> =
        ReflectionHelpers.getField(service, "jobs")

    @Test fun cancellingQueueCancelsBufferedJobButPreservesDequeuedWork() = runBlocking {
        val service = service()
        val jobs = jobs(service)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val bufferedCompletions = AtomicInteger()

        val running = service.postFcitxJob {
            started.complete(Unit)
            finish.await()
        }
        val buffered = service.postFcitxKeyJob {
            error("An undelivered key must not execute")
        }.also { it.invokeOnCompletion { bufferedCompletions.incrementAndGet() } }

        assertSame(running, jobs.receive())
        running.start()
        withTimeout(5_000) { started.await() }

        jobs.cancel()
        withTimeout(5_000) { buffered.join() }
        assertTrue(buffered.isCancelled)
        assertEquals(1, bufferedCompletions.get())
        assertEquals(1, releasedTickets.get())
        assertTrue("Another client can keep the shared engine scope alive", engineJob.isActive)
        assertTrue("Already dequeued work must not be cancelled with the buffer", running.isActive)

        finish.complete(Unit)
        withTimeout(5_000) { running.join() }
        assertFalse(running.isCancelled)
    }

    @Test fun closedQueueRejectsAndCompletesNewKeyExactlyOnce() = runBlocking {
        val service = service()
        jobs(service).cancel()
        val completions = AtomicInteger()

        val rejected = service.postFcitxKeyJob {
            error("A rejected key must not execute")
        }.also { it.invokeOnCompletion { completions.incrementAndGet() } }

        withTimeout(5_000) { rejected.join() }
        assertTrue(rejected.isCancelled)
        assertEquals(1, completions.get())
        assertEquals(1, releasedTickets.get())
        assertTrue(engineJob.isActive)
    }

    @Test fun rejectedPagingModeJobDoesNotRequeueIntoClosedChannel() {
        val service = service()
        jobs(service).cancel()

        ReflectionHelpers.callInstanceMethod<Unit>(
            service,
            "requestCandidatePagingMode",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1)
        )

        assertNull(ReflectionHelpers.getField<Job?>(service, "candidatePagingModeJob"))
        assertNull(ReflectionHelpers.getField<Int?>(service, "pendingCandidatePagingMode"))
        assertTrue(engineJob.isActive)
    }

    @Test fun ownerConsumerStartsOnlyDequeuedKeysAndKeepsOtherJobsOnTheirScope() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { owner ->
            val service = service(owner)
            val blockerStarted = CompletableDeferred<Thread>()
            val releaseBlocker = CompletableDeferred<Unit>()
            val keyStarted = CompletableDeferred<Thread>()
            val releaseKey = CompletableDeferred<Unit>()
            val blocker = service.postFcitxJob {
                blockerStarted.complete(Thread.currentThread())
                releaseBlocker.await()
            }
            val key = service.postFcitxKeyJob {
                keyStarted.complete(Thread.currentThread())
                releaseKey.await()
            }
            val last = service.postFcitxKeyJob { error("A buffered key must not execute") }
            val consumer = service.startFcitxJobQueue()
            try {
                withTimeout(5_000) {
                    val defaultThread = blockerStarted.await()
                    assertFalse(key.isActive)
                    releaseBlocker.complete(Unit)
                    val keyThread = keyStarted.await()
                    val ownerThread = withContext(owner) { Thread.currentThread() }
                    assertNotSame(ownerThread, defaultThread)
                    assertSame(ownerThread, keyThread)

                    jobs(service).cancel()
                    last.join()
                    assertTrue(last.isCancelled)
                    assertTrue(key.isActive)
                    releaseKey.complete(Unit)
                    consumer.join()
                    assertFalse(blocker.isCancelled)
                    assertFalse(key.isCancelled)
                    assertEquals(2, releasedTickets.get())
                }
            } finally {
                releaseBlocker.complete(Unit)
                releaseKey.complete(Unit)
                consumer.cancel()
            }
        }
    }
}
