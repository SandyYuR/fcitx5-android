/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fcitx.directrime.Native
import org.fcitx.directrime.Snapshot
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Run this class alone in a fresh instrumentation invocation, with no connected IME client.
 * Requires the installed SandyYuR EVO provider containing shared input_types support, or an
 * app-readable absolute -e enginePath with its required -e engineSha256 (64 hex digits). The input
 * file is read-only to this test and is never cleaned up. The standard runner starts the target
 * Application; only the native probe uses an isolated UID.
 * All authorization, managed executable, schema and preference writes belong to disposable cache
 * roots. This test does not use the app engine store, DataManager.sync, settings or real profiles.
 */
@RunWith(AndroidJUnit4::class)
class RimeEvoInputTypesDeviceTest {
    @Test fun evoSwitchesCompiledInputTypesAndRestoresReset(): Unit = runBlocking {
        val className = this@RimeEvoInputTypesDeviceTest.javaClass.name
        val arguments = InstrumentationRegistry.getArguments()
        val selector = arguments.getString("class")
        assertTrue("Run this class/method alone in a fresh instrumentation invocation",
            selector in setOf(className, "$className#evoSwitchesCompiledInputTypesAndRestoresReset"))
        assertNull("An IME client already owns Native", FcitxDaemon.getFirstConnectionOrNull())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val enginePath = arguments.getString("enginePath")
        val engineSha256 = arguments.getString("engineSha256")
        require(enginePath != null || engineSha256 == null) { "engineSha256 requires enginePath" }
        val expectedSha256 = if (enginePath != null) {
            require(engineSha256 != null && engineSha256.matches(Regex("[0-9A-Fa-f]{64}"))) {
                "Imported mode requires -e engineSha256 with exactly 64 hex digits"
            }
            engineSha256.lowercase()
        } else null
        val candidate = if (enginePath == null) {
            RimeEnginePlugins.discover(context).singleOrNull { it.packageName == PROVIDER }
                ?.also {
                    assertEquals(1, it.apiVersion)
                    assertEquals(1, it.rimeApiAbi)
                    assertTrue(it.certificateSha256.isNotBlank())
                    assertTrue(it.staticInfo.sha256.isNotBlank())
                } ?: error("Required EVO provider was not discovered: $PROVIDER")
        } else null
        val source = if (enginePath != null) File(enginePath).also {
            require(it.isAbsolute && it.isFile) { "enginePath must name an app-readable absolute file" }
        } else File(requireNotNull(candidate).libraryPath)
        val staticInfo = candidate?.staticInfo ?: RimeEngineElf.inspect(source)
        if (expectedSha256 != null) assertEquals("Imported source SHA-256", expectedSha256, staticInfo.sha256)
        val runtime = RimeEngineProbeService.probe(context, source, staticInfo)
        assertTrue(runtime.apiDataSize > 0)
        assertTrue(runtime.librimeVersion.isNotBlank())
        assertTrue("Native probe must be isolated", "isolated-uid" in runtime.capabilities)
        assertTrue("Native probe must use a sealed copy", "sealed-memfd" in runtime.capabilities)
        val verified = candidate?.let { RimeEnginePlugins.requireCurrent(context, it) }

        // No suspension from configureExternal through finalization: Native has one OS-thread owner.
        val id = System.nanoTime().coerceAtLeast(1).toString()
        val schema = "evo_input_types_$id"
        val root = File(context.cacheDir, "evo-input-types-$id")
        val executionRoot = File(context.codeCacheDir, "evo-input-types-$id")
        val shared = File(root, "shared")
        val user = File(root, "user")
        var rootOwned = false
        var executionOwned = false
        var ownsNative = false
        var generation: Long? = null
        try {
            assertEquals(context.cacheDir.canonicalFile, root.canonicalFile.parentFile)
            assertTrue("Refuse to reuse a cache root", root.mkdir())
            rootOwned = true
            assertEquals(context.codeCacheDir.canonicalFile, executionRoot.canonicalFile.parentFile)
            assertTrue("Refuse to reuse an execution root", executionRoot.mkdir())
            executionOwned = true
            assertTrue(shared.mkdir())
            assertTrue(user.mkdir())
            writeProfile(user, schema)
            val custom = File(user, "$schema.custom.yaml")
            val originalCustom = custom.readText()
            val repositoryRoot = File(root, "repository")
            val repository = RimeEngineRepository(repositoryRoot, executionRoot)
            val resolved = if (verified != null) {
                repository.authorizePlugin(PluginRimeEngineAuthorization(
                    packageName = verified.packageName,
                    displayName = verified.displayName,
                    providerId = verified.providerId,
                    certificateSha256 = verified.certificateSha256,
                    payloadSha256 = verified.staticInfo.sha256,
                    soname = verified.soname,
                    staticInfo = verified.staticInfo,
                    runtimeInfo = runtime,
                    profile = schema,
                    authorizedAt = System.currentTimeMillis()
                ))
                repository.select(RimeEngineSelection(RimeEngineKind.PLUGIN, PROVIDER), "rime")
                repository.beginActivation()
                repository.resolveActive(listOf(verified)) as ResolvedRimeEngine.External
            } else {
                val record = repository.installImported(source, "EVO input-types test", staticInfo, runtime, schema)
                assertEquals(expectedSha256, record.id)
                val managed = repository.importedFile(record)
                assertNotEquals(source.canonicalPath, managed.canonicalPath)
                assertTrue(managed.canonicalPath.startsWith(repositoryRoot.canonicalPath + File.separator))
                assertEquals(expectedSha256, RimeEngineElf.sha256(managed))
                repository.select(RimeEngineSelection(RimeEngineKind.IMPORTED, record.id), "rime")
                repository.beginActivation()
                repository.resolveActive(emptyList()) as ResolvedRimeEngine.External
            }
            val executable = File(resolved.filePath)
            assertNotEquals(source.canonicalPath, executable.canonicalPath)
            assertTrue(executable.canonicalPath.startsWith(executionRoot.canonicalPath + File.separator))
            assertEquals(staticInfo.sha256, resolved.payloadSha256)
            assertEquals(staticInfo.sha256, RimeEngineElf.sha256(executable))
            assertTrue("The validated engine must belong to the EVO family", resolved.evoFamily)
            verified?.let { RimeEnginePlugins.requireCurrent(context, it) }
            assertNull("An IME client connected during the probe", FcitxDaemon.getFirstConnectionOrNull())
            ParcelFileDescriptor.open(executable, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val actual = Native.configureExternal(descriptor.fd, resolved.entrySymbol, resolved.payloadSha256)
                ownsNative = true
                assertEquals(runtime.librimeVersion, actual.firstOrNull())
            }
            val identity = if (verified == null) "imported" else "provider=$PROVIDER"
            Log.i(TAG, "$identity sha256=" + resolved.payloadSha256 + " version=" + runtime.librimeVersion +
                " soname=" + staticInfo.soname + " buildId=" + staticInfo.buildId)
            Native.start(shared.absolutePath, user.absolutePath, "evo-input-types-test", fullCheck = true)
            assertTrue("The temporary schema was not deployed", schema in Native.schemas())
            val stats = Native.sessionStats()
            assertEquals(3, stats.size)
            assertTrue("Run in a fresh process: " + stats.contentToString(), stats.all { it == 0L })
            assertState(schema, "a", "a", "b")
            assertFalse("Disabled type must not be selectable", Native.selectInputType(schema, "c", true))
            assertEquals(originalCustom, custom.readText())

            val aPrism = File(user, "build/$schema.a.prism.bin")
            val bPrism = File(user, "build/$schema.b.prism.bin")
            assertTrue(aPrism.isFile && aPrism.length() > 0)
            assertTrue(bPrism.isFile && bPrism.length() > 0)
            assertFalse("Disabled type must not be compiled", File(user, "build/$schema.c.prism.bin").exists())
            val heldPrism = File(user, "build/$schema.b.prism.bin.held-by-test")
            assertFalse(heldPrism.exists())
            assertTrue("Cannot hide the test-owned prism", bPrism.renameTo(heldPrism))
            try {
                assertState(schema, "a", "a")
                assertFalse("Enabled but undeployed type must be rejected", Native.selectInputType(schema, "b", true))
            } finally {
                assertTrue("Cannot restore the test-owned prism", heldPrism.renameTo(bPrism))
            }
            assertState(schema, "a", "a", "b")
            assertTrue("State discovery must not create an editor", stats.contentEquals(Native.sessionStats()))

            generation = System.nanoTime().coerceAtLeast(1)
            var current = Native.beginInput(generation, "evo-input-types-test", noLearning = true)
            assertEmpty("initial editor", current, schema)
            current = typeA(current, schema, "甲")
            val artifacts = listOf("$schema.schema.yaml", "$schema.table.bin", "$schema.a.prism.bin", "$schema.b.prism.bin")
                .map { name ->
                    val file = File(user, "build/$name")
                    assertTrue("Missing deployed artifact: $name", file.isFile && file.length() > 0)
                    Artifact(file, file.lastModified(), file.readBytes())
                }
            val selected = RimeInputTypesRepository.select(user, "b", schema) {
                Native.selectInputType(schema, "b", true, current.generation)
            }
            assertEquals("b", selected.reset)
            assertEquals(listOf("a", "b"), selected.enabled)
            assertEquals(listOf("a", "b", "c"), selected.types.map { it.option })
            assertEquals("Only reset may change; preserve enabled/options/comments/unrelated patches",
                originalCustom.replace("input_types/reset: a", "input_types/reset: b"), custom.readText())
            assertState(schema, "b", "a", "b")
            current = Native.action(current.generation, current.revision, READ, 0, "")
            assertCandidates("live composition after immediate switch", current, schema, "乙")
            current = Native.action(current.generation, current.revision, CLEAR, 0, "")
            assertEmpty("clear after switch", current, schema)
            current = typeA(current, schema, "乙")
            artifacts.forEach {
                assertEquals("Switch must not redeploy " + it.file.name, it.modified, it.file.lastModified())
                assertTrue("Switch changed deployed bytes: " + it.file.name, it.bytes.contentEquals(it.file.readBytes()))
            }

            assertEquals("", Native.end(generation).commit)
            generation = generation + 1
            current = Native.beginInput(generation, "evo-input-types-reopened", noLearning = true)
            assertEmpty("new editor must not resurrect the old composition", current, schema)
            assertState(schema, "b", "a", "b")
            current = typeA(current, schema, "乙")
            assertEquals("", Native.end(generation).commit)
            generation = null
            Native.stop()
            // This direct Native test recompiles the patched reset. Normal app restart also has
            // an owner restorePreferences path; beginInput alone does not read custom.yaml.
            Native.start(shared.absolutePath, user.absolutePath, "evo-input-types-restart", fullCheck = true)
            assertState(schema, "b", "a", "b")
            assertEquals("b", RimeInputTypesRepository.read(user, schema)?.reset)
            generation = System.nanoTime().coerceAtLeast(1)
            current = Native.beginInput(generation, "evo-input-types-restarted", noLearning = true)
            assertEmpty("restarted editor", current, schema)
            typeA(current, schema, "乙")
        } finally {
            var stopped = !ownsNative
            try {
                if (ownsNative) {
                    try {
                        generation?.let { Native.end(it) }
                    } finally {
                        Native.stop()
                        stopped = true
                        Native.selectBuiltin()
                    }
                }
            } finally {
                // A failed stop deliberately retains the owned files instead of deleting live data.
                if (stopped) {
                    if (executionOwned) assertTrue(executionRoot.deleteRecursively())
                    if (rootOwned) assertTrue(root.deleteRecursively())
                }
            }
        }
    }

