/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.fcitx.directrime.Native
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Run this class/method alone in a fresh instrumentation invocation, without an IME client.
 * Uses the built-in librime directly and only disposable cache profiles plus smoke assets from
 * the test APK. It does not resolve or change the app's engine selection, profiles or presets.
 */
@RunWith(AndroidJUnit4::class)
class ReviewNativeConfigDeviceTest {
    @Test fun noSessionCurrentSchemaMatchesRealLibrimeAcrossYamlForms() {
        val className = javaClass.name
        val methodName = "noSessionCurrentSchemaMatchesRealLibrimeAcrossYamlForms"
        val selector = InstrumentationRegistry.getArguments().getString("class")
        assertTrue("Run this class/method alone in a fresh instrumentation invocation",
            selector == className || selector == "$className#$methodName")
        assertNull("An IME client already owns Native", FcitxDaemon.getFirstConnectionOrNull())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // app/androidTest mounts direct-rime/src/main/assets in the test APK, not the target APK.
        val assets = instrumentation.context.assets
        val root = File(context.cacheDir, "review-native-config-" + UUID.randomUUID())
        val shared = File(root, "shared")
        val profiles = File(root, "profiles")
        var rootOwned = false
        var ownsNative = false
        var stopped = true
        var generation: Long? = null

        fun stopOwnedNative() {
            if (!ownsNative || stopped) return
            try {
                generation?.let { Native.end(it) }
            } finally {
                Native.stop()
                generation = null
                stopped = true
            }
        }

        try {
            assertEquals(context.cacheDir.canonicalFile, root.canonicalFile.parentFile)
            assertTrue("Refuse to reuse a cache root", root.mkdir())
            rootOwned = true
            assertTrue(shared.mkdir())
            assertTrue(profiles.mkdir())
            val smokeFiles = listOf("default.yaml", "$FIRST.schema.yaml", "$FIRST.dict.yaml")
            smokeFiles.forEach { name ->
                assets.open("smoke/$name").use { input ->
                    File(shared, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            val defaultFile = File(shared, "default.yaml")
            val originalDefault = defaultFile.readText().replace("\r\n", "\n")
            assertTrue("The smoke fixture changed", originalDefault.contains("  - schema: $FIRST\n"))
            defaultFile.writeText(originalDefault.replace("  - schema: $FIRST\n",
                "  - schema: $FIRST\n  - schema: $SECOND\n") + """
                switcher:
                  hotkeys: []
                  save_options: []
                  fix_schema_list_order: false
            """.trimIndent() + "\n")
            val originalSchema = File(shared, "$FIRST.schema.yaml").readText().replace("\r\n", "\n")
            assertTrue("The smoke schema fixture changed", originalSchema.contains("schema_id: $FIRST\n"))
            File(shared, "$SECOND.schema.yaml").writeText(originalSchema.replace(
                "schema_id: $FIRST\n", "schema_id: $SECOND\n"))

            val cases = yamlCases()
            cases.forEach { case ->
                val user = File(profiles, case.name)
                assertTrue(user.mkdir())
                assertEquals(profiles.canonicalFile, user.canonicalFile.parentFile)
                val syncPath = File(user, "sync").absolutePath.replace("'", "''")
                File(user, "installation.yaml").writeText("""
                    installation_id: review-native-config
                    sync_dir: '$syncPath'
                """.trimIndent() + "\n")
                File(user, "default.custom.yaml").writeText(
                    "patch:\n  switcher/fix_schema_list_order: " + case.fixedOrder + "\n")
            }
            assertNull("An IME client connected before Native selection", FcitxDaemon.getFirstConnectionOrNull())
            Native.selectBuiltin()
            ownsNative = true

            // No coroutine launches or suspension: Native keeps one OS-thread owner for this test.
            fun runCase(case: YamlCase, withSession: Boolean, probed: String? = null): String {
                assertTrue("The preceding case must be stopped", stopped)
                assertNull(case.name + ": an IME client connected", FcitxDaemon.getFirstConnectionOrNull())
                val user = File(profiles, case.name)
                // Only rewrite after the previous engine was finalized; never rely on a live config cache.
                File(user, "user.yaml").writeText(case.yaml)
                stopped = false
                try {
                    Native.start(shared.absolutePath, user.absolutePath, "review-native-config", fullCheck = true)
                    val before = Native.sessionStats()
                    assertEquals(case.name + ": sessionStats shape", 3, before.size)
                    if (!withSession) {
                        assertTrue(case.name + ": fresh no-session discovery: " + before.contentToString(),
                            before.all { it == 0L })
                    }
                    assertArrayEquals(case.name + ": deployed schema order", arrayOf(FIRST, SECOND), Native.schemas())
                    val discovered = Native.currentSchema()
                    assertEquals(case.name + ": no-session currentSchema", case.expected, discovered)
                    assertArrayEquals(case.name + ": discovery must not create/reuse/end a session",
                        before, Native.sessionStats())
                    if (probed != null) assertEquals(case.name + ": result after a clean restart", probed, discovered)
                    if (withSession) {
                        assertNull(case.name + ": an IME client connected before beginInput",
                            FcitxDaemon.getFirstConnectionOrNull())
                        val epoch = System.nanoTime().coerceAtLeast(1)
                        generation = epoch
                        var snapshot = Native.beginInput(epoch, "review-native-config", noLearning = true)
                        assertTrue(case.name + ": beginInput: " + snapshot.gateMessage, snapshot.ready)
                        assertEquals(case.name + ": actual new session schema", discovered, snapshot.schema)
                        assertEquals(case.name + ": beginInput must not commit", "", snapshot.commit)
                        val afterBegin = Native.sessionStats()
                        assertEquals(case.name + ": exactly one real session", before[0] + 1, afterBegin[0])
                        assertEquals(case.name + ": no session reuse", before[1], afterBegin[1])
                        assertEquals(case.name + ": no premature end", before[2], afterBegin[2])
                        "ni".forEach { snapshot = Native.key(epoch, it.code, 0, release = false) }
                        assertTrue(case.name + ": real smoke dictionary must produce candidates",
                            snapshot.ready && snapshot.candidates.contains("你"))
                        assertEquals(case.name + ": typing retains the selected schema", discovered, snapshot.schema)
                        assertEquals(case.name + ": typing must not commit", "", snapshot.commit)
                        assertEquals(case.name + ": endInput must not commit", "", Native.end(epoch).commit)
                        generation = null
                        assertArrayEquals(case.name + ": exactly one session ended",
                            longArrayOf(before[0] + 1, before[1], before[2] + 1), Native.sessionStats())
                    }
                    Log.i(TAG, "phase=" + (if (withSession) "session" else "no-session") +
                        " case=" + case.name + " schema=" + discovered + " stats=" + Native.sessionStats().contentToString())
                    assertNull(case.name + ": an IME client connected during the case",
                        FcitxDaemon.getFirstConnectionOrNull())
                    return discovered
                } finally {
                    stopOwnedNative()
                }
            }

            // Engine-slot statistics survive stop/start. Probe EVERY YAML form before creating
            // ANY editor so all three counters really remain zero in every no-session case.
            val discovered = cases.associate { it.name to runCase(it, withSession = false) }
            // Then activate a real librime session for every form and compare the same expectations.
            cases.forEach { runCase(it, withSession = true, probed = discovered.getValue(it.name)) }
        } finally {
            try {
                stopOwnedNative()
            } finally {
                // Never delete files under an engine whose finalization failed.
                if (rootOwned && stopped) {
                    assertEquals(context.cacheDir.canonicalFile, root.canonicalFile.parentFile)
                    assertTrue("Cannot remove the test-owned cache root: " + root.path, root.deleteRecursively())
                    assertTrue("Test-owned cache root remains", !root.exists())
                }
            }
        }
    }

    private data class YamlCase(
        val name: String,
        val yaml: String,
        val expected: String,
        val fixedOrder: Boolean = false
    )

    private fun yamlCases() = listOf(
        YamlCase("single-quoted", "var:\n  previously_selected_schema: '$SECOND'\n", SECOND),
        YamlCase("double-quoted", "var:\n  previously_selected_schema: \"$SECOND\"\n", SECOND),
        YamlCase("plain", "var:\n  previously_selected_schema: $SECOND\n", SECOND),
        YamlCase("single-quoted-comment", "var:\n  previously_selected_schema: '$SECOND' # selected\n", SECOND),
        YamlCase("double-quoted-comment", "var:\n  previously_selected_schema: \"$SECOND\" # selected\n", SECOND),
        YamlCase("plain-comment", "var:\n  previously_selected_schema: $SECOND # selected\n", SECOND),
        YamlCase("wrong-root-path", "previously_selected_schema: $SECOND\n", FIRST),
        YamlCase("wrong-nested-path", "other:\n  previously_selected_schema: $SECOND\n", FIRST),
        YamlCase("decoy-before-var", "other:\n  previously_selected_schema: $FIRST\nvar:\n  previously_selected_schema: '$SECOND' # selected\n", SECOND),
        YamlCase("unavailable-schema", "var:\n  previously_selected_schema: review_native_unavailable # not in schema_list\n", FIRST),
        YamlCase("fixed-list-order", "var:\n  previously_selected_schema: '$SECOND' # ignored by fixed order\n", FIRST, fixedOrder = true)
    )

    private companion object {
        const val FIRST = "direct_smoke"
        const val SECOND = "review_native_second"
        const val TAG = "ReviewNativeConfigTest"
    }
}
