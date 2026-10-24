/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import android.content.ContextWrapper
import org.fcitx.fcitx5.android.input.keyboard.AuxBarConfig
import org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition
import org.junit.Assert.*
import org.junit.Test

class LayoutEditHistoryTest {
    private val base = LayoutEditTarget("rime", null, false)
    private val split = LayoutEditTarget("rime", "cangjie", true)
    private fun manager() = LayoutDataManager(ContextWrapper(null))
    private fun key(label: String) = mutableMapOf<String, Any?>("type" to "MacroKey", "label" to label)
    private fun document(label: String): LayoutDocument = manager().let {
        it.entries["rime"] = mutableListOf(mutableListOf(key(label)))
        LayoutDocument.capture(it)
    }

    @Test fun undoAndRedoRestoreTheWholeDocumentIncludingSplitMetadata() {
        val manager = manager()
        manager.entries["rime"] = mutableListOf(mutableListOf(key("a")))
        val before = LayoutDocument.capture(manager)
        val history = LayoutEditHistory()
        history.reset(before, base)
        manager.entries["rime:cangjie:__variant__:split"] = mutableListOf(mutableListOf(key("b")))
        manager.layoutHeightPercentOverrides["rime:cangjie"] = 30
        manager.layoutHeightPercentOverridesLandscape["rime:cangjie"] = 40
        manager.layoutAuxBarConfigs["rime:cangjie"] = AuxBarConfig(AuxBarPosition.Left, 12f)
        manager.layoutAuxBarKeys["rime:cangjie"] = mutableListOf(key("copy"))
        val after = LayoutDocument.capture(manager)
        history.record(after, split)
        history.undo()!!.document.restore(manager)
        assertEquals(before, LayoutDocument.capture(manager))
        assertTrue(manager.layoutAuxBarKeys.isEmpty())
        assertEquals(base, history.current!!.target)
        history.redo()!!.document.restore(manager)
        assertEquals(after, LayoutDocument.capture(manager))
        assertEquals(split, history.current!!.target)
    }

    @Test fun snapshotsDoNotAliasNestedMacrosComposeOverridesOrAuxiliaryKeys() {
        val manager = manager()
        val steps = mutableListOf<Any>(mutableMapOf("type" to "text", "text" to "original"))
        val source = key("a").apply {
            put("tap", mutableMapOf("macro" to steps))
            put("composeOverride", mutableMapOf("displayText" to mutableMapOf("schema" to "old")))
        }
        manager.entries["rime"] = mutableListOf(mutableListOf(source))
        manager.layoutAuxBarKeys["rime"] = mutableListOf(source)
        val snapshot = LayoutDocument.capture(manager)
        steps.clear()
        (source["composeOverride"] as MutableMap<*, *>).clear()
        source["label"] = "changed"
        snapshot.restore(manager)
        val restored = manager.entries["rime"]!![0][0]
        assertEquals("a", restored["label"])
        assertEquals(1, ((restored["tap"] as Map<*, *>)["macro"] as List<*>).size)
        assertEquals(mapOf("displayText" to mapOf("schema" to "old")), restored["composeOverride"])
        (restored["tap"] as MutableMap<*, *>).clear()
        manager.layoutAuxBarKeys["rime"]!![0]["label"] = "aux changed"
        snapshot.restore(manager)
        assertEquals(1, ((manager.entries["rime"]!![0][0]["tap"] as Map<*, *>)["macro"] as List<*>).size)
        assertEquals("a", manager.layoutAuxBarKeys["rime"]!![0]["label"])
    }

    @Test fun selectionChangesDoNotConsumeHistoryOrDestroyRedo() {
        val history = LayoutEditHistory()
        history.reset(document("a"), base)
        history.record(document("b"), base)
        history.undo()
        history.record(document("a"), split)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals(split, history.current!!.target)
        assertEquals(document("b"), history.redo()!!.document)
    }

    @Test fun editingAfterUndoDropsOnlyTheRedoBranch() {
        val history = LayoutEditHistory()
        history.reset(document("a"), base)
        history.record(document("b"), base)
        history.record(document("c"), split)
        history.undo()
        history.record(document("d"), base)
        assertFalse(history.canRedo)
        assertEquals(document("b"), history.undo()!!.document)
        assertEquals(document("a"), history.undo()!!.document)
    }

    @Test fun historyLimitRetainsAValidCurrentStateAndUndoBoundary() {
        val history = LayoutEditHistory(3)
        history.reset(document("a"), base)
        listOf("b", "c", "d").forEach { history.record(document(it), base) }
        assertEquals(document("c"), history.undo()!!.document)
        assertEquals(document("b"), history.undo()!!.document)
        assertNull(history.undo())
        assertEquals(document("c"), history.redo()!!.document)
        assertEquals(document("d"), history.redo()!!.document)
    }

    @Test fun memoryBudgetDropsOldSnapshotsButKeepsOneUndoStep() {
        val history = LayoutEditHistory(limit = 30, byteLimit = 1)
        history.reset(document("a"), base)
        history.record(document("b"), base)
        history.record(document("c"), base)
        assertEquals(document("b"), history.undo()!!.document)
        assertNull(history.undo())
        assertEquals(document("c"), history.redo()!!.document)
    }

    @Test fun switchingProfilesResetsHistoryAndCannotRestoreAnotherFile() {
        val history = LayoutEditHistory()
        history.reset(document("profile one"), base)
        history.record(document("edited one"), base)
        history.reset(document("profile two"), split)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(document("profile two"), history.current!!.document)
    }
}