    private fun writeProfile(user: File, schema: String) {
        val syncPath = File(user, "sync").absolutePath.replace("'", "''")
        File(user, "default.yaml").writeText("""
            config_version: '9999.0'
            schema_list:
              - schema: $schema
            switcher:
              hotkeys: []
              save_options: []
            menu:
              page_size: 5
        """.trimIndent() + "\n")
        File(user, "installation.yaml").writeText("""
            installation_id: $schema
            sync_dir: '$syncPath'
        """.trimIndent() + "\n")
        File(user, "$schema.schema.yaml").writeText("""
            schema:
              schema_id: $schema
              name: EVO isolated input types
              version: '1.0'
            switches:
              - name: ascii_mode
                reset: 0
                states: [Chinese, ASCII]
            engine:
              processors: [speller, selector, express_editor]
              segmentors: [abc_segmentor]
              translators: [table_translator]
            speller:
              alphabet: ab
            input_types:
              options:
                - {option: a, label: Identity}
                - {option: b, label: Swapped}
                - {option: c, label: Disabled}
              enabled: [a, b]
              reset: a
            translator:
              dictionary: $schema
              prisms: input_types
              user_dict_mode: off
              enable_user_dict: false
              enable_completion: false
              enable_sentence: false
              contextual_suggestions: false
              enable_charset_filter: false
            build:
              dictionaries:
                $schema:
                  algebra:
                    a: []
                    b: [xlit/ab/ba/]
                    c: []
        """.trimIndent() + "\n")
        File(user, "$schema.dict.yaml").writeText("""
            ---
            name: $schema
            version: '1.0'
            sort: by_weight
            use_preset_vocabulary: false
            ...
        """.trimIndent() + "\n甲\ta\t100\n乙\tb\t100\n")
        File(user, "$schema.custom.yaml").writeText("""
            # Keep this comment and the unrelated patch byte-for-byte.
            patch:
              input_types/enabled: [a, b]
              input_types/options:
                - {option: a, label: Identity}
                - {option: b, label: Swapped}
                - {option: c, label: Disabled}
              input_types/reset: a
              menu/page_size: 5
        """.trimIndent() + "\n")
    }

