/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.fcitx.directrime.Snapshot
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestDelegate
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestShadow
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, NativeTestShadow::class, FcitxDaemonTestShadow::class])
class CandidateSelectionQueueTest {
    @Test fun queuedTapKeepsItsDisplayedVersionAndItsNativeIndexOrigin() = runBlocking {
        val backend = DirectRimeBackend(RuntimeEnvironment.getApplication())
        FcitxDaemonTestShadow.install(CandidateQueueDaemonDelegate(backend))
        val owner: ExecutorCoroutineDispatcher = ReflectionHelpers.getField(backend, "owner")
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val jobs: Channel<Job> = ReflectionHelpers.getField(service, "jobs")
        try {
            ReflectionHelpers.setField(backend, "started", true)
            ReflectionHelpers.setField(backend, "schemas", listOf("fixture" to "Fixture"))
            ReflectionHelpers.getField<FcitxLifecycleRegistry>(backend, "registry").apply {
                postEvent(FcitxLifecycle.Event.ON_START); postEvent(FcitxLifecycle.Event.ON_READY)
            }
            val generation = backend.invalidateInputSession()
            backend.prepareInputSession(generation, "editor", CapabilityFlags.DefaultFlags, true)
            ReflectionHelpers.getField<InputKeyGate>(backend, "keyGate").apply {
                running(); resolve(generation, true)
            }
            CandidateSelectionNativeShadow.reset(generation)
            NativeTestShadow.delegate = CandidateSelectionNativeShadow()
            val connection = object : FcitxConnection {
                override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = runBlocking { block(backend) }
                override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = block(backend)
                override fun runIfReady(block: suspend FcitxAPI.() -> Unit) { backend.lifeCycleScope.launch { block(backend) } }
                override val lifecycleScope get() = backend.lifeCycleScope
            }
            ReflectionHelpers.setField(service, "fcitx", connection)

            fun displayedTarget(): CandidateSelectionTarget {
                val event: FcitxEvent.PagedCandidateEvent = ReflectionHelpers.getField(backend, "candidateEventCached")
                return requireNotNull(event.data.candidates[0].selectionTarget)
            }
            backend.sendKey('n', 0u, 0, false, 0)
            val old = displayedTarget()
            service.postFcitxKeyJob { sendKey('x', 0u, 0, false, 0) }
            service.postCandidateSelection(old)
            withTimeout(5_000) { jobs.receive().join(); jobs.receive().join() }
            assertTrue("The old tap must not reach native selection", CandidateSelectionNativeShadow.selections.isEmpty())

            service.postCandidateSelection(displayedTarget())
            withTimeout(5_000) { jobs.receive().join() }
            assertEquals(listOf(1 to 0), CandidateSelectionNativeShadow.selections)
            assertEquals("新候选", CandidateSelectionNativeShadow.committed)

            // Expanded data retain their global index even while the compact UI is paged.
            val expanded = backend.getCandidates(16, 2)
            val target = requireNotNull(expanded[1].selectionTarget)
            assertEquals(17, target.index)
            assertTrue(target.global)
            service.postCandidateSelection(target)
            withTimeout(5_000) { jobs.receive().join() }
            assertEquals(11 to 17, CandidateSelectionNativeShadow.selections.last())
            assertFalse(backend.selectCandidateIfCurrent(old.index, old.global, old.context))
        } finally {
            jobs.cancel()
            backend.lifeCycleScope.coroutineContext[Job]?.cancelAndJoin()
            ReflectionHelpers.setField(backend, "started", false)
            ReflectionHelpers.getField<CoroutineScope>(backend, "engineScope").cancel()
            val events = ReflectionHelpers.getField<InputEventQueue>(backend, "events")
            ReflectionHelpers.getField<Channel<Unit>>(events, "available").cancel()
            owner.close()
            FcitxDaemonTestShadow.reset()
        }
    }
}

internal class CandidateSelectionNativeShadow : NativeTestDelegate {
    companion object {
        var generation = 0L
        var revision = 0L
        var word = ""
        var committed = ""
        val selections = mutableListOf<Pair<Int, Int>>()
        fun reset(value: Long) { generation = value; revision = 0; word = ""; committed = ""; selections.clear() }
        private fun snapshot(commit: String = "") = Snapshot(generation, ++revision, 0, true, 0,
            commit, "ni", "fixture", false, 0, true, 2, arrayOf(word), emptyArray(), emptyArray(),
            "Fixture", 0, word, arrayOf("1"))
    }
    override fun options(): Array<String> = emptyArray()
    override fun key(value: Long, sym: Int, mask: Int, release: Boolean): Snapshot {
        check(value == generation)
        word = if (sym == 'n'.code) "旧候选" else "新候选"
        return snapshot()
    }
    override fun action(value: Long, previous: Long, action: Int, index: Int, name: String): Snapshot {
        check(value == generation && previous == revision)
        check(action == 1 || action == 11)
        selections += action to index
        committed = if (action == 1) word else "扩展$index"
        return snapshot(committed)
    }
    override fun candidates(value: Long, offset: Int, limit: Int): Array<String> {
        check(value == generation)
        return (offset until offset + limit).flatMap { listOf("扩展$it", "") }.toTypedArray()
    }
}

/** Supply a scoped engine while retaining the actual service queue and InputKeyGate. */
private class CandidateQueueDaemonDelegate(private val backend: DirectRimeBackend) : FcitxDaemonTestDelegate {
    override fun getCurrentInputSession() = backend.inputSession
    override fun requireInputSession() = backend.requireInputSession(backend.inputSession)
    override fun captureInputKey() = backend.captureInputKey()
    override fun canDeliverInputKey(ticket: InputKeyGate.Ticket) = backend.canDeliverInputKey(ticket)
    override fun releaseInputKey(ticket: InputKeyGate.Ticket) = backend.releaseInputKey(ticket)
}
