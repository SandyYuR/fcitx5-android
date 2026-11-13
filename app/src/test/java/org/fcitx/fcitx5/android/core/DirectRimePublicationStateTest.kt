/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.fcitx.directrime.Snapshot
import org.junit.Assert.*
import org.junit.Test

class DirectRimePublicationStateTest {
    private val initialEntry = InputMethodEntry("Before binding")
    private val entry = InputMethodEntry("Rime")
    private val menuInputs = mutableListOf<Snapshot>()
    private var clockCalls = 0

    private fun state() = DirectRimePublicationState(
        initialEntry,
        buildStatusActions = { value ->
            menuInputs += value
            arrayOf(Action(1, false, false, false, "rime", "", value.schemaName, value.schemaName, null))
        },
        commitClock = { 8_000L + ++clockCalls },
    )

    private fun snapshot(
        generation: Long = 7,
        revision: Long = 1,
        handled: Boolean = false,
        commit: String = "",
    ) = Snapshot(
        generation, revision, 2, handled, 0, commit, "ni", "fixture", false,
        0, true, 2, arrayOf("你"), arrayOf("注"), arrayOf("同标签"),
        "Fixture", 0, "你", arrayOf("1"), tabsRevision = 8,
    )

    private fun panel(value: Snapshot, hint: String = "", hintId: Long = 0) =
        DirectRimePresentation.inputPanel(value, "ComposingText", false, true, hint, "", hintId)

    private fun build(
        state: DirectRimePublicationState,
        value: Snapshot,
        previous: Snapshot? = null,
        forceCandidates: Boolean = false,
        optionsChanged: Boolean = false,
        pagingMode: Int = 1,
        presentation: FcitxEvent.InputPanelEvent.Data = panel(value),
        ime: InputMethodEntry = entry,
    ) = state.buildBatch(
        value,
        previous,
        DirectRimePresentation.clientPreedit(value, "ComposingText"),
        presentation,
        ime,
        pagingMode,
        optionsChanged,
        forceCandidates,
    )

    private fun List<FcitxEvent<*>>.paged() = filterIsInstance<FcitxEvent.PagedCandidateEvent>().single()

    @Test fun newSessionPublishesSideEffectsFirstAndStampsTheDisplayedTargets() {
        val state = state()
        val value = snapshot(handled = true, commit = "你")
        val client = DirectRimePresentation.clientPreedit(value, "ComposingText")
        val panel = panel(value)
        val statistics = InputOperationStatistics(12, "fixture", "Fixture", 3, false, true, 100, 50)

        val batch = state.buildBatch(value, null, client, panel, entry, 1, false,
            statistics = statistics, statisticsKey = ' '.code)

        assertEquals(
            listOf(FcitxEvent.EventType.StatisticsKey, FcitxEvent.EventType.Commit,
                FcitxEvent.EventType.ClientPreedit, FcitxEvent.EventType.InputPanel,
                FcitxEvent.EventType.PagedCandidate, FcitxEvent.EventType.Change,
                FcitxEvent.EventType.StatusArea),
            batch.map { it.eventType },
        )
        assertTrue(batch.all { it.inputSession == value.generation })
        val count = batch[0] as FcitxEvent.StatisticsKeyEvent
        val commit = batch[1] as FcitxEvent.CommitStringEvent
        assertEquals(' '.code, count.sym)
        assertSame(statistics, count.statistics)
        assertSame(statistics, commit.statistics)
        assertEquals("你", commit.data.text)
        assertEquals(-1, commit.data.cursor)
        assertEquals(8_001L, commit.commitProducedNanos)
        assertEquals(1, clockCalls)

        val candidates = batch.paged()
        val target = requireNotNull(candidates.data.candidates.single().selectionTarget)
        assertEquals(CandidateActionContext(7, state.candidateVersion), candidates.candidateActionContext)
        assertEquals(candidates.candidateActionContext, target.context)
        assertEquals(0, target.index)
        assertFalse(target.global)
        assertEquals(CandidateTabActionTarget(CandidateTabActionContext(7, 8), 0),
            panel.tabs.single().tabTarget)
        assertSame(client, state.clientPreeditCached)
        assertSame(panel, state.inputPanelCached)
        assertSame(entry, state.inputMethodEntryCached)
        assertSame(state.statusAreaActionsCached, (batch.last() as FcitxEvent.StatusAreaEvent).data.actions)
        assertSame(value, menuInputs.single())
        assertFalse(state.isNewSession(7))
        assertTrue(state.isNewSession(8))
    }

