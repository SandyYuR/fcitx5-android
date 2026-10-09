/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class CandidateIndexBadgePosition(override val stringRes: Int) : ManagedPreferenceEnum {
    TopLeft(R.string.top_left),
    TopRight(R.string.top_right),
    BottomRight(R.string.bottom_right),
    BottomLeft(R.string.bottom_left),
}
