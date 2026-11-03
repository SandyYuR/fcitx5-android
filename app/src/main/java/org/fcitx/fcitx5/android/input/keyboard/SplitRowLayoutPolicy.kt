/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** Row-level decisions shared by split rendering and its odd-row key duplication. */
internal object SplitRowLayoutPolicy {
    /** `splitRowDisabled` is stored on the first key of the row. */
    fun shouldSplit(row: List<KeyDef>): Boolean = row.firstOrNull()?.splitRowDisabled != true
}
