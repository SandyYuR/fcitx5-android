/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

/**
 * Layout decision for one pass of the Kawaii Bar center row.
 *
 * Pure data, deliberately free of Android types: the row layout is a pure function of the width
 * it is currently measured with, and that function is unit tested
 * (see `KawaiiBarRowMetricsTest`).
 */
internal data class KawaiiBarRowMetrics(
    /** True when every button can be given an equal share of the row. */
    val evenDistribution: Boolean,
    /**
     * Width handed to each button in even distribution mode, in pixels.
     * Zero in scroll mode, where buttons keep their intrinsic width.
     */
    val buttonWidth: Int
)

/**
 * Decide between even distribution and horizontal scrolling for [buttonCount] buttons inside
 * [availableWidth] pixels.
 *
 * [spacing] is the total horizontal margin consumed by one button (its start plus end margin),
 * matching how the row lays its children out. A button never gets less than [minButtonWidth];
 * when the row cannot honour that for every button, the row scrolls instead of squeezing them.
 */
internal fun resolveKawaiiBarRowMetrics(
    availableWidth: Int,
    buttonCount: Int,
    spacing: Int,
    minButtonWidth: Int
): KawaiiBarRowMetrics {
    if (buttonCount <= 0 || availableWidth <= 0) {
        // Nothing to place (or no width yet): stay out of scroll mode so an empty row never
        // shows a scrollbar or swallows horizontal drags.
        return KawaiiBarRowMetrics(evenDistribution = true, buttonWidth = 0)
    }
    val evenWidth = (availableWidth - spacing * buttonCount) / buttonCount
    return if (evenWidth >= minButtonWidth) {
        KawaiiBarRowMetrics(evenDistribution = true, buttonWidth = evenWidth)
    } else {
        KawaiiBarRowMetrics(evenDistribution = false, buttonWidth = 0)
    }
}
