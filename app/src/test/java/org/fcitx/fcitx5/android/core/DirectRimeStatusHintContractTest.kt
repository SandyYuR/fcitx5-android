/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.fcitx.directrime.Snapshot
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Existing Backend queues and publication code with only the shared JNI shadow substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, NativeTestShadow::class])
class DirectRimeStatusHintContractTest {
    private lateinit var backend: DirectRimeBackend
    private lateinit var owner: ExecutorCoroutineDispatcher
    private lateinit var native: HintNative
    private var generation = 0L
    private val hint: DirectRimeStatusHintScheduler
        get() = ReflectionHelpers.getField(backend, "statusHints")
    private val engineScope: CoroutineScope
        get() = ReflectionHelpers.getField(backend, "engineScope")

    @Before fun prepare() {
        backend = DirectRimeBackend(RuntimeEnvironment.getApplication())
        owner = ReflectionHelpers.getField(backend, "owner")
        ReflectionHelpers.setField(backend, "started", true)
        ReflectionHelpers.setField(backend, "schemas", listOf("fixture" to "Fixture"))
        generation = backend.invalidateInputSession()
        backend.prepareInputSession(generation, "editor", CapabilityFlags.DefaultFlags, true)
        ReflectionHelpers.getField<InputKeyGate>(backend, "keyGate").apply {
            running()
            resolve(generation, true)
        }
        val initial = snapshot(1, ascii = false)
        ReflectionHelpers.setField(backend, "snapshot", initial)
        ReflectionHelpers.getField<DirectRimePublicationState>(backend, "publicationState").let {
            ReflectionHelpers.setField(it, "publishedGeneration", generation)
        }
        native = HintNative(initial)
        NativeTestShadow.delegate = native
    }

    @After fun close() {
        ReflectionHelpers.setField(backend, "started", false)
        engineScope.cancel()
        backend.lifeCycleScope.cancel()
        val events = ReflectionHelpers.getField<InputEventQueue>(backend, "events")
        ReflectionHelpers.getField<Channel<Unit>>(events, "available").cancel()
        owner.close()
    }

    private fun snapshot(revision: Long, ascii: Boolean, commit: String = "") = Snapshot(
        generation, revision, 0, true, 0, commit, "", "fixture", ascii,
        0, true, 0, emptyArray(), emptyArray(), emptyArray(),
        "Fixture", 0, "", emptyArray(), -1, 8,
    )

