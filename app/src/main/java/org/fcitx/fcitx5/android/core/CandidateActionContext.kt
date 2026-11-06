/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** The editor and candidate list to which a displayed candidate action belongs. */
data class CandidateActionContext(val inputSession: Long, val version: Long)

/** Capture both the source index and its meaning before a candidate reaches the UI. */
data class CandidateSelectionTarget(val context: CandidateActionContext, val index: Int, val global: Boolean)
