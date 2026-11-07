/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.app.Application
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.testing.FilesystemFixtures
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WanxiangUpdateTransactionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun put(root: File, name: String, value: String) = File(root, name).apply {
        parentFile!!.mkdirs()
        if (name.startsWith(".wanxiang-update/")) File(root, ".wanxiang-update/owner").writeText(WanxiangUpdateTransaction.OWNER)
        writeText(value)
    }

    @Test fun anUnrelatedDirectoryWithTheReservedNameIsNeverDeleted() {
        val user = temp.newFolder()
        val notes = File(user, ".wanxiang-update/notes.txt").apply { parentFile!!.mkdirs(); writeText("personal notes") }
        assertTrue(runCatching { WanxiangUpdateTransaction.recover(user) }.isFailure)
        assertEquals("personal notes", notes.readText())
    }

    @Test fun updatePreservesPersonalDataAndRunsDeploymentWithAllResourcesInstalled() {
        val user = temp.newFolder("user")
        val payload = temp.newFolder("payload")
        put(user, "wanxiang.schema.yaml", "old")
        put(user, "wanxiang.custom.yaml", "personal patch")
        put(user, "wanxiang.userdb/data", "learned words")
        put(user, "custom_phrase.dict.yaml", "personal phrase")
        put(payload, "wanxiang.schema.yaml", "new")
        put(payload, "lua/module.lua", "new lua")
        var deployed = false
        WanxiangUpdateTransaction.install(user, payload, activate = {
            assertEquals("new", File(user, "wanxiang.schema.yaml").readText())
            assertEquals("new lua", File(user, "lua/module.lua").readText())
            deployed = true
        }, deactivate = { fail("Successful deployment must not roll back") })
        assertTrue(deployed)
        assertEquals("personal patch", File(user, "wanxiang.custom.yaml").readText())
        assertEquals("learned words", File(user, "wanxiang.userdb/data").readText())
        assertEquals("personal phrase", File(user, "custom_phrase.dict.yaml").readText())
        assertFalse(File(user, ".wanxiang-update").exists())
    }

    @Test fun deploymentFailureStopsTheEngineBeforeRestoringEveryReplacedResource() {
        val user = temp.newFolder("user")
        val payload = temp.newFolder("payload")
        put(user, "default.yaml", "old")
        put(payload, "default.yaml", "new")
        put(payload, "lua/new.lua", "new file")
        var stopped = false
        val result = runCatching { WanxiangUpdateTransaction.install(user, payload,
            activate = { error("deploy failed") }, deactivate = {
                assertEquals("new", File(user, "default.yaml").readText()); stopped = true
            }) }
        assertTrue(result.isFailure)
        assertTrue(stopped)
        assertEquals("old", File(user, "default.yaml").readText())
        assertFalse(File(user, "lua/new.lua").exists())
    }

    @Test fun startupRollsBackAnInterruptedInstallAndRecoveryIsRepeatable() {
        val user = temp.newFolder("user")
        put(user, "default.yaml", "partially installed")
        put(user, "lua/new.lua", "partially installed")
        put(user, ".wanxiang-update/old/default.yaml", "original")
        put(user, ".wanxiang-update/journal.json", Json.encodeToString(WanxiangUpdateTransaction.Journal(listOf(
            WanxiangUpdateTransaction.Entry("default.yaml", true), WanxiangUpdateTransaction.Entry("lua/new.lua", false)))))
        repeat(2) { WanxiangUpdateTransaction.recover(user) }
        assertEquals("original", File(user, "default.yaml").readText())
        assertFalse(File(user, "lua/new.lua").exists())
    }

    @Test fun committedResourcesSurviveInterruptedCleanup() {
        val user = temp.newFolder("user")
        put(user, "default.yaml", "deployed")
        put(user, ".wanxiang-update/journal.json", Json.encodeToString(WanxiangUpdateTransaction.Journal(
            listOf(WanxiangUpdateTransaction.Entry("default.yaml", true)), committed = true)))
        WanxiangUpdateTransaction.recover(user)
        assertEquals("deployed", File(user, "default.yaml").readText())
        assertFalse(File(user, ".wanxiang-update").exists())
    }

    @Test fun aMissingBackupOrProtectedJournalEntryBlocksRecoveryWithoutChangingLiveData() {
        for (name in listOf("default.yaml", "user.yaml")) {
            val user = temp.newFolder()
            put(user, name, "keep")
            put(user, ".wanxiang-update/journal.json", Json.encodeToString(WanxiangUpdateTransaction.Journal(
                listOf(WanxiangUpdateTransaction.Entry(name, true)))))
            assertTrue(runCatching { WanxiangUpdateTransaction.recover(user) }.isFailure)
            assertEquals("keep", File(user, name).readText())
            assertTrue(File(user, ".wanxiang-update/journal.json").isFile)
        }
    }

    @Test fun redirectedDestinationsNeverWriteOutsideTheProfile() {
        val user = temp.newFolder("user")
        val outside = temp.newFolder("outside")
        val payload = temp.newFolder("payload")
        put(outside, "module.lua", "keep")
        put(payload, "lua/module.lua", "new")
        FilesystemFixtures.symbolicLink(File(user, "lua").toPath(), outside.toPath())
        assertTrue(runCatching { WanxiangUpdateTransaction.install(user, payload, {}, {}) }.isFailure)
        assertEquals("keep", File(outside, "module.lua").readText())
        assertFalse(File(user, ".wanxiang-update").exists())
    }

    @Test fun aFileBlockingAnIncomingDirectoryIsRejectedBeforeAnyReplacement() {
        val user = temp.newFolder("user")
        val payload = temp.newFolder("payload")
        put(user, "default.yaml", "keep")
        put(user, "lua", "keep parent")
        put(payload, "default.yaml", "new")
        put(payload, "lua/module.lua", "new")
        assertTrue(runCatching { WanxiangUpdateTransaction.install(user, payload, {}, {}) }.isFailure)
        assertEquals("keep", File(user, "default.yaml").readText())
    }

    @Test fun defaultTransactionCannotWriteCustomPatchesButVerifiedSettingsCan() {
        val user = temp.newFolder("settings-user")
        val payload = temp.newFolder("settings-payload")
        put(user, "wanxiang.custom.yaml", "original patch")
        put(payload, "wanxiang.custom.yaml", "new patch")
        assertTrue(runCatching { WanxiangUpdateTransaction.install(user, payload, {}, {}) }.isFailure)
        assertEquals("original patch", File(user, "wanxiang.custom.yaml").readText())
        WanxiangUpdateTransaction.install(user, payload, {}, {}, settings = true)
        assertEquals("new patch", File(user, "wanxiang.custom.yaml").readText())
    }

    @Test fun anOverrideAllowedByUserRulesStillRollsBackIfDeploymentFails() {
        val user = temp.newFolder("override-user")
        val payload = temp.newFolder("override-payload")
        put(user, "custom_phrase.txt", "personal phrases")
        put(payload, "custom_phrase.txt", "downloaded phrases")
        var activated = false
        assertTrue(runCatching { WanxiangUpdateTransaction.install(user, payload, {
            assertEquals("downloaded phrases", File(user, "custom_phrase.txt").readText())
            activated = true
            error("deployment failed")
        }, {}, overrides = true) }.isFailure)
        assertTrue(activated)
        assertEquals("personal phrases", File(user, "custom_phrase.txt").readText())
    }

    @Test fun interruptedOverrideRestoresItsProtectedResourceUsingTheRecordedPolicy() {
        val user = temp.newFolder("interrupted-override")
        put(user, "wanxiang.custom.yaml", "incomplete override")
        put(user, ".wanxiang-update/old/wanxiang.custom.yaml", "original patch")
        put(user, ".wanxiang-update/journal.json", Json.encodeToString(WanxiangUpdateTransaction.Journal(
            listOf(WanxiangUpdateTransaction.Entry("wanxiang.custom.yaml", true)), overrides = true)))
        repeat(2) { WanxiangUpdateTransaction.recover(user) }
        assertEquals("original patch", File(user, "wanxiang.custom.yaml").readText())
    }

    @Test fun anInterruptedSettingsSaveRestoresItsCustomPatchBeforeOpeningRime() {
        val user = temp.newFolder("interrupted-settings")
        put(user, "wanxiang.custom.yaml", "incomplete patch")
        put(user, ".wanxiang-update/old/wanxiang.custom.yaml", "original patch")
        put(user, ".wanxiang-update/journal.json", Json.encodeToString(WanxiangUpdateTransaction.Journal(
            listOf(WanxiangUpdateTransaction.Entry("wanxiang.custom.yaml", true)), settings = true)))
        WanxiangUpdateTransaction.recover(user)
        assertEquals("original patch", File(user, "wanxiang.custom.yaml").readText())
    }
}