    @Test fun handledOperationInvalidatesOldTargetsEvenWhenTheVisibleFirstPageIsEqual() {
        val state = state()
        val first = snapshot()
        val old = build(state, first).paged()
        val oldTarget = requireNotNull(old.data.candidates.single().selectionTarget)
        val version = state.candidateVersion
        val handled = snapshot(revision = 2, handled = true)

        val batch = build(state, handled, first)

        assertEquals(listOf(FcitxEvent.EventType.PagedCandidate), batch.map { it.eventType })
        val current = batch.paged()
        assertEquals(old.data, current.data)
        assertNotSame(old.data.candidates.single(), current.data.candidates.single())
        assertEquals(version + 1, state.candidateVersion)
        val currentTarget = requireNotNull(current.data.candidates.single().selectionTarget)
        assertNotEquals(oldTarget.context, currentTarget.context)
        assertEquals(current.candidateActionContext, currentTarget.context)
        assertEquals(CandidateActionContext(7, version), old.candidateActionContext)
        assertEquals(oldTarget, old.data.candidates.single().selectionTarget)
        assertEquals(1, menuInputs.size)
        assertEquals(0, clockCalls)
    }

    @Test fun forcedRefreshPreservesSelectionVersionAndDoesNotReplayThePreviousCommit() {
        val state = state()
        val committed = snapshot(handled = true, commit = "你")
        val first = build(state, committed).paged()
        val version = state.candidateVersion
        val refreshed = Snapshot.reusePresentation(committed, 2)

        val batch = build(state, refreshed, committed, forceCandidates = true)

        assertEquals(listOf(FcitxEvent.EventType.PagedCandidate), batch.map { it.eventType })
        assertEquals(version, state.candidateVersion)
        assertEquals(first.candidateActionContext, batch.paged().candidateActionContext)
        assertEquals(first.data.candidates.single().selectionTarget,
            batch.paged().data.candidates.single().selectionTarget)
        assertEquals(1, clockCalls)
        assertEquals(1, menuInputs.size)

        val next = Snapshot.reusePresentation(refreshed, 3)
        assertTrue(build(state, next, refreshed).isEmpty())
        assertEquals(version, state.candidateVersion)
        assertFalse(state.isNewSession(next.generation))
        assertEquals(1, clockCalls)
        assertEquals(1, menuInputs.size)
    }

    @Test fun optionAndPagingChangesInvalidateTargetsIndependentlyOfCandidateText() {
        val state = state()
        val first = snapshot()
        val firstCandidates = build(state, first).paged()
        val firstVersion = state.candidateVersion
        val options = snapshot(revision = 2)

        val optionBatch = build(state, options, first, optionsChanged = true)

        assertEquals(listOf(FcitxEvent.EventType.PagedCandidate, FcitxEvent.EventType.StatusArea),
            optionBatch.map { it.eventType })
        assertEquals(firstCandidates.data, optionBatch.paged().data)
        assertEquals(firstVersion + 1, state.candidateVersion)
        assertEquals(2, menuInputs.size)
        val optionTarget = optionBatch.paged().data.candidates.single().selectionTarget
        val next = snapshot(revision = 3)

        val layoutBatch = build(state, next, options, pagingMode = 0)

        assertEquals(listOf(FcitxEvent.EventType.Candidate), layoutBatch.map { it.eventType })
        val listed = layoutBatch.single() as FcitxEvent.CandidateListEvent
        assertEquals(firstVersion + 2, state.candidateVersion)
        assertNotEquals(optionTarget?.context, listed.data.candidates.single().selectionTarget?.context)
        assertEquals(CandidateActionContext(7, state.candidateVersion), listed.candidateActionContext)
        assertFalse(requireNotNull(listed.data.candidates.single().selectionTarget).global)
        assertEquals(2, menuInputs.size)
    }

