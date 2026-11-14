/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException
import org.fcitx.fcitx5.android.core.DirectRimeManagementCommands.ProfileAction
import org.fcitx.fcitx5.android.core.engine.PluginRimeEngineAuthorization
import org.fcitx.fcitx5.android.core.engine.RimeEngineKind
import org.fcitx.fcitx5.android.core.engine.RimeEngineRepository
import org.fcitx.fcitx5.android.core.engine.RimeEngineRuntimeInfo
import org.fcitx.fcitx5.android.core.engine.RimeEngineSelection
import org.fcitx.fcitx5.android.core.engine.RimeEngineStaticInfo
import org.fcitx.fcitx5.android.data.RimeProfileImportTransaction
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real directory configuration and import transactions with owner lifecycle callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DirectRimeProfileOperationsTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var config: DirectRimeConfig
    private lateinit var repository: RimeEngineRepository

    @Before fun prepare() {
        val files = temporary.newFolder("files")
        val external = temporary.newFolder("external")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = files
            override fun getExternalFilesDir(type: String?) = external
        }
        config = DirectRimeConfig(context).apply { load(); saveDirectories() }
        repository = RimeEngineRepository(temporary.newFolder("engines"))
    }

    private fun operations(
        stop: () -> Unit = { fail("Unexpected stop") },
        restart: () -> Unit = { fail("Unexpected restart") },
        repositoryProvider: () -> RimeEngineRepository = { repository },
    ) = DirectRimeProfileOperations(config, repositoryProvider, stop, restart)

    private fun DirectRimeProfileOperations.manage(
        action: ProfileAction,
        name: String,
        externalPath: () -> String = { fail("Unexpected ExternalPath read"); "" },
        newName: () -> String = { fail("Unexpected NewName read"); "" },
    ) = manage(DirectRimeManagementCommands.ProfileManager(name, action), externalPath, newName)

    private fun assertLeaseHeld() {
        assertEquals("引擎或工作目录正在切换，请稍后重试",
            assertThrows(IllegalArgumentException::class.java) { repository.requireProfileMutationIdle() }.message)
    }

    private fun profiles() = config.directoryState().findByName("Profiles")!!.value.lines()

    private fun blockSave() {
        assertTrue(File(config.root, "config/conf/rime-data-dir.conf.tmp").mkdir())
    }

    private fun imported(invalid: Boolean = false): RimeProfileImportTransaction {
        val imports = temporary.newFolder("imports")
        val archive = File(imports, "profile.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            val entries = if (invalid) listOf("../escape" to "bad") else listOf(
                "default.yaml" to "schema_list: []\n", "custom.dict.yaml" to "personal entries")
            for ((name, contents) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return RimeProfileImportTransaction.prepare(archive.absolutePath, imports, config.dataRoot,
            profiles().toSet(), timestamp = 100)
    }

    private fun authorizeProfile(): RimeEngineSelection {
        val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, "org.example.engine")
        repository.authorizePlugin(PluginRimeEngineAuthorization(
            packageName = selection.id, displayName = "Example", providerId = "example",
            certificateSha256 = "11".repeat(32), payloadSha256 = "22".repeat(32), soname = "librime.so",
            staticInfo = RimeEngineStaticInfo("22".repeat(32), 1, "arm64-v8a", 64, "librime.so",
                null, "rime_get_api", listOf("libc.so"), 16384, "librime.so"),
            runtimeInfo = RimeEngineRuntimeInfo("1.0", 1024, emptyList()), profile = "rime", authorizedAt = 1,
        ))
        return selection
    }

    @Test fun createReadsOnlyItsPathWhileLeasedAndClearsThePreviousError() {
        val outside = temporary.newFolder("outside")
        File(outside, "personal.dict.yaml").writeText("keep")
        config.error = "previous error"

        operations().manage(ProfileAction.Create, "external", externalPath = {
            assertLeaseHeld()
            outside.absolutePath
        })

        assertEquals(listOf("rime", "external"), profiles())
        assertEquals("rime", config.current)
        assertEquals("", config.error)
        assertEquals("keep", File(outside, "personal.dict.yaml").readText())
        val restored = DirectRimeConfig(context).apply { load(); switch("external") }
        assertEquals(outside.canonicalFile, restored.user.canonicalFile)
        repository.requireProfileMutationIdle()
    }

    @Test fun firstRepositoryAccessFailsInsideTheManagementErrorHandlerBeforeFieldsAreRead() {
        val failure = IOException("repository unavailable")
        config.error = "previous error"
        val operation = operations(repositoryProvider = { throw failure })

        assertSame(failure, assertThrows(IOException::class.java) {
            operation.manage(ProfileAction.Create, "new")
        })

        assertEquals(failure.message, config.error)
        assertEquals(listOf("rime"), profiles())
        repository.requireProfileMutationIdle()
    }

    @Test fun activeRenameReadsItsNewNameAfterStopAndRestartsTheRenamedProfileWhileLeased() {
        val calls = mutableListOf<String>()
        var nextName = "before-stop"
        File(config.user, "personal.dict.yaml").writeText("keep")
        val operation = operations(stop = {
            assertLeaseHeld()
            calls += "stop:${config.current}"
            nextName = "renamed"
        }, restart = {
            assertLeaseHeld()
            calls += "restart:${config.current}"
            assertEquals("keep", File(config.user, "personal.dict.yaml").readText())
        })

        operation.manage(ProfileAction.Rename, "rime", newName = {
            calls += "read:$nextName"
            nextName
        })

        assertEquals(listOf("stop:rime", "read:renamed", "restart:renamed"), calls)
        assertEquals("renamed", config.current)
        assertEquals("renamed", DirectRimeConfig(context).apply { load() }.current)
        repository.requireProfileMutationIdle()
    }

    @Test fun inactiveRenameMovesFilesWithoutStoppingOrRestartingTheCurrentProfile() {
        config.create("second")
        File(config.dataRoot, "second/personal.dict.yaml").writeText("keep")

        operations().manage(ProfileAction.Rename, "second", newName = { "renamed" })

        assertEquals("rime", config.current)
        assertEquals(listOf("rime", "renamed"), profiles())
        assertFalse(File(config.dataRoot, "second").exists())
        assertEquals("keep", File(config.dataRoot, "renamed/personal.dict.yaml").readText())
        repository.requireProfileMutationIdle()
    }

    @Test fun anInitialActiveRenameStopFailureDoesNotReadTheNewNameOrRestart() {
        val failure = IOException("stop failed")
        val operation = operations(stop = { assertLeaseHeld(); throw failure })

        assertSame(failure, assertThrows(IOException::class.java) {
            operation.manage(ProfileAction.Rename, "rime")
        })

        assertEquals("rime", config.current)
        assertEquals(listOf("rime"), profiles())
        assertEquals(failure.message, config.error)
        repository.requireProfileMutationIdle()
    }

    @Test fun aNewNameReadFailureStillRestartsTheActiveProfile() {
        val failure = IllegalArgumentException("new name read failed")
        val calls = mutableListOf<String>()
        val operation = operations(stop = { calls += "stop" }, restart = {
            assertLeaseHeld()
            calls += "restart:${config.current}"
        })

        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            operation.manage(ProfileAction.Rename, "rime", newName = { throw failure })
        })

        assertEquals(listOf("stop", "restart:rime"), calls)
        assertEquals(failure.message, config.error)
        repository.requireProfileMutationIdle()
    }

    @Test fun aRenamePersistenceFailureRestoresFilesBeforeRestartingTheOldProfile() {
        File(config.user, "personal.dict.yaml").writeText("keep")
        blockSave()
        val calls = mutableListOf<String>()
        val operation = operations(stop = { calls += "stop" }, restart = {
            assertLeaseHeld()
            calls += "restart:${config.current}"
            assertEquals("keep", File(config.user, "personal.dict.yaml").readText())
            assertFalse(File(config.dataRoot, "renamed").exists())
        })

        val failure = assertThrows(IllegalArgumentException::class.java) {
            operation.manage(ProfileAction.Rename, "rime", newName = { "renamed" })
        }

        assertEquals("Temporary path is not a regular file", failure.message)
        assertEquals(listOf("stop", "restart:rime"), calls)
        assertEquals(listOf("rime"), profiles())
        assertEquals(failure.message, config.error)
        repository.requireProfileMutationIdle()
    }

    @Test fun aRestartFailureAfterRenameKeepsTheCommittedRename() {
        val failure = IOException("restart failed")
        val operation = operations(stop = {}, restart = { throw failure })

        assertSame(failure, assertThrows(IOException::class.java) {
            operation.manage(ProfileAction.Rename, "rime", newName = { "renamed" })
        })

        assertEquals("renamed", config.current)
        assertEquals("renamed", DirectRimeConfig(context).apply { load() }.current)
        assertEquals(failure.message, config.error)
        repository.requireProfileMutationIdle()
    }

    @Test fun renameRestartFailureRetainsTheExistingExceptionPriority() {
        val renameFailure = IOException("new name failed")
        val restartFailure = IOException("restart failed")
        val operation = operations(stop = {}, restart = { throw restartFailure })

        assertSame(restartFailure, assertThrows(IOException::class.java) {
            operation.manage(ProfileAction.Rename, "rime", newName = { throw renameFailure })
        })

        assertEquals("rime", config.current)
        assertEquals(restartFailure.message, config.error)
        assertTrue(restartFailure.suppressed.isEmpty())
        repository.requireProfileMutationIdle()
    }

    @Test fun referencedProfilesAreRejectedBeforeRenameStopOrFieldReadAndBeforeDeletion() {
        authorizeProfile()
        for (action in listOf(ProfileAction.Rename, ProfileAction.Delete)) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                operations().manage(action, "rime")
            }
            assertEquals("此工作目录仍被内置回退或外部引擎引用，请先更改引擎绑定", failure.message)
            assertEquals(failure.message, config.error)
            assertEquals(listOf("rime"), profiles())
            repository.requireProfileMutationIdle()
        }
    }

    @Test fun aManagementSwitchRestartFailureKeepsTheNewSelectionWithoutImportStyleRollback() {
        config.create("second")
        val failure = IOException("restart failed")
        val calls = mutableListOf<String>()
        val operation = operations(restart = {
            assertLeaseHeld()
            calls += config.current
            throw failure
        })

        assertSame(failure, assertThrows(IOException::class.java) {
            operation.manage(ProfileAction.Switch, "second")
        })

        assertEquals(listOf("second"), calls)
        assertEquals("second", config.current)
        assertEquals("second", DirectRimeConfig(context).apply { load() }.current)
        assertEquals(failure.message, config.error)
        repository.requireProfileMutationIdle()
    }

    @Test fun managementRestartCancellationRetainsTheNewSelectionAndRecordsTheError() {
        config.create("second")
        val failure = CancellationException("restart cancelled")
        val operation = operations(restart = { throw failure })

        assertSame(failure, assertThrows(CancellationException::class.java) {
            operation.manage(ProfileAction.Switch, "second")
        })

        assertEquals("second", config.current)
        assertEquals(failure.message, config.error)
        repository.requireProfileMutationIdle()
    }

    @Test fun deletingAnExternalRegistrationPreservesItsFilesAndClearsTheError() {
        val outside = temporary.newFolder("outside")
        File(outside, "personal.dict.yaml").writeText("keep")
        config.create("external", outside.absolutePath)
        config.error = "previous error"

        operations().manage(ProfileAction.Delete, "external")

        assertEquals(listOf("rime"), profiles())
        assertEquals("keep", File(outside, "personal.dict.yaml").readText())
        assertEquals("", config.error)
        repository.requireProfileMutationIdle()
    }

    @Test fun successfulImportRegistersAndSelectsBeforeRestartAndPreservesTheError() {
        val transaction = imported()
        config.error = "previous error"
        val calls = mutableListOf<String>()
        val operation = operations(restart = {
            assertLeaseHeld()
            calls += config.current
            assertTrue(transaction.name in profiles())
            assertEquals("personal entries", File(config.user, "custom.dict.yaml").readText())
        })

        operation.installImported(transaction)

        assertEquals(listOf(transaction.name), calls)
        assertEquals(transaction.name, DirectRimeConfig(context).apply { load() }.current)
        assertEquals("previous error", config.error)
        assertFalse(File(config.dataRoot, ".${transaction.name}.importing").exists())
        repository.requireProfileMutationIdle()
    }

    @Test fun invalidImportFailsBeforeRepositoryAccessAndKeepsTheExistingError() {
        val transaction = imported(invalid = true)
        config.error = "previous error"
        val operation = operations(repositoryProvider = { throw IOException("must not access repository") })

        assertThrows(IllegalArgumentException::class.java) { operation.installImported(transaction) }

        assertFalse(transaction.target.exists())
        assertFalse(File(config.dataRoot, ".${transaction.name}.importing").exists())
        assertEquals(listOf("rime"), profiles())
        assertEquals("previous error", config.error)
        repository.requireProfileMutationIdle()
    }

    @Test fun aBusyImportLeaseCleansTheUnregisteredInstallAndKeepsTheExistingError() {
        val transaction = imported()
        config.error = "previous error"
        repository.profileMutationLease().use {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                operations().installImported(transaction)
            }
            assertEquals("引擎或工作目录正在切换，请稍后重试", failure.message)
            assertFalse(transaction.target.exists())
            assertFalse(File(config.dataRoot, ".${transaction.name}.importing").exists())
            assertEquals(listOf("rime"), profiles())
            assertEquals("previous error", config.error)
        }
        repository.requireProfileMutationIdle()
    }

    @Test fun importRechecksTheBuiltinGuardAfterInstallAndCleansTheUnregisteredTarget() {
        val transaction = imported()
        val selection = authorizeProfile()
        repository.select(selection, "rime")
        repository.beginActivation()
        repository.activationSucceeded(selection)
        config.error = "previous error"

        val failure = assertThrows(IllegalArgumentException::class.java) {
            operations().installImported(transaction)
        }

        assertEquals("外部引擎正在使用；请在“Rime 引擎变体”页面更改它绑定的工作目录", failure.message)
        assertFalse(transaction.target.exists())
        assertEquals(listOf("rime"), profiles())
        assertEquals("previous error", config.error)
        repository.requireProfileMutationIdle()
    }

    @Test fun anImportRegistrationPersistenceFailureRemovesOnlyTheUnregisteredTarget() {
        val transaction = imported()
        File(config.user, "personal.dict.yaml").writeText("keep")
        config.error = "previous error"
        blockSave()

        val failure = assertThrows(IllegalArgumentException::class.java) {
            operations().installImported(transaction)
        }

        assertEquals("Temporary path is not a regular file", failure.message)
        assertFalse(transaction.target.exists())
        assertFalse(File(config.dataRoot, ".${transaction.name}.importing").exists())
        assertEquals(listOf("rime"), profiles())
        assertEquals("keep", File(config.user, "personal.dict.yaml").readText())
        assertEquals("previous error", config.error)
        repository.requireProfileMutationIdle()
    }

    @Test fun anImportRestartFailureRestoresThePreviousSelectionButRetainsTheRegisteredFiles() {
        val transaction = imported()
        val failure = IOException("restart failed")
        config.error = "previous error"
        val calls = mutableListOf<String>()
        val operation = operations(restart = {
            assertLeaseHeld()
            calls += config.current
            if (calls.size == 1) throw failure
        })

        assertSame(failure, assertThrows(IOException::class.java) { operation.installImported(transaction) })

        assertEquals(listOf(transaction.name, "rime"), calls)
        assertEquals("rime", config.current)
        assertEquals("rime", DirectRimeConfig(context).apply { load() }.current)
        assertTrue(transaction.name in profiles())
        assertEquals("personal entries", File(transaction.target, "custom.dict.yaml").readText())
        assertEquals("previous error", config.error)
        repository.requireProfileMutationIdle()
    }

    @Test fun anImportRecoveryRestartFailureRetainsTheExistingExceptionPriorityAndRegisteredFiles() {
        val transaction = imported()
        val firstFailure = IOException("first restart failed")
        val recoveryFailure = IOException("recovery restart failed")
        val calls = mutableListOf<String>()
        config.error = "previous error"
        val operation = operations(restart = {
            assertLeaseHeld()
            calls += config.current
            throw if (calls.size == 1) firstFailure else recoveryFailure
        })

        assertSame(recoveryFailure, assertThrows(IOException::class.java) { operation.installImported(transaction) })

        assertEquals(listOf(transaction.name, "rime"), calls)
        assertEquals("rime", config.current)
        assertTrue(transaction.target.isDirectory)
        assertTrue(transaction.name in profiles())
        assertTrue(recoveryFailure.suppressed.isEmpty())
        assertEquals("previous error", config.error)
        repository.requireProfileMutationIdle()
    }

    @Test fun anImportRecoverySelectionFailureDoesNotAttemptTheRecoveryRestart() {
        val transaction = imported()
        val firstFailure = IOException("first restart failed")
        val calls = mutableListOf<String>()
        config.error = "previous error"
        val operation = operations(restart = {
            calls += config.current
            blockSave()
            throw firstFailure
        })

        val failure = assertThrows(IllegalArgumentException::class.java) { operation.installImported(transaction) }

        assertEquals("Temporary path is not a regular file", failure.message)
        assertEquals(listOf(transaction.name), calls)
        assertEquals(transaction.name, config.current)
        assertTrue(transaction.target.isDirectory)
        assertTrue(transaction.name in profiles())
        assertEquals("previous error", config.error)
        repository.requireProfileMutationIdle()
    }
}
