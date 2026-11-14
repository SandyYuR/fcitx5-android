/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.fcitx.directrime.Snapshot

/**
 * Presentation state whose complete batches and candidate changes stay on the native owner. The
 * backend validates native results and delivers returned batches through its existing event queue.
 * Startup hints retain the lifecycle caller's volatile panel update; this class never calls native
 * or starts work. Frontend readers observe the volatile caches and candidate version.
 */
internal class DirectRimePublicationState(
    initialEntry: InputMethodEntry,
    private val buildStatusActions: (Snapshot) -> Array<Action>,
    private val commitClock: CommitClock,
) {
    fun interface CommitClock {
        fun now(): Long
    }

    private var publishedGeneration = Long.MIN_VALUE
    private var candidateEventCached: FcitxEvent<*>? = null
    @Volatile var candidateVersion = 0L
        private set
    @Volatile var inputMethodEntryCached = initialEntry
        private set
    @Volatile var statusAreaActionsCached = emptyArray<Action>()
        private set
    @Volatile var clientPreeditCached = FormattedText.Empty
        private set
    @Volatile var inputPanelCached = FcitxEvent.InputPanelEvent.Data()
        private set

    fun isNewSession(generation: Long): Boolean = publishedGeneration != generation

    fun buildBatch(
        value: Snapshot,
        previous: Snapshot?,
        client: FormattedText,
        panel: FcitxEvent.InputPanelEvent.Data,
        ime: InputMethodEntry,
        pagingMode: Int,
        optionsChanged: Boolean,
        forceCandidates: Boolean = false,
        statistics: InputOperationStatistics? = null,
        statisticsKey: Int? = null,
    ): List<FcitxEvent<*>> {
        val newSession = isNewSession(value.generation)
        val imeChanged = ime != inputMethodEntryCached
        val statusChanged = newSession || optionsChanged || imeChanged || statusAreaActionsCached.isEmpty()
        val actions = if (statusChanged) buildStatusActions(value) else statusAreaActionsCached
        val clientChanged = newSession || client != clientPreeditCached
        val panelChanged = newSession || panel != inputPanelCached
        clientPreeditCached = client
        inputPanelCached = panel
        inputMethodEntryCached = ime
        statusAreaActionsCached = actions

        val batch = mutableListOf<FcitxEvent<*>>()
        if (statistics != null && statisticsKey != null && value.ready)
            batch += FcitxEvent.StatisticsKeyEvent(statistics, statisticsKey)
        if (value.commit.isNotEmpty()) {
            batch += FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data(value.commit, -1)).apply {
                commitProducedNanos = commitClock.now()
                this.statistics = statistics
            }
        }
        if (clientChanged) batch += FcitxEvent.ClientPreeditEvent(client)
        if (panelChanged) batch += FcitxEvent.InputPanelEvent(panel)

        val candidates = DirectRimePresentation.candidates(value)
        val candidateEvent = DirectRimePresentation.candidateEvent(value, pagingMode, candidates)
        // The unchanged first page can still hide a changed lazy tail. Preserve invalidation for
        // handled operations and composition changes independently of visible candidate equality.
        val invalidateCandidates = newSession || value.handled || optionsChanged ||
            previous?.preedit != value.preedit || previous?.cursorBytes != value.cursorBytes ||
            candidateEvent != candidateEventCached
        if (invalidateCandidates) candidateVersion++
        val candidateContext = CandidateActionContext(value.generation, candidateVersion)
        candidates.forEachIndexed { index, candidate ->
            // Native snapshots always contain a local Rime page, regardless of frontend layout.
            candidate.selectionTarget = CandidateSelectionTarget(candidateContext, index, global = false)
        }
        candidateEvent.candidateActionContext = candidateContext
        if (invalidateCandidates || forceCandidates) batch += candidateEvent
        candidateEventCached = candidateEvent
        if (imeChanged) batch += FcitxEvent.IMChangeEvent(ime)
        if (statusChanged) batch += FcitxEvent.StatusAreaEvent(FcitxEvent.StatusAreaEvent.Data(actions, ime))
        batch.forEach { it.inputSession = value.generation }
        publishedGeneration = value.generation
        return batch
    }

    fun clearBinding(generation: Long): List<FcitxEvent<*>> {
        clientPreeditCached = FormattedText.Empty
        inputPanelCached = FcitxEvent.InputPanelEvent.Data()
        candidateEventCached = null
        candidateVersion++
        publishedGeneration = Long.MIN_VALUE
        // Keep the menu and input-method entry until a new binding publishes their replacements.
        return listOf(
            FcitxEvent.ClientPreeditEvent(clientPreeditCached),
            FcitxEvent.InputPanelEvent(inputPanelCached),
            FcitxEvent.PagedCandidateEvent(FcitxEvent.PagedCandidateEvent.Data.Empty),
        ).onEach { it.inputSession = generation }
    }

    fun clearStatusActions() {
        statusAreaActionsCached = emptyArray()
    }

    fun expireStatusHint(generation: Long): List<FcitxEvent<*>> {
        val panel = inputPanelCached.copy(auxUp = FormattedText.Empty, statusHintId = 0)
        if (panel == inputPanelCached) return emptyList()
        inputPanelCached = panel
        return listOf(FcitxEvent.InputPanelEvent(panel).apply { inputSession = generation })
    }

    fun showUnavailable(message: String): List<FcitxEvent<*>> {
        val hint = if (message.isEmpty()) FormattedText.Empty else {
            FormattedText(arrayOf(message), intArrayOf(0), message.length)
        }
        inputPanelCached = FcitxEvent.InputPanelEvent.Data(
            FormattedText.Empty, hint, FormattedText.Empty, emptyArray(),
        )
        // Startup/lifecycle panels retain their existing unbound event semantics.
        return listOf(FcitxEvent.InputPanelEvent(inputPanelCached))
    }
}
