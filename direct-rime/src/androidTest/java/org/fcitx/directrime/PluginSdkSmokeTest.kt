package org.fcitx.directrime

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercise the packaged Lua runtime, bindings, OpenCC data and persisted LevelDB together. */
class PluginSdkSmokeTest {
    @Test fun luaMemoryAndOpenccSurviveEngineRestart(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = mapOf(
            "default.yaml" to "config_version: '1.0'\nschema_list:\n  - schema: sdk_smoke\n",
            "sdk_smoke.schema.yaml" to """
                schema:
                  schema_id: sdk_smoke
                  name: SDK smoke
                  version: '1.0'
                engine:
                  processors: [speller, selector, navigator, express_editor]
                  segmentors: [abc_segmentor, fallback_segmentor]
                  translators: [table_translator, lua_translator@*sdk_smoke]
                speller:
                  alphabet: abcdefghijklmnopqrstuvwxyz
                translator:
                  dictionary: sdk_smoke
                  enable_user_dict: true
                  enable_sentence: false
            """.trimIndent(),
            "sdk_smoke.dict.yaml" to "---\nname: sdk_smoke\nversion: '1.0'\nsort: by_weight\n...\n你\tni\t1000\n",
            "lua/sdk_smoke.lua" to """
                local M = {}
                function M.init(env)
                  env.memory = Memory(env.engine, env.engine.schema)
                  env.opencc = Opencc("s2t.json")
                end
                function M.func(input, seg, env)
                  if input == "write" then
                    local entry = DictEntry()
                    entry.text = env.opencc:convert("汉语")
                    entry.custom_code = "sdk "
                    assert(env.memory:update_userdict(entry, 1, ""))
                    yield(Candidate("sdk_smoke", seg.start, seg._end, entry.text, _VERSION))
                  elseif input == "read" then
                    env.memory:user_lookup("sdk", true)
                    for entry in env.memory:iter_user() do
                      for i = 1, 32 do
                        yield(Candidate("sdk_smoke", seg.start, seg._end, entry.text, _VERSION))
                      end
                    end
                  end
                end
                return M
            """.trimIndent()
        )
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                files.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        try {
            assertTrue(RimeRuntime.deploy(context, profile = { ByteArrayInputStream(bytes) }))
            var generation = RimeRuntime.newGeneration()
            var state = RimeRuntime.begin(context, generation, "sdk.smoke")!!
            assertTrue(state.gateMessage, state.ready)
            "write".forEach { state = RimeRuntime.key(generation, it.code)!! }
            assertEquals("漢語", state.candidates.firstOrNull())
            assertEquals("Lua 5.4", state.comments.firstOrNull())
            assertEquals("漢語", RimeRuntime.action(state, 1, 0)!!.commit)
            // An interrupted snapshot restore must not become a user dictionary
            // on the next sync. Use this APK's sandbox and the real packaged SDK.
            val residue = File(context.filesDir, "rime/user/.temp.userdb")
            assertTrue(residue.isDirectory || residue.mkdirs())
            val marker = File(residue, "keep-for-test").apply { writeText("scratch residue") }
            assertTrue("A real learned dictionary must sync with .temp.userdb present", RimeRuntime.synchronize(context))
            assertTrue("Enumeration must not delete leftover scratch content", marker.isFile)
            val snapshots = File(context.filesDir, "rime/user/sync").walkTopDown().filter { it.isFile }.toList()
            assertTrue("The real user dictionary must be backed up", snapshots.any { it.name == "sdk_smoke.userdb.txt" })
            assertFalse(snapshots.any { it.name == ".temp.userdb.txt" })
            generation = RimeRuntime.newGeneration()
            state = RimeRuntime.begin(context, generation, "sdk.smoke")!!
            assertTrue(state.gateMessage, state.ready)
            "read".forEach { state = RimeRuntime.key(generation, it.code)!! }
            assertEquals("漢語", state.candidates.firstOrNull())
            // Close the VM with a live Lua translation, then reopen the same user database.
            assertTrue(RimeRuntime.deploy(context))
            generation = RimeRuntime.newGeneration()
            state = RimeRuntime.begin(context, generation, "sdk.smoke")!!
            assertTrue(state.gateMessage, state.ready)
            "read".forEach { state = RimeRuntime.key(generation, it.code)!! }
            assertEquals("漢語", state.candidates.firstOrNull())
            assertEquals("Lua 5.4", state.comments.firstOrNull())
            assertEquals("漢語", RimeRuntime.action(state, 1, 0)!!.commit)
            RimeRuntime.end(generation)
        } finally {
            assertTrue(RimeRuntime.deploy(context, smoke = true))
        }
    }
}