    @Test fun clearingBindingInvalidatesCandidatesAndRepublishesTheNextSession() {
        val state = state()
        val first = snapshot()
        val oldTarget = build(state, first).paged().data.candidates.single().selectionTarget
        val oldVersion = state.candidateVersion
        val actions = state.statusAreaActionsCached
        val ime = state.inputMethodEntryCached

        val cleared = state.clearBinding(8)

        assertEquals(listOf(FcitxEvent.EventType.ClientPreedit, FcitxEvent.EventType.InputPanel,
            FcitxEvent.EventType.PagedCandidate), cleared.map { it.eventType })
        assertTrue(cleared.all { it.inputSession == 8L })
        assertSame(FormattedText.Empty, (cleared[0] as FcitxEvent.ClientPreeditEvent).data)
        assertSame(state.inputPanelCached, (cleared[1] as FcitxEvent.InputPanelEvent).data)
        assertSame(FcitxEvent.PagedCandidateEvent.Data.Empty, cleared.paged().data)
        assertSame(FormattedText.Empty, state.clientPreeditCached)
        assertEquals(FcitxEvent.InputPanelEvent.Data(), state.inputPanelCached)
        assertSame(actions, state.statusAreaActionsCached)
        assertSame(ime, state.inputMethodEntryCached)
        assertEquals(oldVersion + 1, state.candidateVersion)
        assertTrue(state.isNewSession(7))
        assertTrue(state.isNewSession(8))

        val rebound = build(state, snapshot(generation = 8), first)

        assertEquals(listOf(FcitxEvent.EventType.ClientPreedit, FcitxEvent.EventType.InputPanel,
            FcitxEvent.EventType.PagedCandidate, FcitxEvent.EventType.StatusArea),
            rebound.map { it.eventType })
        assertTrue(rebound.all { it.inputSession == 8L })
        assertEquals(oldVersion + 2, state.candidateVersion)
        assertNotEquals(oldTarget?.context, rebound.paged().data.candidates.single().selectionTarget?.context)
        assertEquals(2, menuInputs.size)
        assertFalse(state.isNewSession(8))
    }

    @Test fun expiringStatusHintOnlyPublishesThePanelAndPreservesCandidateAndCommitState() {
        val state = state()
        val committed = snapshot(commit = "你")
        val candidates = build(state, committed).paged()
        val value = Snapshot.reusePresentation(committed, 2)
        val hinted = panel(value, "切换模式", 55)
        build(state, value, committed, presentation = hinted)
        val client = state.clientPreeditCached
        val ime = state.inputMethodEntryCached
        val actions = state.statusAreaActionsCached
        val version = state.candidateVersion

        val expired = state.expireStatusHint(7)

        assertEquals(listOf(FcitxEvent.EventType.InputPanel), expired.map { it.eventType })
        assertEquals(7L, expired.single().inputSession)
        val panel = (expired.single() as FcitxEvent.InputPanelEvent).data
        assertSame(FormattedText.Empty, panel.auxUp)
        assertEquals(0L, panel.statusHintId)
        assertSame(hinted.preedit, panel.preedit)
        assertSame(hinted.auxDown, panel.auxDown)
        assertSame(hinted.tabs, panel.tabs)
        assertSame(hinted.auxBarActions, panel.auxBarActions)
        assertSame(client, state.clientPreeditCached)
        assertSame(ime, state.inputMethodEntryCached)
        assertSame(actions, state.statusAreaActionsCached)
        assertEquals(version, state.candidateVersion)
        assertFalse(state.isNewSession(7))
        assertTrue(state.expireStatusHint(7).isEmpty())
        assertSame(panel, state.inputPanelCached)

        val refreshed = build(state, Snapshot.reusePresentation(value, 3), value,
            forceCandidates = true, presentation = panel)
        assertEquals(listOf(FcitxEvent.EventType.PagedCandidate), refreshed.map { it.eventType })
        assertEquals(candidates.candidateActionContext, refreshed.paged().candidateActionContext)
        assertEquals(version, state.candidateVersion)
        assertEquals(1, clockCalls)
        assertEquals(1, menuInputs.size)
    }

