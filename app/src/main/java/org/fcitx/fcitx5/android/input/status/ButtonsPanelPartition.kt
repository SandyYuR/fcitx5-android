/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import org.fcitx.fcitx5.android.input.config.ConfigurableButton

/**
 * Pure partition/merge logic for the in-keyboard "adjust buttons" panel.
 *
 * Split out of [ButtonsAdjustingWindow] so it can be unit-tested without a keyboard: the panel
 * can only display a subset of what ButtonsLayout.json may contain, and getting the round trip
 * wrong silently deleted the user's buttons.
 */
object ButtonsPanelPartition {

    /**
     * @param visible entries the panel can render, in order
     * @param dropped entries it cannot, each with its index in the source list
     */
    data class Result(
        val visible: List<ConfigurableButton>,
        val dropped: List<Pair<Int, ConfigurableButton>>
    )

    /**
     * Split [source] into displayable and non-displayable entries.
     *
     * An entry is displayable when it has a known configurable id (or carries a macro) and is
     * the first occurrence of that id **within this section**. De-duplication is per-section on
     * purpose: the toolbar and the status area are independent, and sharing one "seen" set used
     * to drop a status-area button merely because the toolbar already had the same id.
     *
     * @param configurableIds ids this panel knows how to render
     * @param excludedIds ids that are managed elsewhere and must never appear (e.g. the fixed
     *        `input_method_options` entry)
     */
    fun partition(
        source: List<ConfigurableButton>,
        configurableIds: Set<String>,
        excludedIds: Set<String> = emptySet()
    ): Result {
        val visible = mutableListOf<ConfigurableButton>()
        val dropped = mutableListOf<Pair<Int, ConfigurableButton>>()
        val seen = mutableSetOf<String>()
        source.forEachIndexed { index, button ->
            val displayable = button.id !in excludedIds &&
                (button.id in configurableIds || button.macroSteps != null) &&
                seen.add(button.id)
            if (displayable) visible += button else dropped += index to button
        }
        return Result(visible, dropped)
    }

    /**
     * Splice [dropped] entries back into [visible] at (approximately) their original indices, so
     * saving from the panel preserves entries it never displayed.
     */
    fun merge(
        visible: List<ConfigurableButton>,
        dropped: List<Pair<Int, ConfigurableButton>>
    ): List<ConfigurableButton> {
        if (dropped.isEmpty()) return visible
        val result = visible.toMutableList()
        dropped.sortedBy { it.first }.forEach { (index, button) ->
            result.add(index.coerceIn(0, result.size), button)
        }
        return result
    }
}
