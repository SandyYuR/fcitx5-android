/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fcitx.directrime.Native
import org.fcitx.directrime.Snapshot
import org.fcitx.fcitx5.android.core.DirectRimeCandidateSequence
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Run alone in a fresh instrumentation invocation, with no connected IME client:
 * -e class org.fcitx.fcitx5.android.core.engine.RimeEvoOrderingDeviceTest#installedEvoPersistsActualManualOrdering
 *
 * All engine/profile writes use this test's disposable cache roots. The standard runner still
 * starts the target Application; this is process/profile isolation, not an isolated-UID IME test.
 * Only the provider ABI probe runs in an Android isolated service. No app settings, DataManager.sync,
 * user schemas or external profile archive are used. A missing/incompatible EVO fails this test.
 */
@RunWith(AndroidJUnit4::class)
class RimeEvoOrderingDeviceTest {
    @Test fun installedEvoPersistsActualManualOrdering(): Unit = runBlocking {
        val selector = InstrumentationRegistry.getArguments().getString("class")
        assertTrue("Run this class/method alone in a fresh instrumentation invocation", selector in setOf(
            this@RimeEvoOrderingDeviceTest.javaClass.name,
            "${this@RimeEvoOrderingDeviceTest.javaClass.name}#installedEvoPersistsActualManualOrdering"))
        assertNull("An IME client already owns Native; run this test alone", FcitxDaemon.getFirstConnectionOrNull())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidate = RimeEnginePlugins.discover(context).singleOrNull { it.packageName == PROVIDER }
            ?: error("Required EVO provider was not discovered: $PROVIDER")
        assertEquals(1, candidate.apiVersion)
        assertEquals(1, candidate.rimeApiAbi)
        assertTrue(candidate.certificateSha256.isNotBlank())
        assertTrue(candidate.staticInfo.sha256.isNotBlank())
        val runtime = RimeEngineProbeService.probe(context, File(candidate.libraryPath), candidate.staticInfo)
        assertTrue(runtime.apiDataSize > 0)
        assertTrue(runtime.librimeVersion.isNotBlank())
        assertTrue("Provider probe is not isolated", "isolated-uid" in runtime.capabilities)
        assertTrue("Provider probe is not sealed", "sealed-memfd" in runtime.capabilities)
        val verified = RimeEnginePlugins.requireCurrent(context, candidate)

        // From here through finalization all Native calls stay on this OS thread. In particular,
        // do not launch/suspend between beginInput and action: Engine enforces a single owner.
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "evo-ordering-$id")
        val executionRoot = File(context.codeCacheDir, "evo-ordering-$id")
        val shared = File(root, "shared")
        val user = File(root, "user")
        var configured = false
        try {
            assertTrue(shared.mkdirs())
            assertTrue(user.mkdirs())
            writeProfile(shared, user)
            val repository = RimeEngineRepository(File(root, "repository"), executionRoot)
            repository.authorizePlugin(PluginRimeEngineAuthorization(
                packageName = verified.packageName,
                displayName = verified.displayName,
                providerId = verified.providerId,
                certificateSha256 = verified.certificateSha256,
                payloadSha256 = verified.staticInfo.sha256,
                soname = verified.soname,
                staticInfo = verified.staticInfo,
                runtimeInfo = runtime,
                profile = SCHEMA,
                authorizedAt = System.currentTimeMillis()
            ))
            repository.select(RimeEngineSelection(RimeEngineKind.PLUGIN, PROVIDER), "rime")
            repository.beginActivation()
            val resolved = repository.resolveActive(listOf(verified)) as ResolvedRimeEngine.External
            val executable = File(resolved.filePath)
            assertNotEquals(File(verified.libraryPath).canonicalPath, executable.canonicalPath)
            assertTrue(executable.canonicalPath.startsWith(executionRoot.canonicalPath + File.separator))
            assertEquals(verified.staticInfo.sha256, RimeEngineElf.sha256(executable))
            // Close the discovery/publication race before configuring the immutable read-only copy.
            RimeEnginePlugins.requireCurrent(context, verified)
            ParcelFileDescriptor.open(executable, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val actual = Native.configureExternal(descriptor.fd, resolved.entrySymbol, resolved.payloadSha256)
                configured = true
                assertEquals(runtime.librimeVersion, actual[0])
            }
            Log.i(TAG, "provider=$PROVIDER sha256=${resolved.payloadSha256} version=${runtime.librimeVersion}")
            Native.start(shared.absolutePath, user.absolutePath, "evo-ordering-test", fullCheck = true)
            val untouched = Native.sessionStats()
            assertEquals(3, untouched.size)
            assertTrue("Native already has session history: ${untouched.contentToString()}", untouched.all { it == 0L })
            assertTrue("Temporary EVO schema was not deployed", SCHEMA in Native.schemas())
            var generation = System.nanoTime().coerceAtLeast(1)
            var current = compose(generation)
            val bindings = Native.sequenceKeys(true)
            assertEquals("EVO raw-binding ABI", "evo-editor-bindings-v1", bindings.firstOrNull())
            val rawPairs = bindings.drop(1).chunked(2)
            assertTrue("Conflicting noop pair must survive native discovery", listOf("Shift+Control+F12", "noop") in rawPairs)
            assertTrue("Unsupported-modifier pair must survive native discovery",
                listOf("Lock+Control+Delete", "manual_delete") in rawPairs)
            assertTrue("Modifier-order alias pair must survive native discovery",
                listOf("Control+Shift+F12", "manual_delete") in rawPairs)
            assertTrue("Equivalent binding colliding with noop must be hidden",
                DirectRimeCandidateSequence.resolveKeys(arrayOf(
                    "evo-editor-bindings-v1", "Control+Shift+F12", "manual_delete",
                    "Shift+Control+F12", "noop"), evo = true).isEmpty())
            val keys = DirectRimeCandidateSequence.resolveKeys(bindings, evo = true)
            assertEquals("Resolved EVO up/down/manual-reset/pin/auto-delete slots", 5, keys.size)
            assertEquals("EVO editor manual_up binding", "Control+F10", keys[0])
            assertEquals("EVO editor manual_down binding", "Control+F11", keys[1])
            assertEquals("EVO editor manual_delete binding", "Shift+Delete", keys[2])
            assertEquals("EVO must not invent a pin action", "", keys[3])
            assertEquals("EVO editor auto_delete binding", "Control+Delete", keys[4])
            assertEquals("manual_up native key", "65479 4", nativeKey(keys[0]))
            assertEquals("manual_down native key", "65480 4", nativeKey(keys[1]))
            assertEquals("manual_delete native key", "65535 1", nativeKey(keys[2]))
            assertEquals("auto_delete native key", "65535 4", nativeKey(keys[4]))
            val legacyKeys = Native.sequenceKeys(false)
            assertTrue("Non-Wanxiang schema must not expose legacy sequence bindings", legacyKeys.isEmpty())
            assertOrder("initial dictionary", current, WORDS)

            current = ordered("page-local up", current, LOCAL, 2, keys[0], moved(WORDS, 2, 1), 1)
            // Already highlighted: librime may return false from Highlight, but the move must run.
            current = ordered("already-highlighted up", current, LOCAL, 1, keys[0], moved(WORDS, 2, 0), 0)
            current = ordered("page-local down", current, LOCAL, 0, keys[1], moved(WORDS, 2, 1), 1)
            current = ordered("manual reset restores original weight", current, LOCAL, 1, keys[2], WORDS, 1)

            current = Native.action(generation, current.revision, PAGE, 1, "")
            assertOrder("second page", current, WORDS)
            assertEquals(1, current.page)
            current = ordered("second-page local slot is not global slot", current, LOCAL, 1, keys[0], moved(WORDS, 4, 3), 3)
            current = ordered("second-page reset", current, LOCAL, 0, keys[2], WORDS, 3)
            current = Native.action(generation, current.revision, PAGE, -1, "")
            assertOrder("back to first page", current, WORDS)
            assertEquals(0, current.page)
            current = ordered("global target from a different page", current, GLOBAL, 4, keys[0], moved(WORDS, 4, 3), 3)
            current = ordered("global reset", current, GLOBAL, 3, keys[2], WORDS, 3)
            current = ordered("up across page boundary", current, GLOBAL, 3, keys[0], moved(WORDS, 3, 2), 2)
            current = ordered("down across page boundary", current, GLOBAL, 2, keys[1], WORDS, 3)
            current = unchanged("clear natural-slot manual state", current, GLOBAL, 3, keys[2], WORDS)

            // End the menu on page 0, highlighted slot 1. An out-of-range global index with
            // the same modulo must not accidentally deliver the key to that visible candidate.
            current = ordered("prepare wrong-page collision", current, GLOBAL, 2, keys[0], moved(WORDS, 2, 1), 1)
            val collisionOrder = moved(WORDS, 2, 1)
            current = rejectedAfterHighlight("out-of-range global with highlighted modulo", current, GLOBAL, 10, keys[0], collisionOrder, page = 2, highlighted = 1)
            current = unchanged("negative global target", current, GLOBAL, -1, keys[0], collisionOrder)
            current = unchanged("out-of-range local target", current, LOCAL, PAGE_SIZE, keys[0], collisionOrder)
            current = ordered("cleanup collision setup", current, GLOBAL, 1, keys[2], WORDS, 1)

            val oldRevision = current
            val persisted = moved(WORDS, 6, 5)
            current = ordered("persistent move across page boundary", current, GLOBAL, 6, keys[0], persisted, 5)
            assertStale("stale revision", Native.action(generation, oldRevision.revision, GLOBAL, 1, nativeKey(keys[0])))
            current = Native.action(generation, current.revision, READ, 0, "")
            assertOrder("stale revision cannot mutate newer ordering", current, persisted)

            val oldGeneration = current
            assertEquals("end must not commit", "", Native.end(generation).commit)
            current = compose(++generation)
            assertOrder("new generation sees manual order", current, persisted)
            assertStale("stale generation", Native.action(oldGeneration.generation,
                oldGeneration.revision, GLOBAL, 5, nativeKey(keys[2])))
            current = Native.action(generation, current.revision, READ, 0, "")
            assertOrder("stale generation cannot reset current ordering", current, persisted)

            assertEquals("end must not commit", "", Native.end(generation).commit)
            Native.stop()
            Native.start(shared.absolutePath, user.absolutePath, "evo-ordering-restart", fullCheck = false)
            current = compose(++generation)
            assertOrder("manual order survives stop/finalize/start", current, persisted)
            assertTrue("No UserDB was created in disposable user profile", user.walkTopDown().any {
                it.name.startsWith(SCHEMA + ".userdb")
            })
            current = ordered("reset persisted manual position", current, GLOBAL, 5, keys[2], WORDS, 5)
            assertEquals("end must not commit", "", Native.end(generation).commit)
            Native.stop()
            Native.start(shared.absolutePath, user.absolutePath, "evo-ordering-reset-restart", fullCheck = false)
            current = compose(++generation)
            assertOrder("reset survives stop/finalize/start", current, WORDS)
            assertEquals("end must not commit", "", Native.end(generation).commit)

            // Deliberate setup commits are confined to the temporary profile. Every ordering/reset
            // operation must leave commit empty. A positive automatic record makes Ctrl+Delete (reset
            // auto learning) observably different from the requested Shift+Delete manual reset.
            current = compose(++generation)
            assertOrder("before automatic-learning setup", current, WORDS)
            val learnedCommit = Native.action(generation, current.revision, SELECT_GLOBAL, 0, "")
            assertEquals("Automatic-learning setup must commit exactly its target", WORDS[0], learnedCommit.commit)
            finishLearning(generation, "automatic-learning setup")
            assertEquals("end must not replay setup commit", "", Native.end(generation).commit)
            assertTrue("Cannot snapshot disposable automatic-learning state", Native.sync())
            val learned = userState(user, WORDS[0])
            assertTrue("Automatic-learning setup did not create commits", learned.automatic[0].toInt() > 0)
            assertEquals("Fresh learned record has no manual position", 0, learned.position)

            current = compose(++generation)
            assertOrder("learned word before manual move", current, WORDS)
            current = ordered("move automatically learned word down", current, GLOBAL, 0, keys[1], moved(WORDS, 0, 1), 1)
            assertEquals("end must not commit", "", Native.end(generation).commit)
            assertTrue("Cannot snapshot disposable manual position", Native.sync())
            val manual = userState(user, WORDS[0])
            assertEquals("Manual move changed automatic commits/dynamic/last_tick", learned.automatic, manual.automatic)
            assertEquals("Manual move did not persist one-based position", 2, manual.position)

            current = compose(++generation)
            assertOrder("learned manual position survives sync/finalize", current, moved(WORDS, 0, 1))
            current = ordered("Shift+Delete resets only manual position", current, GLOBAL, 1, keys[2], WORDS, 1)
            assertEquals("end must not commit", "", Native.end(generation).commit)
            assertTrue("Cannot snapshot disposable Shift+Delete result", Native.sync())
            val reset = userState(user, WORDS[0])
            assertEquals("Shift+Delete erased automatic commits/dynamic/last_tick", learned.automatic, reset.automatic)
            assertEquals("Shift+Delete did not clear manual position", 0, reset.position)
            assertNotEquals("Manual reset must publish a new manual stamp", manual.stamp, reset.stamp)
            Native.stop()
            Native.start(shared.absolutePath, user.absolutePath, "evo-ordering-learned-reset-restart", fullCheck = false)
            current = compose(++generation)
            assertOrder("learned word and manual reset survive restart", current, WORDS)
            assertEquals("end must not commit", "", Native.end(generation).commit)

            // A second code has independent automatic learning. Deleting the a-code target must
            // preserve this witness and every other UserDB record, as well as the target's manual
            // fields. EVO marks automatic deletion; it does not erase the entire user dictionary.
            current = compose(++generation, 'b')
            assertEquals("Independent learning witness", listOf(WITNESS), allCandidates(current))
            val witnessCommit = Native.action(generation, current.revision, SELECT_GLOBAL, 0, "")
            assertEquals("Witness setup must commit exactly its target", WITNESS, witnessCommit.commit)
            finishLearning(generation, "witness setup")
            assertEquals("end must not replay witness commit", "", Native.end(generation).commit)
            assertTrue("Cannot snapshot independent automatic learning", Native.sync())
            val witness = userState(user, WITNESS)
            assertTrue("Witness was not automatically learned", witness.automatic[0].toInt() > 0)

            current = compose(++generation)
            assertOrder("before auto-delete manual setup", current, WORDS)
            val pinned = moved(WORDS, 0, 1)
            current = ordered("prepare learned manual target for auto-delete", current,
                GLOBAL, 0, keys[1], pinned, 1)
            assertEquals("end must not commit", "", Native.end(generation).commit)
            assertTrue("Cannot snapshot auto-delete setup", Native.sync())
            val beforeDelete = userStates(user)
            val targetKey = beforeDelete.keys.single { it.substringAfter('\t') == WORDS[0] }
            val targetBefore = beforeDelete.getValue(targetKey)
            assertTrue("Auto-delete target must have automatic learning", targetBefore.automatic[0].toInt() > 0)
            assertEquals("Auto-delete setup must have a manual position", 2, targetBefore.position)
            assertEquals("Preparing another target changed witness", witness, userState(user, WITNESS))

            current = compose(++generation)
            assertOrder("before Control+Delete", current, pinned)
            current = Native.action(generation, current.revision, GLOBAL, 1, nativeKey(keys[4]))
            assertOrder("Control+Delete keeps valid manual ordering and does not commit", current, pinned)
            assertEquals("end must not commit", "", Native.end(generation).commit)
            assertTrue("Cannot snapshot Control+Delete result", Native.sync())
            val afterDelete = userStates(user)
            val targetAfter = afterDelete.getValue(targetKey)
            assertEquals("Control+Delete must mark only selected automatic learning deleted",
                -targetBefore.automatic[0].toInt(), targetAfter.automatic[0].toInt())
            assertEquals("Control+Delete changed selected manual position", targetBefore.position, targetAfter.position)
            assertEquals("Control+Delete changed selected manual stamp", targetBefore.stamp, targetAfter.stamp)
            assertEquals("Control+Delete changed unrelated UserDB records",
                beforeDelete.filterKeys { it != targetKey }, afterDelete.filterKeys { it != targetKey })
            Native.stop()
            Native.start(shared.absolutePath, user.absolutePath, "evo-ordering-auto-delete-restart", fullCheck = false)
            current = compose(++generation)
            assertOrder("auto-delete preserves manual order across restart", current, pinned)
            assertEquals("end must not commit", "", Native.end(generation).commit)
            assertTrue("Cannot snapshot auto-delete restart", Native.sync())
            assertEquals("Auto-delete state did not persist across restart", afterDelete, userStates(user))
        } finally {
            // Only stop a slot configured by this test; never stop another owner's engine.
            if (configured) {
                Native.stop()
                Native.selectBuiltin()
            }
            assertTrue("Could not remove disposable profile/repository $root", root.deleteRecursively())
            assertTrue("Could not remove disposable executable $executionRoot", executionRoot.deleteRecursively())
        }
    }

    private fun compose(generation: Long, input: Char = 'a'): Snapshot {
        var snapshot = Native.beginInput(generation, "evo-ordering-test", noLearning = false)
        assertTrue("beginInput: ${snapshot.gateMessage}", snapshot.ready)
        assertEquals("beginInput must not commit", "", snapshot.commit)
        snapshot = Native.action(generation, snapshot.revision, SCHEMA_ACTION, 0, SCHEMA)
        assertTrue("select temporary schema: ${snapshot.gateMessage}", snapshot.ready)
        assertEquals("schema selection must not commit", "", snapshot.commit)
        snapshot = Native.key(generation, input.code, 0, release = false)
        assertComposing("type $input", snapshot, input.toString())
        return snapshot
    }

    private fun finishLearning(generation: Long, label: String) {
        // EVO keeps a commit pending until the next unhandled non-Backspace key.
        // An Escape with no composition commits learning before sync/finalize can abort it.
        val after = Native.key(generation, 0xff1b, 0, release = false)
        assertTrue("$label did not finish: ${after.gateMessage}", after.ready)
        assertEquals("$label replayed commit", "", after.commit)
        assertEquals("$label unexpectedly composed", "", after.preedit)
    }

    private fun nativeKey(raw: String): String = requireNotNull(
        DirectRimeCandidateSequence.keyArgument(arrayOf(raw, "", "", "", ""), 2, evo = true)
    ) {
        "Discovered binding cannot be delivered to Native: $raw"
    }

    private fun ordered(label: String, before: Snapshot, action: Int, index: Int, key: String,
                        expected: List<String>, selected: Int): Snapshot {
        val previous = allCandidates(before)
        val after = Native.action(before.generation, before.revision, action, index, nativeKey(key))
        assertNotEquals("$label must change actual ordering", previous, expected)
        assertOrder(label, after, expected)
        assertEquals("$label target page", selected / PAGE_SIZE, after.page)
        assertEquals("$label target local highlight", selected % PAGE_SIZE, after.highlighted)
        assertEquals("$label selected text", expected[selected], after.candidates[after.highlighted])
        return after
    }

    private fun unchanged(label: String, before: Snapshot, action: Int, index: Int, key: String,
                          expected: List<String>): Snapshot {
        val after = Native.action(before.generation, before.revision, action, index, nativeKey(key))
        assertOrder(label, after, expected)
        assertEquals("$label changed page", before.page, after.page)
        assertEquals("$label changed highlight", before.highlighted, after.highlighted)
        return after
    }

    private fun rejectedAfterHighlight(label: String, before: Snapshot, action: Int, index: Int, key: String,
                                       expected: List<String>, page: Int, highlighted: Int): Snapshot {
        // EVO clamps an invalid Highlight to its final candidate. Delivery must still be rejected:
        // the observed page is not the requested page, even when the local slot has the same modulo.
        val after = Native.action(before.generation, before.revision, action, index, nativeKey(key))
        assertOrder(label, after, expected)
        assertEquals("$label changed preedit", before.preedit, after.preedit)
        assertEquals("$label clamped page", page, after.page)
        assertEquals("$label clamped highlight", highlighted, after.highlighted)
        return after
    }

    private fun assertStale(label: String, snapshot: Snapshot) {
        assertEquals("$label must be rejected", 3, snapshot.gate)
        assertEquals("$label must not commit", "", snapshot.commit)
    }

    private fun assertOrder(label: String, snapshot: Snapshot, expected: List<String>) {
        assertComposing(label, snapshot)
        assertEquals("$label full candidate sequence", expected, allCandidates(snapshot))
        val start = snapshot.page * PAGE_SIZE
        assertEquals("$label visible page", expected.drop(start).take(PAGE_SIZE), snapshot.candidates.toList())
        Log.i(TAG, "$label generation=${snapshot.generation} revision=${snapshot.revision}" +
            " page=${snapshot.page} highlight=${snapshot.highlighted} order=$expected")
    }

    private fun assertComposing(label: String, snapshot: Snapshot, input: String = "a") {
        assertTrue("$label: ${snapshot.gateMessage}", snapshot.ready)
        assertEquals("$label unexpectedly committed", "", snapshot.commit)
        assertEquals("$label changed schema", SCHEMA, snapshot.schema)
        assertTrue("$label lost composition", snapshot.preedit.isNotEmpty())
        val raw = Native.rawInput(snapshot.generation, snapshot.revision)
            ?: error("$label lost current raw-input target")
        assertEquals("$label changed raw input", input, raw.text)
    }

    private fun allCandidates(snapshot: Snapshot): List<String> {
        val packed = Native.candidates(snapshot.generation, 0, 256)
        assertEquals("Candidate text/comment array is malformed", 0, packed.size % 2)
        return packed.asList().chunked(2).map { it[0] }
    }

    private data class UserState(val automatic: List<String>, val position: Int, val stamp: String)

    private fun userState(user: File, word: String): UserState =
        userStates(user).entries.single { it.key.substringAfter('\t') == word }.value

    private fun userStates(user: File): Map<String, UserState> {
        // EVO's versioned snapshot serializes commits,dynamic,last_tick,position,manual_stamp.
        // Require real records; a missing/legacy export must fail rather than weaken this check.
        val snapshot = File(user, "sync").walkTopDown()
            .filter { it.isFile && it.name == SCHEMA + ".userdb.tsv" }.single()
        val records = snapshot.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val result = records.associate { line ->
            val record = line.split('\t')
            assertEquals("Unsupported EVO UserDB snapshot row", 3, record.size)
            val fields = record[2].split(',')
            assertEquals("Unsupported EVO UserDB snapshot fields", 5, fields.size)
            val key = record[0] + "\t" + record[1]
            val state = UserState(fields.take(3), fields[3].toInt(), fields[4])
            Log.i(TAG, "UserDB key=$key state=$state")
            key to state
        }
        assertEquals("Duplicate EVO UserDB snapshot keys", records.size, result.size)
        return result
    }

    private fun moved(words: List<String>, from: Int, to: Int): List<String> =
        words.toMutableList().apply { add(to, removeAt(from)) }

    private fun writeProfile(shared: File, user: File) {
        // Pin sync to this disposable profile; no existing installation/settings are read.
        File(user, "installation.yaml").writeText(
            "installation_id: evo-ordering-test\nsync_dir: '${File(user, "sync").absolutePath}'\n")
        File(shared, "default.yaml").writeText("""
            config_version: '1.0'
            schema_list:
              - schema: $SCHEMA
            menu:
              page_size: $PAGE_SIZE
            switcher:
              caption: EVO ordering test
              hotkeys: []
              save_options: []
        """.trimIndent() + "\n")
        File(shared, "$SCHEMA.schema.yaml").writeText("""
            schema:
              schema_id: $SCHEMA
              name: EVO ordering test
              version: '1.0'
            switches:
              - name: ascii_mode
                reset: 0
                states: [Chinese, ASCII]
            engine:
              processors: [speller, selector, express_editor]
              segmentors: [abc_segmentor]
              translators: [table_translator]
            menu:
              page_size: $PAGE_SIZE
            speller:
              alphabet: abcdefghijklmnopqrstuvwxyz
              initials: abcdefghijklmnopqrstuvwxyz
              max_code_length: 8
              auto_select: false
            translator:
              dictionary: $SCHEMA
              prism: pinyin
              user_dict: $SCHEMA
              user_dict_mode: auto
              enable_user_dict: true
              enable_completion: false
              enable_sentence: false
              enable_encoder: false
              enable_contextual_suggestions: false
            editor:
              bindings:
                Control+F10: manual_up
                Control+F11: manual_down
                Shift+Delete: manual_delete
                Control+Delete: auto_delete
                Lock+Control+Delete: manual_delete
                Control+Shift+F12: manual_delete
                Shift+Control+F12: noop
            build:
              dictionaries:
                $SCHEMA:
                  algebra:
                    pinyin: []
        """.trimIndent() + "\n")
        File(shared, "$SCHEMA.dict.yaml").writeText("""
            ---
            name: $SCHEMA
            version: '1.0'
            sort: original
            use_preset_vocabulary: false
            ...
        """.trimIndent() + "\n" + WORDS.mapIndexed { index, word ->
            "$word\ta\t${(WORDS.size - index) * 1000}"
        }.joinToString("\n", postfix = "\n") + "$WITNESS\tb\t1000\n")
    }

    private companion object {
        const val TAG = "EvoOrderingDeviceTest"
        const val PROVIDER = "org.rime.plugin.sandyyur.librime_evo"
        const val SCHEMA = "evo_ordering_test"
        const val WITNESS = "壬"
        const val PAGE_SIZE = 3
        const val PAGE = 2
        const val SCHEMA_ACTION = 3
        const val SELECT_GLOBAL = 11
        const val READ = 13
        const val LOCAL = 14
        const val GLOBAL = 15
        val WORDS = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛")
    }
}
