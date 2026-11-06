/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.core

data class CandidateWord @JvmOverloads constructor(
    val label: String,
    val text: String,
    val comment: String,
    val spaceBetweenComment: Boolean = true
) {
    // Set once by the producer before publication. Presentation equality deliberately
    // excludes this metadata so a new candidate version need not re-layout identical text.
    internal var selectionTarget: CandidateSelectionTarget? = null

    fun textWithComment(): String {
        return buildString {
            append(text)
            if (comment.isNotBlank()) {
                if (spaceBetweenComment) {
                    append(" ")
                }
                append(comment)
            }
        }
    }

    companion object {
        val Empty = CandidateWord("", "", "", false)
    }
}

internal fun Array<out CandidateWord>.sameSelectionTargets(other: Array<out CandidateWord>): Boolean {
    if (size != other.size) return false
    for (i in indices) if (this[i].selectionTarget != other[i].selectionTarget) return false
    return true
}
