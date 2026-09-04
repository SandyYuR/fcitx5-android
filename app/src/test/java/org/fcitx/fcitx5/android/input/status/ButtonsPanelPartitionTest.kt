/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B3: entering the "adjust buttons" panel and dragging once must not delete buttons.
 *
 * The panel only renders a whitelisted subset of ButtonsLayout.json, and it saves the lists it
 * holds. So anything it filtered out has to be remembered and written back, and de-duplication
 * must be per-section — otherwise the same id in both sections lost one of them.
 */
class ButtonsPanelPartitionTest {

    private val configurableIds = setOf("undo", "redo", "clipboard", "theme")

    private fun button(id: String, macro: Boolean = false) = ConfigurableButton(
        id = id,
        macroSteps = if (macro) listOf(MacroStep.Text("x")) else null
    )

    @Test
    fun sameIdInBothSectionsIsKeptInBoth() {
        val topSource = listOf(button("undo"), button("theme"))
        val bottomSource = listOf(button("theme"), button("clipboard"))

        val top = ButtonsPanelPartition.partition(topSource, configurableIds)
        val bottom = ButtonsPanelPartition.partition(bottomSource, configurableIds)

        assertEquals(listOf("undo", "theme"), top.visible.map { it.id })
        assertEquals("theme must survive in the status area too", listOf("theme", "clipboard"), bottom.visible.map { it.id })
        assertEquals(emptyList<Int>(), top.dropped.map { it.first })
        assertEquals(emptyList<Int>(), bottom.dropped.map { it.first })
    }

    @Test
    fun duplicateWithinOneSectionIsDroppedButRemembered() {
        val source = listOf(button("undo"), button("undo"), button("redo"))
        val result = ButtonsPanelPartition.partition(source, configurableIds)
        assertEquals(listOf("undo", "redo"), result.visible.map { it.id })
        assertEquals(listOf(1), result.dropped.map { it.first })
        // Round trip restores every entry.
        assertEquals(
            source.map { it.id },
            ButtonsPanelPartition.merge(result.visible, result.dropped).map { it.id }
        )
    }

    @Test
    fun unknownIdIsPreservedThroughARoundTrip() {
        val source = listOf(button("undo"), button("from_a_newer_build"), button("redo"))
        val result = ButtonsPanelPartition.partition(source, configurableIds)
        assertEquals("panel cannot render the unknown id", listOf("undo", "redo"), result.visible.map { it.id })
        assertEquals(
            "saving must not delete it",
            listOf("undo", "from_a_newer_build", "redo"),
            ButtonsPanelPartition.merge(result.visible, result.dropped).map { it.id }
        )
    }

    @Test
    fun macroButtonWithUnknownIdIsStillDisplayable() {
        val source = listOf(button("custom_1", macro = true))
        val result = ButtonsPanelPartition.partition(source, configurableIds)
        assertEquals(listOf("custom_1"), result.visible.map { it.id })
        assertEquals(emptyList<Int>(), result.dropped.map { it.first })
    }

    @Test
    fun excludedIdIsNeverDisplayedButIsPreserved() {
        val source = listOf(button("undo"), button("input_method_options"))
        val result = ButtonsPanelPartition.partition(
            source,
            configurableIds,
            excludedIds = setOf("input_method_options")
        )
        assertEquals(listOf("undo"), result.visible.map { it.id })
        assertEquals(
            listOf("undo", "input_method_options"),
            ButtonsPanelPartition.merge(result.visible, result.dropped).map { it.id }
        )
    }

    @Test
    fun reorderingVisibleEntriesKeepsDroppedOnesAtTheirIndex() {
        val source = listOf(button("undo"), button("unknown"), button("redo"), button("clipboard"))
        val result = ButtonsPanelPartition.partition(source, configurableIds)
        // User drags "clipboard" to the front of the visible list.
        val reordered = listOf(button("clipboard"), button("undo"), button("redo"))
        val merged = ButtonsPanelPartition.merge(reordered, result.dropped)
        assertEquals("nothing is lost", 4, merged.size)
        assertEquals(listOf("clipboard", "unknown", "undo", "redo"), merged.map { it.id })
    }

    @Test
    fun mergeWithNothingDroppedIsIdentity() {
        val visible = listOf(button("undo"), button("redo"))
        assertEquals(visible, ButtonsPanelPartition.merge(visible, emptyList()))
    }

    @Test
    fun droppedIndexBeyondTheListIsClampedInsteadOfThrowing() {
        // Every entry filtered out: indices are far past the (empty) visible list.
        val source = listOf(button("a"), button("b"), button("c"))
        val result = ButtonsPanelPartition.partition(source, configurableIds = emptySet())
        assertEquals(emptyList<String>(), result.visible.map { it.id })
        assertEquals(
            listOf("a", "b", "c"),
            ButtonsPanelPartition.merge(result.visible, result.dropped).map { it.id }
        )
    }
}