    @Test fun unavailablePanelPreservesOtherCachesAndTheUnboundLifecycleEvent() {
        val state = state()
        val first = snapshot(commit = "你")
        val candidates = build(state, first).paged()
        val client = state.clientPreeditCached
        val ime = state.inputMethodEntryCached
        val actions = state.statusAreaActionsCached
        val version = state.candidateVersion

        val unavailable = state.showUnavailable("准备失败")

        assertEquals(listOf(FcitxEvent.EventType.InputPanel), unavailable.map { it.eventType })
        assertEquals(0L, unavailable.single().inputSession)
        val panel = (unavailable.single() as FcitxEvent.InputPanelEvent).data
        assertSame(panel, state.inputPanelCached)
        assertEquals("准备失败", panel.auxUp.toString())
        assertSame(FormattedText.Empty, panel.preedit)
        assertSame(FormattedText.Empty, panel.auxDown)
        assertTrue(panel.tabs.isEmpty())
        assertEquals(0L, panel.statusHintId)
        assertSame(client, state.clientPreeditCached)
        assertSame(ime, state.inputMethodEntryCached)
        assertSame(actions, state.statusAreaActionsCached)
        assertEquals(version, state.candidateVersion)
        assertFalse(state.isNewSession(7))

        val restored = build(state, Snapshot.reusePresentation(first, 2), first, forceCandidates = true)
        assertEquals(listOf(FcitxEvent.EventType.InputPanel, FcitxEvent.EventType.PagedCandidate),
            restored.map { it.eventType })
        assertEquals(candidates.candidateActionContext, restored.paged().candidateActionContext)
        assertEquals(version, state.candidateVersion)
        assertEquals(1, clockCalls)
        assertEquals(1, menuInputs.size)
    }

    @Test fun clearingStatusActionsRebuildsOnlyTheMenuOnTheNextPublication() {
        val state = state()
        val first = snapshot()
        build(state, first)
        val client = state.clientPreeditCached
        val panel = state.inputPanelCached
        val ime = state.inputMethodEntryCached
        val version = state.candidateVersion

        state.clearStatusActions()

        assertTrue(state.statusAreaActionsCached.isEmpty())
        assertSame(client, state.clientPreeditCached)
        assertSame(panel, state.inputPanelCached)
        assertSame(ime, state.inputMethodEntryCached)
        assertEquals(version, state.candidateVersion)
        assertFalse(state.isNewSession(7))

        val next = Snapshot.reusePresentation(first, 2)
        val rebuilt = build(state, next, first)
        assertEquals(listOf(FcitxEvent.EventType.StatusArea), rebuilt.map { it.eventType })
        assertSame(state.statusAreaActionsCached, (rebuilt.single() as FcitxEvent.StatusAreaEvent).data.actions)
        assertEquals(version, state.candidateVersion)
        assertEquals(2, menuInputs.size)

        val stable = Snapshot.reusePresentation(next, 3)
        assertTrue(build(state, stable, next).isEmpty())
        assertEquals(2, menuInputs.size)
        val renamedEntry = entry.copy(name = "Rime renamed")
        val changed = build(state, Snapshot.reusePresentation(stable, 4), stable, ime = renamedEntry)
        assertEquals(listOf(FcitxEvent.EventType.Change, FcitxEvent.EventType.StatusArea),
            changed.map { it.eventType })
        assertSame(renamedEntry, state.inputMethodEntryCached)
        assertEquals(version, state.candidateVersion)
        assertEquals(3, menuInputs.size)
    }
}
