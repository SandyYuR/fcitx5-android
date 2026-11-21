/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.floating

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * Position for the floating candidates window.
 */
enum class FloatingCandidatesPosition(override val stringRes: Int) : ManagedPreferenceEnum {
    /**
     * Top-left corner
     */
    TopLeft(R.string.top_left),
    /**
     * Top-right corner
     */
    TopRight(R.string.top_right),
    /**
     * Bottom-left corner of the available area
     */
    BottomLeft(R.string.bottom_left),
    /**
     * Bottom-right corner of the available area
     */
    BottomRight(R.string.bottom_right),
    /**
     * Next to the text cursor, flipping above or below it when space is short
     */
    FollowCursor(R.string.follow_cursor),
    /** Keep the standard physical-keyboard anchor at the beginning of the composing text. */
    Default(R.string.default_)
}