    private suspend fun publishHint(commit: String = ""): Long = coroutineScope {
        native.next = snapshot(2, ascii = true, commit)
        // The status-area event is the final member of this first publication batch.
        val published = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { backend.eventFlow.first { it is FcitxEvent.StatusAreaEvent } }
        }
        backend.sendKey('A', 0u, 1, false, 0)
        published.await()
        backend.inputPanelCached.statusHintId.also { assertTrue(it > 0) }
    }

    @Test fun expiringAHintOnlyPublishesAPanelWithoutNativeRefreshOrCommitReplay() = runBlocking {
        val observed = ConcurrentLinkedQueue<FcitxEvent<*>>()
        val collecting = launch(start = CoroutineStart.UNDISPATCHED) {
            backend.eventFlow.collect { observed.add(it) }
        }
        try {
            val id = publishHint(commit = "已上屏")
            yield()
            val frameTime = SystemClock.uptimeMillis()
            val current = ReflectionHelpers.getField<Snapshot>(backend, "snapshot")
            val version = backend.candidateActionContext
            val commitCount = observed.count { it is FcitxEvent.CommitStringEvent }
            assertEquals(1, commitCount)
            val beforeExpiry = observed.size
            val expired = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) {
                    backend.eventFlow.first {
                        it is FcitxEvent.InputPanelEvent && it.data.statusHintId == 0L
                    }
                }
            }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3))

            backend.statusHintPresented(id, frameTime)
            val event = expired.await() as FcitxEvent.InputPanelEvent
            yield()

            assertEquals(generation, event.inputSession)
            assertEquals(FormattedText.Empty, event.data.auxUp)
            assertEquals("", hint.text)
            assertEquals(0L, hint.id)
            assertEquals(listOf(FcitxEvent.EventType.InputPanel), observed.drop(beforeExpiry).map { it.eventType })
            assertEquals(commitCount, observed.count { it is FcitxEvent.CommitStringEvent })
            assertSame(current, ReflectionHelpers.getField<Snapshot>(backend, "snapshot"))
            assertEquals(2L, current.revision)
            assertEquals(version, backend.candidateActionContext)
            assertEquals(listOf("key", "options"), native.calls)
        } finally { collecting.cancelAndJoin() }
    }

    @Test fun endingTheBindingClearsTheHintAndInvalidatesItsQueuedFrame() = runBlocking {
        val old = publishHint()

        backend.focus(false)
        backend.statusHintPresented(old, SystemClock.uptimeMillis())
        withContext(owner) {}

        assertEquals("", hint.text)
        assertEquals(0L, hint.id)
        assertEquals(0L, backend.inputPanelCached.statusHintId)
        assertNull(ReflectionHelpers.getField<Snapshot?>(backend, "snapshot"))
        assertEquals(listOf("key", "options", "end"), native.calls)
    }

    @Test fun stoppingClearsTheHintBeforeFinalizingNativeAndRejectsOldFrames() = runBlocking {
        val old = publishHint()
        val registry = ReflectionHelpers.getField<FcitxLifecycleRegistry>(backend, "registry")
        registry.postEvent(FcitxLifecycle.Event.ON_START)
        registry.postEvent(FcitxLifecycle.Event.ON_READY)
        val ownerThread = withContext(owner) { Thread.currentThread().id }
        native.onStop = {
            assertEquals("", hint.text)
            assertEquals(0L, hint.id)
            assertNotEquals(generation, backend.inputSession)
            assertEquals(ownerThread, Thread.currentThread().id)
        }

        backend.stop()
        backend.statusHintPresented(old, SystemClock.uptimeMillis())

        assertEquals(FcitxLifecycle.State.STOPPED, backend.lifecycle.currentState)
        assertEquals("", hint.text)
        assertEquals(0L, hint.id)
        assertEquals(listOf("key", "options", "stop"), native.calls)
    }

    @Test fun startingPublishesTheUnavailablePanelSynchronouslyWithoutClearingATransientHint() = runBlocking {
        val old = publishHint()
        val text = hint.text
        val version = backend.candidateActionContext
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        engineScope.launch {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            // The owner cannot execute the asynchronous initialization block yet.
            backend.start()

            assertEquals(FcitxLifecycle.State.STARTING, backend.lifecycle.currentState)
            assertEquals("正在准备中州韵…", backend.inputPanelCached.auxUp.toString())
            assertEquals(0L, backend.inputPanelCached.statusHintId)
            assertEquals(old, hint.id)
            assertEquals(text, hint.text)
            assertEquals(generation, backend.inputSession)
            assertEquals(version, backend.candidateActionContext)
            assertEquals(listOf("key", "options"), native.calls)
        } finally {
            // Cancel queued initialization before releasing the owner; no assets/native start runs.
            engineScope.cancel()
            release.countDown()
            withContext(owner) {}
        }
    }
}

private class HintNative(private val initial: Snapshot) : NativeTestDelegate {
    var next = initial
    val calls = mutableListOf<String>()
    var onStop: () -> Unit = {}

    override fun key(value: Long, sym: Int, mask: Int, release: Boolean): Snapshot {
        calls += "key"
        return next
    }

    override fun options(): Array<String> { calls += "options"; return emptyArray() }
    override fun end(value: Long): Snapshot { calls += "end"; return initial }
    override fun stop() { calls += "stop"; onStop() }
}
