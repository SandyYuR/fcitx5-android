/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException

class DirectRimeInputTypeOperationsTest {
    @get:Rule val temp = TemporaryFolder()

    private class Owner(override val user: File) : DirectRimeInputTypeOperations.Owner {
        override var started = true
        override var maintenance = false
        override var evo = true
        override var generation = 7L
        override var profile = "default"
        override var engineIdentity = "evo"
        var refreshed = 0
        var onRefresh: () -> Unit = {}
        override fun requireAvailable() { check(started && !maintenance) }
        override fun requireProfile(profile: String, path: String) {
            check(profile == this.profile && path == user.canonicalPath)
        }
        override fun refresh() { refreshed++; onRefresh() }
    }

    private fun owner(): Owner {
        val user = temp.newFolder()
        File(user, "test.schema.yaml").writeText("""
            schema:
              schema_id: test
            input_types:
              enabled: [a, b]
              reset: a
              options:
                - {option: a, label: Type A}
                - {option: b, label: Type B}
                - {option: c, label: Not enabled}
        """.trimIndent() + "\n")
        return Owner(user)
    }

    private fun command(raw: RawConfig, option: String = "b") = RawConfig(arrayOf(
        RawConfig("Schema", "test"), RawConfig("Option", option), requireNotNull(raw.findByName("Target")),
    ))

    @Test fun enabledCompiledIntersectionAndRuntimeSelectionUseTheSameOwner() {
        val owner = owner()
        val selected = mutableListOf<String>()
        val operations = DirectRimeInputTypeOperations(owner,
            nativeState = { _, evo -> check(evo); arrayOf("a", "a", "b", "c") },
            nativeSelect = { schema, option, evo, generation ->
                check(evo && generation == -1L && schema == "test")
                selected += option
                true
            })
        val raw = operations.read("test")
        val state = requireNotNull(DirectRimeInputTypeOperations.decode(raw))
        assertEquals(listOf("a", "b"), state.selectableTypes.map { it.option })
        operations.select(command(raw)) { }
        assertEquals(listOf("b"), selected)
        assertEquals("patch:\n  input_types/reset: b\n", File(owner.user, "test.custom.yaml").readText())
        assertEquals(1, owner.refreshed)
    }

    @Test fun unsupportedEngineAndUndeployedChoiceNeverReachNativeSelection() {
        val owner = owner()
        var selections = 0
        val operations = DirectRimeInputTypeOperations(owner,
            nativeState = { _, _ -> arrayOf("a", "a") },
            nativeSelect = { _, _, _, _ -> selections++; true })
        val raw = operations.read("test")
        assertTrue(runCatching { operations.select(command(raw)) { } }.isFailure)
        owner.evo = false
        assertEquals("False", operations.read("test").findByName("Editable")?.value)
        assertTrue(runCatching { operations.select(command(raw, "a")) { } }.isFailure)
        assertEquals(0, selections)
        assertTrue(!File(owner.user, "test.custom.yaml").exists())
    }

    @Test fun staleProfileEngineAndMaintenanceRejectWithoutWriting() {
        val owner = owner()
        var selections = 0
        val operations = DirectRimeInputTypeOperations(owner,
            nativeState = { _, _ -> arrayOf("a", "a", "b") },
            nativeSelect = { _, _, _, _ -> selections++; true })
        val request = command(operations.read("test"))
        owner.profile = "other"
        assertTrue(runCatching { operations.select(request) { } }.isFailure)
        owner.profile = "default"
        owner.engineIdentity = "other"
        assertTrue(runCatching { operations.select(request) { } }.isFailure)
        owner.engineIdentity = "evo"
        owner.maintenance = true
        assertTrue(runCatching { operations.select(request) { } }.isFailure)
        assertEquals(0, selections)
        assertTrue(!File(owner.user, "test.custom.yaml").exists())
    }

    @Test fun editorChangesAfterMenuReadAndDuringPersistenceKeepTheSettingsTarget() {
        val owner = owner()
        val selected = mutableListOf<Pair<String, Long>>()
        val operations = DirectRimeInputTypeOperations(owner,
            nativeState = { _, _ -> arrayOf("a", "a", "b") },
            nativeSelect = { _, option, _, generation -> selected += option to generation; true })
        val request = command(operations.read("test"))
        owner.generation++ // Opening the choice dialog changes Android editor focus.
        var checks = 0
        operations.select(request) { if (++checks == 2) owner.generation++ }
        assertEquals(listOf("b" to -1L), selected)
        assertEquals("patch:\n  input_types/reset: b\n", File(owner.user, "test.custom.yaml").readText())
        assertEquals(0, owner.refreshed)
    }

    @Test fun cancellationDuringPersistenceRollsBackBeforeNative() {
        val owner = owner()
        val file = File(owner.user, "test.custom.yaml")
        val before = "# user data\r\npatch:\r\n  menu/page_size: 6\r\n  input_types/reset: a"
        file.writeText(before)
        var selections = 0
        val operations = DirectRimeInputTypeOperations(owner,
            nativeState = { _, _ -> arrayOf("a", "a", "b") },
            nativeSelect = { _, _, _, _ -> selections++; true })
        val request = command(operations.read("test"))
        var checks = 0
        assertTrue(runCatching { operations.select(request) {
            if (++checks == 2) throw CancellationException("cancelled")
        } }.isFailure)
        assertEquals(before, file.readText())
        assertEquals(0, selections)
        assertEquals(0, owner.refreshed)
    }

