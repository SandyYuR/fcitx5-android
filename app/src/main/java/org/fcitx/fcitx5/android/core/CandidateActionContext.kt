/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** The editor and candidate list to which a candidate menu action belongs. */
data class CandidateActionContext(val inputSession: Long, val version: Long)