    private fun assertState(schema: String, vararg expected: String) {
        val actual = Native.inputTypeState(schema, true)
        assertEquals("selected + enabled/compiled input types", expected.toList(), actual.toList())
    }

    private fun assertEmpty(label: String, snapshot: Snapshot, schema: String) {
        assertReady(label, snapshot, schema)
        assertEquals(label, "", snapshot.preedit)
        assertTrue(label, snapshot.candidates.isEmpty())
        assertEquals(label, "", Native.rawInput(snapshot.generation, snapshot.revision)?.text)
    }

    private fun typeA(previous: Snapshot, schema: String, expected: String): Snapshot {
        val current = Native.key(previous.generation, 'a'.code, 0, false)
        assertCandidates("actual code a", current, schema, expected)
        return current
    }

    private fun assertCandidates(label: String, snapshot: Snapshot, schema: String, expected: String) {
        assertReady(label, snapshot, schema)
        assertEquals(label, "a", Native.rawInput(snapshot.generation, snapshot.revision)?.text)
        assertEquals(label, listOf(expected), snapshot.candidates.toList())
        val pairs = Native.candidates(snapshot.generation, 0, 8)
        assertEquals(label + " candidate text/comment pairs", 0, pairs.size % 2)
        assertEquals(label + " full native candidate query", listOf(expected), pairs.toList().chunked(2).map { it[0] })
    }

    private fun assertReady(label: String, snapshot: Snapshot, schema: String) {
        assertTrue(label + ": " + snapshot.gateMessage, snapshot.ready)
        assertEquals(label, schema, snapshot.schema)
        assertEquals(label + " must not commit", "", snapshot.commit)
    }

    private data class Artifact(val file: File, val modified: Long, val bytes: ByteArray)

    companion object {
        private const val PROVIDER = "org.rime.plugin.sandyyur.librime_evo"
        private const val TAG = "EvoInputTypesDeviceTest"
        private const val CLEAR = 0
        private const val READ = 13
    }
}
