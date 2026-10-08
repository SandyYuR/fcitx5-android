/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.floor

private const val VERTICAL_AUX_BAR_TAB_MAX_FULL_ITEMS = 6
private const val VERTICAL_AUX_BAR_TAB_VISIBLE_ROWS = 6.5f

/**
 * Resolve the vertical item heights used by the Rime syllable-tab list.
 *
 * Six or fewer tabs share the available height. Longer lists keep a
 * six-and-a-half-row viewport so the seventh tab is partially visible and the list
 * remains naturally scrollable.
 */
internal fun resolveVerticalAuxBarTabItemHeights(
    containerHeight: Int,
    itemCount: Int
): IntArray {
    if (containerHeight <= 0 || itemCount <= 0) return IntArray(0)

    if (itemCount <= VERTICAL_AUX_BAR_TAB_MAX_FULL_ITEMS) {
        val baseHeight = containerHeight / itemCount
        val remainder = containerHeight % itemCount
        return IntArray(itemCount) { index ->
            baseHeight + if (index < remainder) 1 else 0
        }
    }

    val itemHeight = floor(containerHeight / VERTICAL_AUX_BAR_TAB_VISIBLE_ROWS)
        .toInt()
        .coerceAtLeast(1)
    return IntArray(itemCount) { itemHeight }
}
