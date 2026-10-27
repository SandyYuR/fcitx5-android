/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.utils.FileUtil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Host driver reboots the disposable device while the learn phase still owns its connection. */
class RimeShutdownPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val receipt get() = File(context.filesDir, "shutdown-learning-receipt.json")
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
    }
    private fun bootId() = shell("cat /proc/sys/kernel/random/boot_id").also {
        require(UUID.fromString(it).toString() == it) { "Cannot read the actual kernel boot ID" }
    }
    private fun bootCount() = shell("settings get global boot_count").toInt()

    @Test fun learnedRankingSurvivesOrderlyDeviceShutdown(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Only the explicitly selected disposable emulator",
            args.getString("allowDisposableDevice") == "true" && BuildConfig.DEBUG)
        val phase = args.getString("shutdownPhase") ?: error("Specify learn, verify or cleanup")
        val owner = "ShutdownPersistence-${UUID.randomUUID()}"
        val connection = FcitxDaemon.connect(owner)
        try {
            if (phase == "learn") {
                val initialBootCount = bootCount()
                require(initialBootCount >= 0) { "The device must expose its boot counter for this probe" }
                check(!receipt.exists()) { "Clean up the previous disposable learning fixture first" }
                val profile = "shutdown-test-${UUID.randomUUID()}"
                val previous = connection.runOnReady {
                    val current = getAddonSubConfig("rime", "profile-manager")["Current"].value
                    setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(
                        RawConfig("Action", "create"), RawConfig("Name", profile))))
                    current
                }
                val directory = File(context.getExternalFilesDir(null), "data/$profile")
                for (name in instrumentation.context.assets.list("smoke")!!) {
                    instrumentation.context.assets.open("smoke/$name").use { input ->
                        File(directory, name).outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val schema = File(directory, "direct_smoke.schema.yaml")
                schema.writeText(schema.readText().replace("enable_user_dict: false", "enable_user_dict: true"))
                val before = connection.runOnReady {
                    setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(
                        RawConfig("Action", "switch"), RawConfig("Name", profile))))
                    setCapFlags(CapabilityFlags.DefaultFlags)
                    startInputSession(invalidateInputSession(), "shutdown.learning.test")
                    sendKey('n', KeyState.Virtual.state); sendKey('i', KeyState.Virtual.state)
                    val baseline = getCandidates(0, 10).map { it.text }
                    assertEquals("Fixture must begin without learned ranking", "你", baseline.first())
                    val chosen = baseline.indexOf("泥")
                    assertTrue(chosen > 0)
                    assertTrue(selectGlobal(chosen))
                    // A subsequent query finishes the previous Rime memory session.
                    sendKey('n', KeyState.Virtual.state); sendKey('i', KeyState.Virtual.state)
                    assertEquals("Actual user-dictionary learning must change ranking", "泥", getCandidates(0, 10).first().text)
                    sendKey("Escape", KeyState.Virtual.state)
                    baseline
                }
                FileUtil.writeAtomically(receipt, JSONObject().apply {
                    put("profile", profile); put("previous_profile", previous)
                    put("code_sha", BuildConfig.BUILD_GIT_HASH); put("pid", Process.myPid())
                    put("target_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                    put("boot_id", bootId()); put("uptime_ms", SystemClock.elapsedRealtime())
                    put("boot_count", initialBootCount)
                    put("baseline", JSONArray(before)); put("learned_first", "泥")
                    put("waiting_for_orderly_shutdown", true)
                }.toString(2))
                instrumentation.sendStatus(0, Bundle().apply { putString("shutdown_receipt", receipt.path) })
                // No explicit save, profile switch, disconnect or normal test finish before
                // the host invokes svc power reboot and observes ACTION_SHUTDOWN.
                repeat(1200) { delay(1000) }
                fail("Host did not perform the orderly shutdown while this connection was alive")
            } else {
                val before = JSONObject(receipt.readText())
                val profile = before.getString("profile")
                require(profile.matches(Regex("shutdown-test-[0-9a-f-]{36}")))
                if (phase == "verify") {
                    val currentBoot = bootId()
                    assertTrue("The device must have restarted after learning", bootCount() > before.getInt("boot_count"))
                    assertNotEquals("The kernel must have rebooted", before.getString("boot_id"), currentBoot)
                    val candidates = connection.runOnReady {
                        assertEquals(profile, getAddonSubConfig("rime", "profile-manager")["Current"].value)
                        setCapFlags(CapabilityFlags.DefaultFlags)
                        startInputSession(invalidateInputSession(), "shutdown.verification.test")
                        sendKey('n', KeyState.Virtual.state); sendKey('i', KeyState.Virtual.state)
                        val words = getCandidates(0, 10).map { it.text }
                        assertEquals("Learned ranking must survive a new native engine after shutdown", before.getString("learned_first"), words.first())
                        sendKey("Escape", KeyState.Virtual.state)
                        words
                    }
                    FileUtil.writeAtomically(File(context.filesDir, "shutdown-learning-verified.json"), JSONObject().apply {
                        put("profile", profile); put("code_sha", BuildConfig.BUILD_GIT_HASH)
                        put("target_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                        put("pid", Process.myPid()); put("boot_id", currentBoot)
                        put("boot_count", bootCount())
                        put("uptime_ms", SystemClock.elapsedRealtime()); put("candidates", JSONArray(candidates))
                        put("before", before); put("passed", true)
                    }.toString(2))
                } else {
                    require(phase == "cleanup")
                    connection.runOnReady {
                        setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(
                            RawConfig("Action", "switch"), RawConfig("Name", before.getString("previous_profile")))))
                        setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(
                            RawConfig("Action", "delete"), RawConfig("Name", profile))))
                    }
                    check(receipt.delete())
                }
            }
        } finally { FcitxDaemon.disconnect(owner) }
    }
}
