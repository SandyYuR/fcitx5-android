package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EngineLifecycleControllerTest {
    @Test fun inputIsEnabledBeforeReadinessIsPublished() = runBlocking {
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + owner)
        val registry = FcitxLifecycleRegistry()
        var enabled = false
        var published = false
        val controller = EngineLifecycleController(scope, registry, {}, {}, {
            assertEquals(FcitxLifecycle.State.STARTING, registry.currentState)
            enabled = true
        }, {}, {}, {
            assertEquals(FcitxLifecycle.State.READY, registry.currentState)
            assertTrue(enabled)
            published = true
        })
        try {
            controller.start()
            withContext(owner) {}
            assertTrue(published)
            controller.stopAsync().await()
        } finally { scope.cancel(); owner.close() }
    }

    @Test fun stopDuringInitializationInvalidatesImmediatelyAndNeverPublishesReady() = runBlocking {
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + owner)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val registry = FcitxLifecycleRegistry()
        var ready = false
        var invalidated = false
        var finalized = false
        val controller = EngineLifecycleController(scope, registry, {}, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }, { ready = true }, { invalidated = true }, { finalized = true })
        try {
            controller.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closed = controller.stopAsync()
            assertTrue(invalidated)
            assertEquals(FcitxLifecycle.State.STOPPING, registry.currentState)
            assertFalse(closed.isCompleted)
            release.countDown()
            withTimeout(5000) { closed.await() }
            assertTrue(finalized)
            assertFalse(ready)
            assertEquals(FcitxLifecycle.State.STOPPED, registry.currentState)
        } finally { release.countDown(); scope.cancel(); owner.close() }
    }

    @Test fun reconnectWaitsForNativeCloseAndStartsExactlyOnce() = exerciseReconnect(cancelRestart = false)
    @Test fun anotherDisconnectCancelsThePendingReconnect() = exerciseReconnect(cancelRestart = true)

    private fun exerciseReconnect(cancelRestart: Boolean) = runBlocking {
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + owner)
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val registry = FcitxLifecycleRegistry()
        val order = mutableListOf<String>()
        val controller = EngineLifecycleController(scope, registry, {}, { order += "start" }, {}, {}, {
            closing.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            order += "stop"
        })
        try {
            controller.start()
            withContext(owner) {}
            val stopped = controller.stopAsync()
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            controller.start()
            controller.start()
            if (cancelRestart) assertSame(stopped, controller.stopAsync())
            assertEquals(FcitxLifecycle.State.STOPPING, registry.currentState)
            release.countDown()
            withTimeout(5000) { stopped.await() }
            // Restart request is queued behind close, and initialization behind that request.
            withContext(owner) {}
            withContext(owner) {}
            assertEquals(if (cancelRestart) listOf("start", "stop") else listOf("start", "stop", "start"), order)
            assertEquals(if (cancelRestart) FcitxLifecycle.State.STOPPED else FcitxLifecycle.State.READY, registry.currentState)
        } finally { release.countDown(); scope.cancel(); owner.close() }
    }
}
