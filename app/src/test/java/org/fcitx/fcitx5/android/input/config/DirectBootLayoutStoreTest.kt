/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DirectBootLayoutStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val original = """{"default":[[{"type":"AlphabetKey","main":"a"}]]}"""
    private val replacement = """{"default":[[{"type":"AlphabetKey","main":"b"}]]}"""
    private fun destination() = File(temp.root, "de/TextKeyboardLayout.json")

    @Test fun commentedLayoutsUseTheSameParserAsTheKeyboard() {
        val source = temp.newFile("layout.json").apply { writeText("// custom layout\n$original\n") }
        DirectBootLayoutStore(destination()).syncFrom(source)
        assertEquals(source.readText(), destination().readText())
    }

    @Test fun changedContentWithOlderOrIdenticalTimestampsReplacesTheSnapshot() {
        val source = temp.newFile().apply { writeText(original); setLastModified(2000) }
        val store = DirectBootLayoutStore(destination())
        store.syncFrom(source)
        source.writeText(replacement)
        source.setLastModified(2000)
        store.syncFrom(source)
        assertEquals(replacement, destination().readText())
        source.writeText(original)
        source.setLastModified(1000)
        store.syncFrom(source)
        assertEquals(original, destination().readText())
    }

    @Test fun unchangedContentDoesNotRewriteDeviceStorage() {
        val source = temp.newFile().apply { writeText(original) }
        val store = DirectBootLayoutStore(destination())
        store.syncFrom(source)
        assertTrue(destination().setLastModified(1000))
        store.syncFrom(source)
        assertEquals(1000L, destination().lastModified())
    }

    @Test fun deletingOrSwitchingToAMissingLayoutRemovesThePreviousSnapshot() {
        val source = temp.newFile().apply { writeText(original) }
        val store = DirectBootLayoutStore(destination())
        store.syncFrom(source)
        assertTrue(source.delete())
        store.syncFrom(source)
        assertFalse(destination().exists())
        source.writeText(replacement)
        store.syncFrom(source)
        store.syncFrom(File(temp.root, "missing-profile.json"))
        assertFalse(destination().exists())
    }

    @Test fun unavailableStoragePreservesTheLastSnapshot() {
        val source = temp.newFile().apply { writeText(original) }
        val store = DirectBootLayoutStore(destination())
        store.syncFrom(source)
        store.syncFrom(null)
        assertEquals(original, destination().readText())
    }

    @Test fun invalidJsonAndNonObjectRootsRemoveStaleLayouts() {
        val source = temp.newFile()
        val store = DirectBootLayoutStore(destination())
        for (invalid in listOf("{", "[]", "null", "")) {
            source.writeText(original)
            store.syncFrom(source)
            source.writeText(invalid)
            store.syncFrom(source)
            assertFalse("Invalid layout: $invalid", destination().exists())
        }
    }

    @Test fun failedAtomicWritePreservesThePreviousCompleteSnapshot() {
        val source = temp.newFile().apply { writeText(original) }
        val store = DirectBootLayoutStore(destination())
        store.syncFrom(source)
        source.writeText(replacement)
        // A failed staging write must never truncate the published layout.
        val staging = File(destination().parentFile, "${destination().name}.tmp")
        assertTrue(staging.mkdir())
        assertTrue(runCatching { store.syncFrom(source) }.isFailure)
        assertEquals(original, destination().readText())
    }

    @Test fun profileNamesStillNormalizeBeforeResolvingFiles() {
        assertEquals("default", UserConfigFiles.normalizeTextKeyboardLayoutProfile(" DEFAULT "))
        assertEquals("a b", UserConfigFiles.normalizeTextKeyboardLayoutProfile("../a\t b/"))
        assertNull(UserConfigFiles.normalizeTextKeyboardLayoutProfile("../"))
        assertEquals("TextKeyboardLayout.custom.json", UserConfigFiles.textKeyboardLayoutFileName("custom"))
    }
}
