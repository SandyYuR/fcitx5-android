/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import org.junit.Assert.*
import org.junit.Test

class LayoutKeyOperationsTest {
    private fun row(vararg labels: String) = labels.map {
        mutableMapOf<String, Any?>("type" to "MacroKey", "label" to it)
    }.toMutableList()

    @Test fun movingRightUsesInsertionSlotBeforeRemoval() {
        val rows = mutableListOf(row("a", "b", "c"))
        val result = LayoutKeyOperations.move(rows, EditorKeyPosition(0, 0), EditorKeyPosition(0, 3))
        assertEquals(listOf("b", "c", "a"), rows[0].map { it["label"] })
        assertEquals(EditorKeyPosition(0, 2), result)
    }

    @Test fun movingLeftAndDroppingBesideItselfDoNotDuplicateKeys() {
        val rows = mutableListOf(row("a", "b", "c"))
        LayoutKeyOperations.move(rows, EditorKeyPosition(0, 2), EditorKeyPosition(0, 0))
        assertEquals(listOf("c", "a", "b"), rows[0].map { it["label"] })
        LayoutKeyOperations.move(rows, EditorKeyPosition(0, 1), EditorKeyPosition(0, 2))
        assertEquals(listOf("c", "a", "b"), rows[0].map { it["label"] })
    }

    @Test fun movingAcrossRowsPreservesAllKeyPropertiesAndSupportsAnEmptyRow() {
        val rows = mutableListOf(row("a", "b"), row())
        val key = rows[0][0].apply { put("tap", mapOf("macro" to listOf(mapOf("type" to "text", "text" to "test")))) }
        val result = LayoutKeyOperations.move(rows, EditorKeyPosition(0, 0), EditorKeyPosition(1, 0))
        assertEquals(EditorKeyPosition(1, 0), result)
        assertSame(key, rows[1][0])
        assertEquals(listOf("b"), rows[0].map { it["label"] })
        assertEquals(2, rows.sumOf { it.size })
    }

    @Test fun invalidDropNeverRemovesTheSourceKey() {
        val rows = mutableListOf(row("a"))
        assertNull(LayoutKeyOperations.move(rows, EditorKeyPosition(0, 0), EditorKeyPosition(2, 0)))
        assertNull(LayoutKeyOperations.move(rows, EditorKeyPosition(0, 0), EditorKeyPosition(0, 2)))
        assertNull(LayoutKeyOperations.move(rows, EditorKeyPosition(0, 2), EditorKeyPosition(0, 0)))
        assertEquals("a", rows[0].single()["label"])
    }

    @Test fun manualBoundaryIsUniqueAndAutomaticClearsAllMarkers() {
        val row = row("a", "b", "c")
        row.forEach { it["splitAfter"] = true }
        LayoutKeyOperations.setSplitBoundary(row, 1)
        assertEquals(listOf(null, true, null), row.map { it["splitAfter"] })
        LayoutKeyOperations.setSplitBoundary(row, null)
        assertTrue(row.none { it.containsKey("splitAfter") })
        LayoutKeyOperations.setSplitBoundary(row, 2)
        assertTrue(row.none { it.containsKey("splitAfter") })
    }

    @Test fun splitRowSettingMovesWithTheRowAndSurvivesFirstKeyEdits() {
        val rows = mutableListOf(row("a", "b", "c"), row("x", "y"))
        LayoutKeyOperations.setSplitRowDisabled(rows[0], true)
        LayoutKeyOperations.setSplitRowDisabled(rows[1], false)

        LayoutKeyOperations.move(rows, EditorKeyPosition(0, 0), EditorKeyPosition(1, 0))
        assertTrue(LayoutKeyOperations.isSplitRowDisabled(rows[0]))
        assertFalse(LayoutKeyOperations.isSplitRowDisabled(rows[1]))
        assertEquals(listOf(true, null), rows[0].map { it["splitRowDisabled"] })
        assertTrue(rows[1].all { !it.containsKey("splitRowDisabled") })

        LayoutKeyOperations.replaceKey(rows[0], 0, row("replacement").single())
        assertTrue(LayoutKeyOperations.isSplitRowDisabled(rows[0]))
        assertEquals(listOf(true, null), rows[0].map { it["splitRowDisabled"] })
        LayoutKeyOperations.removeKey(rows[0], 0)
        assertTrue(LayoutKeyOperations.isSplitRowDisabled(rows[0]))
        assertEquals(true, rows[0].single()["splitRowDisabled"])
    }

    @Test fun reorderingTheFirstKeyKeepsTheSettingOnTheRow() {
        val rows = mutableListOf(row("a", "b", "c"))
        LayoutKeyOperations.setSplitRowDisabled(rows.single(), true)

        LayoutKeyOperations.move(rows, EditorKeyPosition(0, 0), EditorKeyPosition(0, 3))

        assertEquals(listOf("b", "c", "a"), rows.single().map { it["label"] })
        assertEquals(true, rows.single().first()["splitRowDisabled"])
        assertTrue(rows.single().drop(1).all { !it.containsKey("splitRowDisabled") })
    }
}
