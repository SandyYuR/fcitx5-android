/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.fcitx.directrime.Snapshot

/** Stateless conversion from a native snapshot to frontend presentation values. */
internal object DirectRimePresentation {
    private val EMPTY_TABS = emptyArray<CandidateAction>()

    fun clientPreedit(snapshot: Snapshot, preeditMode: String): FormattedText = when (preeditMode) {
        "ComposingText" -> formatted(snapshot.preedit, snapshot.cursorUtf16, underline = true)
        "CommitPreview" -> formatted(snapshot.preview, underline = true)
        else -> FormattedText.Empty
    }

    fun inputPanel(
        snapshot: Snapshot,
        preeditMode: String,
        directInput: Boolean,
        bindingUsable: Boolean,
        statusHint: String,
        startupError: String,
        statusHintId: Long,
    ): FcitxEvent.InputPanelEvent.Data {
        val tabs = if (snapshot.tabs.isEmpty()) EMPTY_TABS else {
            val context = CandidateTabActionContext(snapshot.generation, snapshot.tabsRevision)
            Array(snapshot.tabs.size) { index ->
                CandidateAction(
                    index,
                    snapshot.tabs[index],
                    false,
                    "",
                    false,
                    false,
                    CandidateTabActionTarget(context, index),
                )
            }
        }
        val auxUp = when {
            directInput && bindingUsable -> FormattedText.Empty
            snapshot.ready -> formatted(statusHint)
            else -> formatted(startupError.ifEmpty { snapshot.gateMessage })
        }
        return FcitxEvent.InputPanelEvent.Data(
            if (preeditMode == "No") formatted(snapshot.preedit, snapshot.cursorUtf16) else FormattedText.Empty,
            auxUp,
            FormattedText.Empty,
            tabs,
            statusHintId = if (snapshot.ready) statusHintId else 0,
        )
    }

    fun candidates(snapshot: Snapshot): Array<CandidateWord> = Array(snapshot.candidates.size) { index ->
        // Codes remain separate metadata; only the schema-provided comment is displayed.
        CandidateWord(
            snapshot.labels.getOrElse(index) { "${(index + 1) % 10} " },
            snapshot.candidates[index],
            snapshot.comments.getOrElse(index) { "" },
        )
    }

    fun candidateEvent(
        snapshot: Snapshot,
        pagingMode: Int,
        candidates: Array<CandidateWord>,
    ): FcitxEvent<*> = if (pagingMode != 0) {
        FcitxEvent.PagedCandidateEvent(
            FcitxEvent.PagedCandidateEvent.Data(
                candidates,
                snapshot.highlighted,
                FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet,
                snapshot.page > 0,
                !snapshot.lastPage,
            )
        )
    } else {
        FcitxEvent.CandidateListEvent(FcitxEvent.CandidateListEvent.Data(-1, candidates))
    }

    private fun formatted(
        text: String,
        cursor: Int = text.length,
        underline: Boolean = false,
    ): FormattedText = if (text.isEmpty()) {
        FormattedText.Empty
    } else {
        FormattedText(
            arrayOf(text),
            intArrayOf(if (underline) TextFormatFlag.Underline.flag else 0),
            cursor.coerceIn(0, text.length),
        )
    }
}
