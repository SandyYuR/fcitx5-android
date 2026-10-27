/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class LayoutSaveSnapshotTest {
    @get:Rule val temp = TemporaryFolder()
    private fun manager() = LayoutDataManager(appContext).apply {
        entries["default"] = mutableListOf(mutableListOf(mutableMapOf("type" to "AlphabetKey", "main" to "a", "alt" to "!")))
    }
    @Test fun editsAfterCaptureStayDirtyAndNeverEnterTheSavedBaseline() {
        val manager = manager()
        val file = temp.newFile()
        val snapshot = manager.captureSaveSnapshot()
        manager.entries.getValue("default")[0][0]["main"] = "b"
        assertTrue(snapshot.saveToFile(file))
        manager.acceptSavedSnapshot(snapshot)
        assertTrue(file.readText().contains("\"a\""))
        assertFalse(file.readText().contains("\"b\""))
        assertTrue(manager.hasChanges())
    }
    @Test fun nestedMacrosAreDeepCopiedRatherThanShared() {
        val manager = manager()
        val steps = mutableListOf<Any>("a")
        val macro = mutableMapOf<String, Any?>("type" to "MacroKey", "tap" to mutableMapOf("macro" to steps))
        manager.entries.getValue("default")[0][0] = macro
        val snapshot = manager.captureSaveSnapshot()
        steps[0] = "b"
        val captured = snapshot.entries.getValue("default")[0][0]["tap"] as Map<*, *>
        assertEquals(listOf("a"), captured["macro"])
    }
    @Test fun anExistingEmptyFileIsMarkedAsCorrupt() {
        val manager = manager()
        manager.loadFromFile(temp.newFile())
        assertTrue(manager.loadedFromCorruptFile)
    }
}