    @Test fun editorChangeDuringNativeKeepsPreferenceWithoutPublishingOldEditor() {
        val owner = owner()
        val file = File(owner.user, "test.custom.yaml")
        val before = "# preference\r\npatch:\r\n  input_types/reset: a  # original"
        file.writeText(before)
        var running = "a"
        val selections = mutableListOf<Pair<String, Long>>()
        val operations = DirectRimeInputTypeOperations(owner,
            nativeState = { _, _ -> arrayOf(running, "a", "b") },
            nativeSelect = { _, option, _, generation ->
                selections += option to generation
                running = option
                if (option == "b") owner.generation++
                true
            })
        operations.select(command(operations.read("test"))) { }
        assertEquals(listOf("b" to -1L), selections)
        assertEquals("b", running)
        assertEquals(before.replace("reset: a", "reset: b"), file.readText())
        assertEquals(0, owner.refreshed)
    }

    @Test fun cancellationInsideNativeRestoresRuntimeAndRemovesNewCustom() {
        val owner = owner()
        var running = "a"
        var cancelled = false
        val operations = DirectRimeInputTypeOperations(owner,
            nativeState = { _, _ -> arrayOf(running, "a", "b") },
            nativeSelect = { _, option, _, _ ->
                running = option
                if (option == "b") cancelled = true
                true
            })
        val failure = runCatching { operations.select(command(operations.read("test"))) {
            if (cancelled) throw CancellationException("cancelled during JNI")
        } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("a", running)
        assertTrue(!File(owner.user, "test.custom.yaml").exists())
    }

    @Test fun maintenanceRequestedDuringNativeRestoresSameBindingBeforeQueuedWork() {
        val owner = owner()
        val file = File(owner.user, "test.custom.yaml")
        val before = "patch:\n  input_types/reset: a\n"
        file.writeText(before)
        var running = "a"
        val selections = mutableListOf<String>()
        val operations = DirectRimeInputTypeOperations(owner,
            nativeState = { _, _ -> arrayOf(running, "a", "b") },
            nativeSelect = { _, option, _, _ ->
                selections += option
                running = option
                if (option == "b") owner.maintenance = true
                true
            })
        assertTrue(runCatching { operations.select(command(operations.read("test"))) { } }.isFailure)
        assertEquals(listOf("b", "a"), selections)
        assertEquals("a", running)
        assertEquals(before, file.readText())
        assertEquals(0, owner.refreshed)
    }

    @Test fun publicationFailureRestoresRuntimeAndExactFile() {
        val owner = owner()
        val file = File(owner.user, "test.custom.yaml")
        val before = "patch:\n  input_types/reset: a\n  menu/page_size: 6\n"
        file.writeText(before)
        var running = "a"
        owner.onRefresh = { if (owner.refreshed == 1) error("snapshot refresh failed") }
        val operations = DirectRimeInputTypeOperations(owner,
            nativeState = { _, _ -> arrayOf(running, "a", "b") },
            nativeSelect = { _, option, _, _ -> running = option; true })
        val failure = runCatching { operations.select(command(operations.read("test"))) { } }.exceptionOrNull()
        assertEquals("snapshot refresh failed", failure?.message)
        assertEquals("a", running)
        assertEquals(before, file.readText())
        assertEquals(2, owner.refreshed)
    }

    @Test fun runtimeRollbackRejectionIsReportedAndFileStillRestored() {
        val owner = owner()
        var cancelled = false
        val operations = DirectRimeInputTypeOperations(owner,
            nativeState = { _, _ -> arrayOf("a", "a", "b") },
            nativeSelect = { _, option, _, _ ->
                if (option == "b") { cancelled = true; true } else false
            })
        val failure = runCatching { operations.select(command(operations.read("test"))) {
            if (cancelled) throw CancellationException("cancelled after native")
        } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("无法恢复原输入类型", failure?.suppressed?.singleOrNull()?.message)
        assertTrue(!File(owner.user, "test.custom.yaml").exists())
    }

    @Test fun runtimeRejectionRollsBackAndStartupRestoresOnlyPreference() {
        val owner = owner()
        val file = File(owner.user, "test.custom.yaml")
        val before = "patch:\n  input_types/reset: b\n"
        file.writeText(before)
        val selected = mutableListOf<Pair<String, Long>>()
        var accept = false
        val operations = DirectRimeInputTypeOperations(owner,
            nativeState = { _, _ -> arrayOf("a", "a", "b") },
            nativeSelect = { _, option, _, generation -> selected += option to generation; accept })
        assertTrue(runCatching { operations.select(command(operations.read("test"), "a")) { } }.isFailure)
        assertEquals(before, file.readText())
        accept = true
        operations.restorePreferences(listOf("test"))
        assertEquals(listOf("a" to -1L, "b" to -1L), selected)
        assertEquals(0, owner.refreshed)
    }
}
