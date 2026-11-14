/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, NativeTestShadow::class])
class DirectRimePublicationContractTest {
    private lateinit var backend: DirectRimeBackend
    private lateinit var owner: ExecutorCoroutineDispatcher
    private lateinit var native: PublicationNative
    private lateinit var initial: Snapshot
    private var generation = 0L
    private val publicationState: DirectRimePublicationState
        get() = ReflectionHelpers.getField(backend, "publicationState")

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
        initial = snapshot(revision = 1)
        ReflectionHelpers.setField(backend, "snapshot", initial)
        native = PublicationNative(generation)
        NativeTestShadow.delegate = native
    }

    @After fun close() {
        ReflectionHelpers.setField(backend, "started", false)
        ReflectionHelpers.getField<CoroutineScope>(backend, "engineScope").cancel()
        backend.lifeCycleScope.cancel()
        val events = ReflectionHelpers.getField<InputEventQueue>(backend, "events")
        ReflectionHelpers.getField<Channel<Unit>>(events, "available").cancel()
        owner.close()
    }

    private fun snapshot(
        generation: Long = this.generation,
        revision: Long,
        gate: Int = 0,
        handled: Boolean = false,
        commit: String = "",
        inputStatistics: Long = -1,
        tabsRevision: Long = 8
    ) = Snapshot(
        generation, revision, 0, handled, gate, commit, "ni", "fixture", false,
        0, true, 2, arrayOf("你"), arrayOf("注"), arrayOf("同标签"),
        "Fixture", 0, "你", arrayOf("1"), inputStatistics, tabsRevision
    )

    @Test fun completeBatchKeepsSideEffectsBeforePresentationAndStampsDisplayedTargets() = runBlocking {
        native.nextKey = snapshot(
            revision = 2,
            handled = true,
            commit = "你",
            inputStatistics = 2L shl 1,
            tabsRevision = 9
        )
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { backend.eventFlow.take(7).toList() }
        }

        withContext(InputStatisticsSource(generation, 7) { true }) {
            backend.sendKey(' ', 0u, 0, false, 0)
        }

        val events = received.await()
        assertEquals(
            listOf(
                FcitxEvent.EventType.StatisticsKey,
                FcitxEvent.EventType.Commit,
                FcitxEvent.EventType.ClientPreedit,
                FcitxEvent.EventType.InputPanel,
                FcitxEvent.EventType.PagedCandidate,
                FcitxEvent.EventType.Change,
                FcitxEvent.EventType.StatusArea
            ),
            events.map { it.eventType }
        )
        assertTrue(events.all { it.inputSession == generation })

        val statistics = events[0] as FcitxEvent.StatisticsKeyEvent
        val commit = events[1] as FcitxEvent.CommitStringEvent
        assertEquals(7L, statistics.statistics.epoch)
        assertEquals(' '.code, statistics.sym)
        assertEquals("你", commit.data.text)
        assertSame(statistics.statistics, commit.statistics)

        val panel = events[3] as FcitxEvent.InputPanelEvent
        val tab = requireNotNull(panel.data.tabs.single().tabTarget)
        assertEquals(CandidateTabActionContext(generation, 9), tab.context)
        assertEquals(0, tab.id)

        val candidates = events[4] as FcitxEvent.PagedCandidateEvent
        assertEquals(backend.candidateActionContext, candidates.candidateActionContext)
        val target = requireNotNull(candidates.data.candidates.single().selectionTarget)
        assertEquals(backend.candidateActionContext, target.context)
        assertEquals(0, target.index)
        assertFalse(target.global)
    }

    @Test fun staleGenerationAndExpiredGateLeavePublicationStateUntouched() = runBlocking {
        ReflectionHelpers.setField(publicationState, "candidateVersion", 13L)
        ReflectionHelpers.setField(publicationState, "publishedGeneration", generation)
        val cachedCandidate = FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data.Empty)
        ReflectionHelpers.setField(publicationState, "candidateEventCached", cachedCandidate)
        val client = backend.clientPreeditCached
        val panel = backend.inputPanelCached
        val ime = backend.inputMethodEntryCached
        val status = backend.statusAreaActionsCached
        val unexpected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(300) { backend.eventFlow.first() }
        }

        native.nextKey = snapshot(generation = generation + 1, revision = 2, handled = true)
        backend.sendKey('a', 0u, 1, false, 0)
        native.nextKey = snapshot(revision = 3, gate = 3, handled = true)
        backend.sendKey('b', 0u, 2, false, 0)

        assertNull(unexpected.await())
        assertSame(initial, ReflectionHelpers.getField<Snapshot>(backend, "snapshot"))
        assertEquals(13L, backend.candidateActionContext.version)
        assertEquals(generation, ReflectionHelpers.getField<Long>(publicationState, "publishedGeneration"))
        assertSame(cachedCandidate, ReflectionHelpers.getField<FcitxEvent<*>>(publicationState, "candidateEventCached"))
        assertSame(client, backend.clientPreeditCached)
        assertSame(panel, backend.inputPanelCached)
        assertSame(ime, backend.inputMethodEntryCached)
        assertSame(status, backend.statusAreaActionsCached)
    }

    @Test fun unchangedReleaseAdvancesOnlyTheNativeSnapshot() = runBlocking {
        ReflectionHelpers.setField(publicationState, "candidateVersion", 21L)
        ReflectionHelpers.setField(publicationState, "publishedGeneration", generation)
        val cachedCandidate = FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data.Empty)
        ReflectionHelpers.setField(publicationState, "candidateEventCached", cachedCandidate)
        val client = backend.clientPreeditCached
        val panel = backend.inputPanelCached
        val ime = backend.inputMethodEntryCached
        val status = backend.statusAreaActionsCached
        val next = Snapshot.reusePresentation(initial, 2)
        native.nextKey = next
        val unexpected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(300) { backend.eventFlow.first() }
        }

        backend.sendKey('a', 0u, 1, true, 0)

        assertNull(unexpected.await())
        assertSame(next, ReflectionHelpers.getField<Snapshot>(backend, "snapshot"))
        assertEquals(2L, ReflectionHelpers.getField<Snapshot>(backend, "snapshot").revision)
        assertEquals(21L, backend.candidateActionContext.version)
        assertEquals(generation, ReflectionHelpers.getField<Long>(publicationState, "publishedGeneration"))
        assertSame(cachedCandidate, ReflectionHelpers.getField<FcitxEvent<*>>(publicationState, "candidateEventCached"))
        assertSame(client, backend.clientPreeditCached)
        assertSame(panel, backend.inputPanelCached)
        assertSame(ime, backend.inputMethodEntryCached)
        assertSame(status, backend.statusAreaActionsCached)
    }

    @Test fun forcedRefreshResendsCandidatesWithoutReplayingCommitOrAdvancingVersion() = runBlocking {
        val committed = snapshot(revision = 2, handled = true, commit = "你")
        native.nextKey = committed
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) {
                backend.eventFlow.filter {
                    it is FcitxEvent.CommitStringEvent || it is FcitxEvent.PagedCandidateEvent
                }.take(3).toList()
            }
        }

        backend.sendKey(' ', 0u, 0, false, 0)
        val firstVersion = backend.candidateActionContext
        val refreshed = Snapshot.reusePresentation(committed, 3)
        native.nextAction = refreshed
        backend.setCandidatePagingMode(1)

        val events = received.await()
        assertEquals(
            listOf(
                FcitxEvent.EventType.Commit,
                FcitxEvent.EventType.PagedCandidate,
                FcitxEvent.EventType.PagedCandidate
            ),
            events.map { it.eventType }
        )
        assertEquals(1, events.count { it is FcitxEvent.CommitStringEvent })
        val candidates = events.filterIsInstance<FcitxEvent.PagedCandidateEvent>()
        assertEquals(firstVersion, candidates[0].candidateActionContext)
        assertEquals(firstVersion, candidates[1].candidateActionContext)
        assertEquals(firstVersion, backend.candidateActionContext)
        assertEquals(
            candidates[0].data.candidates.single().selectionTarget?.context,
            candidates[1].data.candidates.single().selectionTarget?.context
        )
        assertSame(refreshed, ReflectionHelpers.getField<Snapshot>(backend, "snapshot"))
        assertEquals(listOf(13), native.actions)
    }
}

private class PublicationNative(private val generation: Long) : NativeTestDelegate {
    var nextKey: Snapshot? = null
    var nextAction: Snapshot? = null
    val actions = mutableListOf<Int>()

    override fun key(value: Long, sym: Int, mask: Int, release: Boolean): Snapshot {
        check(value == generation)
        return requireNotNull(nextKey).also { nextKey = null }
    }

    override fun action(value: Long, previous: Long, action: Int, index: Int, name: String): Snapshot {
        check(value == generation)
        check(action == 13)
        actions += action
        return requireNotNull(nextAction).also {
            check(previous + 1 == it.revision)
            nextAction = null
        }
    }

    override fun options(): Array<String> = emptyArray()
}
