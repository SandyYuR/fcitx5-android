/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RimeProfileUpdateInstallerTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun plan(
        policy: RimeInstallPolicy = RimeInstallPolicy.SETTINGS,
        path: String = "default.yaml",
    ): Pair<PreparedRimeInstall, File> {
        val user = temporary.newFolder("user-${System.nanoTime()}")
        val payload = temporary.newFolder("payload-${System.nanoTime()}")
        File(user, path).apply { parentFile!!.mkdirs(); writeText("old") }
        File(payload, path).apply { parentFile!!.mkdirs(); writeText("new") }
        return PreparedRimeInstall(user, payload, policy) to user
    }

    @Test fun successfulInstallStopsBeforeReplacingFilesAndRestartsOnce() {
        val (plan, user) = plan()
        val calls = mutableListOf<String>()

        RimeProfileUpdateInstaller.install(plan, stop = { calls += "stop" }, restart = {
            assertEquals("new", File(user, "default.yaml").readText())
            calls += "restart"
        })

        assertEquals(listOf("stop", "restart"), calls)
        assertEquals("new", File(user, "default.yaml").readText())
    }

    @Test fun failedActivationRetriesRecoveryBeforeRestartingTheOriginalFiles() {
        val (plan, user) = plan()
        val calls = mutableListOf<String>()
        var restarts = 0

        val error = runCatching {
            RimeProfileUpdateInstaller.install(plan, stop = { calls += "stop" }, restart = {
                restarts++
                calls += "restart-$restarts:${File(user, "default.yaml").readText()}"
                if (restarts == 1) error("deployment failed")
            })
        }.exceptionOrNull()

        assertEquals("deployment failed", error?.message)
        assertEquals(listOf("stop", "restart-1:new", "stop", "restart-2:old"), calls)
        assertEquals("old", File(user, "default.yaml").readText())
    }

    @Test fun validationFailureBeforeTheTransactionTryStillRestartsTheStoppedEngine() {
        val (plan, user) = plan(RimeInstallPolicy.CUSTOM_UPDATE, "user.yaml")
        val calls = mutableListOf<String>()

        val error = runCatching {
            RimeProfileUpdateInstaller.install(plan, stop = { calls += "stop" }, restart = { calls += "restart" })
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(listOf("stop", "restart"), calls)
        assertEquals("old", File(user, "user.yaml").readText())
    }

    @Test fun initialStopFailureNeverAttemptsRecoveryOrRestart() {
        val (plan, user) = plan()
        var restarted = false

        val error = runCatching {
            RimeProfileUpdateInstaller.install(plan, stop = { error("stop failed") }, restart = { restarted = true })
        }.exceptionOrNull()

        assertEquals("stop failed", error?.message)
        assertFalse(restarted)
        assertEquals("old", File(user, "default.yaml").readText())
    }

    @Test fun failedOuterRecoveryIsSuppressedOnTheOriginalTransactionError() {
        val (plan, user) = plan()
        File(user, ".wanxiang-update/notes.txt").apply { parentFile!!.mkdirs(); writeText("personal") }
        var restarted = false

        val error = runCatching {
            RimeProfileUpdateInstaller.install(plan, stop = {}, restart = { restarted = true })
        }.exceptionOrNull()!!

        assertEquals(1, error.suppressed.size)
        assertTrue(error.message.orEmpty().contains("original files were preserved"))
        assertTrue(error.suppressed.single().message.orEmpty().contains("original files were preserved"))
        assertFalse(restarted)
        assertEquals("personal", File(user, ".wanxiang-update/notes.txt").readText())
    }
}
