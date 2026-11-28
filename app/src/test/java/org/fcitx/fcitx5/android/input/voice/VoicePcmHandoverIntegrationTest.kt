/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercise the real Manager handover, offer and feed entry points without starting AudioRecord. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class VoicePcmHandoverIntegrationTest {
    private data class Audio(val pcm: ByteArray, val pts: Long)

    private class Provider : IVoiceInputProvider.Stub() {
        val received = LinkedBlockingQueue<Audio>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blockFirst = false
        override fun isAvailable() = true
        override fun getPreferredConfig() = Bundle()
        override fun configure(params: Bundle?) {}
        override fun startSession(callback: IVoiceInputCallback?) {}
        override fun feedAudio(pcm: ByteArray?, offset: Int, len: Int, ptsMs: Long) {
            received.add(Audio(requireNotNull(pcm).copyOfRange(offset, offset + len), ptsMs))
            if (blockFirst && ptsMs == 0L) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test provider was not released" }
            }
        }
        override fun endStream() {}
        override fun cancelSession() {}
        override fun stopSession() {}
    }

    private val manager = VoiceInputProviderManager
    private lateinit var service: FcitxInputMethodService
    private val provider = Provider()
    private val errors = CopyOnWriteArrayList<String?>()
    private val callback = object : IVoiceInputCallback.Stub() {
        override fun onReady() {}
        override fun onVolumeLevel(rms: Int) {}
        override fun onPartialResult(text: String?) {}
        override fun onSegmentFinal(text: String?) {}
        override fun onSessionEnded() {}
        override fun onError(code: Int, message: String?) { errors.add(message) }
    }
    private val fieldNames = listOf(
        "activeProvider", "activeCallback", "activeEditorSession", "sessionGeneration",
        "sessionActive", "voiceSessionTerminalized", "finishing", "floatingFallbackActive",
        "activeAudioFeedQueue", "activeAudioFeedJob", "activeCapture", "readyHandledGeneration",
        "activeSessionConfig", "preRollActive", "pcmQueueMetrics", "lastSlowFeedWarnElapsed",
    )
    private lateinit var saved: Map<String, Any?>
    private lateinit var savedPreRoll: List<Any>
    private var savedOverflowGeneration = -1
    private val channels = mutableListOf<Channel<Any>>()
    private val jobs = mutableListOf<Job>()
    private val threads = mutableListOf<Thread>()
    private val gates = mutableListOf<CountDownLatch>()
    private val threadError = AtomicReference<Throwable?>()
    private val overflowGeneration: AtomicInteger
        get() = ReflectionHelpers.getField(manager, "overflowAbortGeneration")
    private val preRoll: ArrayDeque<Any>
        get() = ReflectionHelpers.getField(manager, "preRollBuffer")
    private val metrics: VoicePcmQueueMetrics
        get() = ReflectionHelpers.getField(manager, "pcmQueueMetrics")

    @Before fun setUp() {
        service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        service.editorSession.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)
        saved = fieldNames.associateWith { ReflectionHelpers.getField<Any?>(manager, it) }
        savedPreRoll = preRoll.toList()
        savedOverflowGeneration = overflowGeneration.getAndSet(-1)
        preRoll.clear()
        val config = Class.forName("${manager.javaClass.name}\$SessionConfig")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val capture = VoiceInputAudioCapture(service, onPcm = { _, _, _, _ -> }, onError = {})
        val state = mapOf(
            "activeProvider" to provider, "activeCallback" to callback,
            "activeEditorSession" to service.editorSession.capture(), "sessionGeneration" to 10,
            "sessionActive" to true, "voiceSessionTerminalized" to false, "finishing" to false,
            "floatingFallbackActive" to false, "activeAudioFeedQueue" to null,
            "activeAudioFeedJob" to null, "activeCapture" to capture,
            "readyHandledGeneration" to -1, "activeSessionConfig" to config,
            "preRollActive" to true, "pcmQueueMetrics" to VoicePcmQueueMetrics(),
            "lastSlowFeedWarnElapsed" to 0L,
        )
        state.forEach { (name, value) -> ReflectionHelpers.setField(manager, name, value) }
    }

    @After fun tearDown() {
        gates.forEach { it.countDown() }
        provider.release.countDown()
        threads.forEach { it.join(5_000); assertFalse("worker is still running", it.isAlive) }
        channels.forEach { it.cancel() }
        jobs.forEach { it.cancel() }
        runBlocking { withTimeout(5_000) { jobs.forEach { it.join() } } }
        ReflectionHelpers.getField<Job?>(manager, "activeAudioFeedJob")?.let {
            it.cancel()
            runBlocking { withTimeout(5_000) { it.join() } }
        }
        service.lifecycleScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        preRoll.clear()
        preRoll.addAll(savedPreRoll)
        overflowGeneration.set(savedOverflowGeneration)
        saved.forEach { (name, value) -> ReflectionHelpers.setField(manager, name, value) }
        threadError.get()?.let { throw AssertionError("worker failed", it) }
    }

    private fun call(name: String, vararg args: Any?): Any? = manager.javaClass.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(manager, *args)

    private fun packet(pts: Long): Any = Class.forName("${manager.javaClass.name}\$QueuedAudio")
        .getDeclaredConstructor(ByteArray::class.java, java.lang.Long.TYPE)
        .apply { isAccessible = true }.newInstance(bytes(pts), pts)

    private fun bytes(pts: Long) = ByteArray(6) { index -> ((pts / 100) + index).toByte() }
    private fun seed(count: Int) { repeat(count) { preRoll.addLast(packet(it * 100L)) } }
    private fun channel(capacity: Int) = Channel<Any>(capacity).also { channels.add(it) }
    private fun handover(queue: Channel<Any>, generation: Int = 10) =
        call("handOverPreRoll", service, queue, generation) as Boolean
    private fun offer(pts: Long, generation: Int = 10) {
        call("offerAudioPacket", service, packet(pts), generation)
    }
    private fun startFeed(queue: Channel<Any>) =
        (call("launchAudioFeedJob", service, queue) as Job).also { jobs.add(it) }
    private fun drain(queue: Channel<Any>, job: Job) {
        queue.close()
        runBlocking { withTimeout(5_000) { job.join() } }
    }
    private fun ready() {
        call("onProviderReady", service, { _: Int -> }, { message: String -> errors.add(message); Unit })
    }
    private fun worker(block: () -> Unit) = Thread {
        try { block() } catch (error: Throwable) { threadError.compareAndSet(null, error) }
    }.also { threads.add(it); it.start() }
    private fun await(gate: CountDownLatch) { assertTrue("latch timed out", gate.await(5, TimeUnit.SECONDS)) }
    private fun overflowAcrossTransition(expectedMetrics: VoicePcmQueueMetrics, transition: () -> Unit) {
        val lock: Any = ReflectionHelpers.getField(manager, "preRollLock")
        val started = CountDownLatch(1)
        val overflowThread = synchronized(lock) {
            val thread = worker {
                started.countDown()
                call("onAudioQueueOverflow", service, 3_100L, 10, expectedMetrics)
            }
            await(started)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (thread.isAlive && System.nanoTime() < deadline &&
                (thread.state != Thread.State.BLOCKED || thread.stackTrace.none {
                    it.methodName.contains("onAudioQueueOverflow")
                })
            ) Thread.yield()
            assertEquals("overflow must acquire the session monitor before changing state",
                Thread.State.BLOCKED, thread.state)
            assertTrue(thread.stackTrace.any { it.methodName.contains("onAudioQueueOverflow") })
            transition()
            thread
        }
        overflowThread.join(5_000)
        assertFalse("overflow worker is still running", overflowThread.isAlive)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(errors.isEmpty())
    }

    private fun assertPackets(expectedPts: List<Long>) {
        val packets = provider.received.toList()
        assertEquals(expectedPts, packets.map { it.pts })
        packets.forEach { assertArrayEquals(bytes(it.pts), it.pcm); assertEquals(0, it.pcm.size % 6) }
    }

    @Test fun handoverKeepsLivePacketBehindEveryPreRollPacket() {
        seed(30)
        val firstReplay = CountDownLatch(1)
        val releaseReplay = CountDownLatch(1).also { gates.add(it) }
        val liveStarted = CountDownLatch(1)
        val backing = channel(31)
        val lock: Any = ReflectionHelpers.getField(manager, "preRollLock")
        val sends = AtomicInteger()
        val queue = object : Channel<Any> by backing {
            override fun trySend(element: Any): ChannelResult<Unit> {
                if (sends.incrementAndGet() == 1) {
                    assertTrue(Thread.holdsLock(lock))
                    assertNull(ReflectionHelpers.getField<Any?>(manager, "activeAudioFeedQueue"))
                    firstReplay.countDown()
                    check(releaseReplay.await(5, TimeUnit.SECONDS))
                }
                return backing.trySend(element)
            }
        }
        val handoverThread = worker { assertTrue(handover(queue)) }
        await(firstReplay)
        val liveThread = worker { liveStarted.countDown(); offer(3_000L) }
        await(liveStarted)
        releaseReplay.countDown()
        handoverThread.join(5_000); liveThread.join(5_000)
        assertFalse(handoverThread.isAlive); assertFalse(liveThread.isAlive)
        assertEquals(31, metrics.currentDepth)
        assertEquals(31, metrics.maxDepth)
        drain(queue, startFeed(queue))
        assertPackets((0..30).map { it * 100L })
        assertEquals(0, metrics.currentDepth)
        assertTrue(errors.isEmpty())
    }

    @Test fun actualReadyCountsFullPreRollAndDuplicatePreservesTheBlockedFeed() {
        provider.blockFirst = true
        seed(31)
        ready()
        await(provider.entered)
        val queue: Channel<Any> = ReflectionHelpers.getField(manager, "activeAudioFeedQueue")
        channels.add(queue)
        val job: Job = ReflectionHelpers.getField(manager, "activeAudioFeedJob")
        jobs.add(job)
        val originalMetrics = metrics
        assertEquals(31, metrics.maxDepth)
        assertEquals(3_000L, metrics.maxHeadAgeMs)
        assertEquals(30, metrics.currentDepth)
        offer(3_100L)
        assertEquals(31, metrics.currentDepth)
        ready()
        assertSame(queue, ReflectionHelpers.getField<Any>(manager, "activeAudioFeedQueue"))
        assertSame(job, ReflectionHelpers.getField<Any>(manager, "activeAudioFeedJob"))
        assertSame(originalMetrics, metrics)
        provider.release.countDown()
        drain(queue, job)
        assertPackets((0..31).map { it * 100L })
        assertEquals(32, metrics.calls)
        assertEquals(0, metrics.currentDepth)
        assertTrue(errors.isEmpty())
    }

    @Test fun fullQueueAbortsOnceInsteadOfSilentlyLosingOrLaterAcceptingPackets() {
        seed(31)
        val queue = channel(31)
        assertTrue(handover(queue))
        offer(3_100L)
        assertEquals(1, metrics.overloads)
        assertEquals(31, metrics.currentDepth)
        val first = queue.tryReceive().getOrThrow()
        metrics.onDequeued()
        assertEquals(0L, ReflectionHelpers.getField<Long>(first, "ptsMs").toLong())
        offer(3_200L)
        assertEquals(30, metrics.currentDepth)
        assertEquals(1, metrics.overloads)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Provider too slow: voice PCM queue full"), errors.toList())
    }

    @Test fun failedReplayNeverPublishesAPartialQueue() {
        seed(31)
        val queue = channel(1)
        assertFalse(handover(queue))
        assertNull(ReflectionHelpers.getField<Any?>(manager, "activeAudioFeedQueue"))
        assertEquals(0, metrics.currentDepth)
        assertEquals(1, metrics.overloads)
        assertTrue(queue.isClosedForSend)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Provider too slow: voice PCM queue full"), errors.toList())
    }

    @Test fun staleGenerationCannotHandoverOrEnqueueIntoTheReplacement() {
        seed(2)
        val replacement = channel(31)
        val replacementMetrics = metrics
        ReflectionHelpers.setField(manager, "activeAudioFeedQueue", replacement)
        ReflectionHelpers.setField(manager, "sessionGeneration", 11)
        val stale = channel(31)
        assertFalse(handover(stale))
        offer(200L)
        assertSame(replacement, ReflectionHelpers.getField<Any>(manager, "activeAudioFeedQueue"))
        assertSame(replacementMetrics, metrics)
        assertEquals(2, preRoll.size)
        assertEquals(0, metrics.currentDepth)
        assertTrue(replacement.tryReceive().isFailure)
        assertTrue(stale.isClosedForSend)
    }

    @Test fun consumerCannotPassEnqueueAccountingEvenIfTrySendHasAlreadyDelivered() {
        val sent = CountDownLatch(1)
        val releaseSend = CountDownLatch(1).also { gates.add(it) }
        val backing = channel(31)
        val queue = object : Channel<Any> by backing {
            override fun trySend(element: Any): ChannelResult<Unit> {
                val result = backing.trySend(element)
                sent.countDown()
                check(releaseSend.await(5, TimeUnit.SECONDS))
                return result
            }
        }
        ReflectionHelpers.setField(manager, "activeAudioFeedQueue", queue)
        val job = startFeed(queue)
        val producer = worker { offer(0L) }
        await(sent)
        assertNull(provider.received.poll(100, TimeUnit.MILLISECONDS))
        releaseSend.countDown()
        producer.join(5_000)
        assertFalse(producer.isAlive)
        drain(queue, job)
        assertPackets(listOf(0L))
        assertEquals(0, metrics.currentDepth)
        assertEquals(1, metrics.calls)
    }

    @Test fun overflowWaitingForSessionLockCannotOverwriteNewGenerationState() {
        val oldMetrics = metrics
        lateinit var replacement: VoicePcmQueueMetrics
        overflowAcrossTransition(oldMetrics) {
            assertEquals(11, call("beginAudioSession"))
            replacement = metrics
            assertNotSame(oldMetrics, replacement)
            // 新代已占用的标记也不能被迟到的旧代 getAndSet 覆盖。
            overflowGeneration.set(11)
        }
        assertEquals(11, overflowGeneration.get())
        assertEquals(0, replacement.overloads)
        assertEquals(0, oldMetrics.overloads)
        assertFalse(ReflectionHelpers.getField<Boolean>(manager, "voiceSessionTerminalized"))
    }

    @Test fun overflowWaitingForSessionLockCannotMarkTerminalizedSession() {
        val oldMetrics = metrics
        overflowAcrossTransition(oldMetrics) {
            assertEquals(10, call("terminalizeAudioSession"))
        }
        assertSame(oldMetrics, metrics)
        assertEquals(-1, overflowGeneration.get())
        assertEquals(0, oldMetrics.overloads)
        assertTrue(ReflectionHelpers.getField<Boolean>(manager, "voiceSessionTerminalized"))
    }

    @Test fun overflowWaitingForSessionLockCannotCountAgainstSameGenerationReplacement() {
        val oldMetrics = metrics
        val replacementQueue = channel(31)
        lateinit var replacement: VoicePcmQueueMetrics
        overflowAcrossTransition(oldMetrics) {
            seed(2)
            assertTrue(handover(replacementQueue))
            replacement = metrics
            assertNotSame(oldMetrics, replacement)
        }
        assertEquals(10, ReflectionHelpers.getField<Int>(manager, "sessionGeneration").toInt())
        assertEquals(-1, overflowGeneration.get())
        assertEquals(0, oldMetrics.overloads)
        assertEquals(0, replacement.overloads)
        assertEquals(2, replacement.currentDepth)
        assertSame(replacementQueue, ReflectionHelpers.getField<Any>(manager, "activeAudioFeedQueue"))
    }

    @Test fun cancelledOldFeedCannotChangeNewSessionMetrics() {
        provider.blockFirst = true
        seed(3)
        val queue = channel(31)
        assertTrue(handover(queue))
        val oldMetrics = metrics
        val job = startFeed(queue)
        await(provider.entered)
        ReflectionHelpers.setField(manager, "sessionGeneration", 11)
        val replacement = VoicePcmQueueMetrics()
        ReflectionHelpers.setField(manager, "pcmQueueMetrics", replacement)
        job.cancel()
        provider.release.countDown()
        runBlocking { withTimeout(5_000) { job.join() } }
        assertEquals(0, replacement.currentDepth)
        assertEquals(0, replacement.calls)
        assertEquals(0, replacement.maxDepth)
        assertEquals(0, oldMetrics.currentDepth)
        assertPackets(listOf(0L))
    }
}
