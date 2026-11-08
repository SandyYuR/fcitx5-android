/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.RadioButton
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.ui.main.settings.behavior.manager.SubModeManager
import org.fcitx.fcitx5.android.ui.main.settings.im.RimeImportActivity
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestName
import java.io.File
import java.io.FileInputStream

/** Disposable emulator only: original UI on the direct backend, not the small lab service. */
class DirectRimeFrontendTest {
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val connection by lazy { FcitxDaemon.connect(javaClass.name) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commits = Channel<String>(Channel.UNLIMITED)
    private var previousProfile = "rime"
    private val createdImports = mutableListOf<String>()
    private val fixtureArchive = InstrumentationRegistry.getArguments().getString("fixtureArchive")
    private val profile = "frontend-test-${System.currentTimeMillis()}"
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { it.readText().trim() }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val wait = InstrumentationRegistry.getArguments().getString("waitMs")?.toLongOrNull() ?: 30_000L
        val deadline = SystemClock.uptimeMillis() + wait
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
    @Before fun prepare(): Unit = runBlocking {
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        connection.runOnReady {
            previousProfile = getAddonSubConfig("rime", "profile-manager")["Current"].value
            setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "create"), RawConfig("Name", profile))))
        }
        val directory = File(context.getExternalFilesDir(null), "data/$profile")
        if (fixtureArchive != null) {
            val archive = File(context.filesDir, fixtureArchive)
            require(archive.canonicalFile.parentFile == context.filesDir.canonicalFile)
            check(directory.delete())
            archive.inputStream().use { org.fcitx.directrime.ProfileArchive.extract(it, directory) }
        } else {
            instrumentation.context.assets.list("smoke")!!.forEach { name ->
                instrumentation.context.assets.open("smoke/$name").use { input -> File(directory, name).outputStream().use { input.copyTo(it) } }
            }
            if (testName.methodName == "schemaCommentsAndSwitchGroupsMatchRime") writeCommentFixture(directory)
            if (testName.methodName == "schemaSwitchPreferencesSurviveNewSessionsAndRestart") writeSavedOptionsFixture(directory)
            if (testName.methodName == "preeditCursorUsesRawCoordinates") writeCursorFixture(directory)
            if (testName.methodName == "symbolKeysAndPopupsUseRimePunctuation") writePunctuationFixture(directory)
            if (testName.methodName in setOf("newSessionsKeepRimeSavedAscii", "modeMenuRemembersBothDirections",
                    "modeMenuWithoutSaveOptionsStaysSessionLocal")) {
                val schema = File(directory, "direct_smoke.schema.yaml")
                val text = schema.readText().replace("\r\n", "\n")
                val reset = "  - name: ascii_mode\n    reset: 0\n"
                check(text.contains(reset))
                schema.writeText(text.replace(reset, "  - name: ascii_mode\n"))
                val options = if (testName.methodName == "modeMenuWithoutSaveOptionsStaysSessionLocal") "[]" else "[ascii_mode]"
                File(directory, "default.yaml").appendText("\nswitcher:\n  save_options: $options\n")
            }
            if (testName.methodName == "newApplicationDoesNotInheritInlineAscii") {
                File(directory, "direct_smoke.schema.yaml").appendText("\nascii_composer:\n  inline_uppercase: true\n")
            }
            File(directory, "second.schema.yaml").writeText(File(directory, "direct_smoke.schema.yaml").readText()
                .replace("schema_id: direct_smoke", "schema_id: second").replace("name: 直连测试", "name: 第二方案"))
            File(directory, "default.yaml").appendText("\n")
            val default = File(directory, "default.yaml")
            default.writeText(default.readText().replace("- schema: direct_smoke", "- schema: direct_smoke\n  - schema: second"))
            if (testName.methodName == "newSessionsRespectRimeSchemaOrder") {
                default.appendText("\nswitcher:\n  fix_schema_list_order: true\n")
            }
            if (testName.methodName == "wanxiangAdvancedReadsPreserveDeployedFilesAndChineseInput") {
                File(directory, "wanxiang.schema.yaml").writeText(File(directory, "direct_smoke.schema.yaml").readText()
                    .replace("schema_id: direct_smoke", "schema_id: wanxiang"))
                default.writeText(default.readText().replace("schema: direct_smoke", "schema: wanxiang"))
            }
            if (testName.methodName == "redeployReloadsLuaAndSchema") writeReloadFixture(directory, "部署前", "before")
        }
        connection.runOnReady {
            setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "switch"), RawConfig("Name", profile))))
            setImConfig("rime", RawConfig(arrayOf(RawConfig("PreeditMode", "No"))))
            // API-only scenarios model a new ordinary editor. A preceding UI
            // test can finish in a password field, whose capabilities must not
            // leak into this new test session and bypass the replayed keys.
            setCapFlags(CapabilityFlags.DefaultFlags)
            startInputSession(invalidateInputSession(), "frontend.test")
        }
        val flow = connection.runImmediately { eventFlow }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flow.collect { if (it is FcitxEvent.CommitStringEvent && FcitxDaemon.isCurrentInputSession(it.inputSession)) commits.send(it.data.text) }
        }
    }
    @After fun cleanup(): Unit = runBlocking {
        scope.cancel()
        connection.runOnReady {
            setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "switch"), RawConfig("Name", previousProfile))))
            setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "delete"), RawConfig("Name", profile))))
            createdImports.forEach { setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "delete"), RawConfig("Name", it)))) }
        }
        FcitxDaemon.disconnect(javaClass.name)
    }
    private fun writeCursorFixture(directory: File) {
        val schema = File(directory, "direct_smoke.schema.yaml")
        val text = schema.readText().replace("\r\n", "\n")
        check(text.contains("translator:\n"))
        schema.writeText(text.replace("translator:\n", "translator:\n  preedit_format:\n    - 'xform/^(.)(.*)$/${'$'}1 ${'$'}2/'\n"))
        File(directory, "direct_smoke.dict.yaml").appendText("\n奶\tnai\t200\n")
    }

    @Test fun preeditCursorUsesRawCoordinates(): Unit = runBlocking {
        connection.runOnReady {
            setImConfig("rime", RawConfig(arrayOf(RawConfig("PreeditMode", "ComposingText"))))
            setCandidatePagingMode(1)
            sendKey('n'); sendKey('i')
            assertEquals("n i", clientPreeditCached.toString())
            val candidatesBeforeRefresh = candidateActionContext
            setCandidatePagingMode(1)
            assertEquals(candidatesBeforeRefresh, candidateActionContext)
            assertTrue(moveCursorIfCurrent(2, inputSession, "n i"))
            sendKey('a')
            sendKey(0xff57) // End: include the original suffix when confirming the edited code.
            assertEquals("奶", getCandidates(0, 1).single().text)
            assertTrue(selectGlobal(0))
        }
        assertEquals("奶", withTimeout(5_000) { commits.receive() })
    }

    private fun writeCommentFixture(directory: File) {
        // Wanxiang uses a three-way comment group and Lua to rewrite/clear comments.
        // Reproduce that contract with a tiny dictionary, without bundling its data.
        val schema = File(directory, "direct_smoke.schema.yaml")
        val text = schema.readText().replace("\r\n", "\n")
        check(text.contains("engine:\n"))
        schema.writeText(text.replace("engine:\n", """
  - name: emoji
    reset: 0
    states: [表情关, 表情开]
  - options: [raw_input, tone_display, full_pinyin]
    reset: 0
    states: [原编码, 有声调, 无声调]
  - options: [comment_off, tone_hint, toneless_hint]
    reset: 0
    states: [注释关, 有声调, 无声调]
  - name: internal_hint
engine:
""".trimStart('\n')).replace("  translators: [table_translator]",
            "  translators: [table_translator]\n  filters: [lua_filter@*frontend_comment_test]"))
        File(directory, "lua").mkdirs()
        File(directory, "lua/frontend_comment_test.lua").writeText("""
return function(input, env)
  local context = env.engine.context
  for candidate in input:iter() do
    local comment = ""
    if context:get_option("tone_hint") then comment = "nǐ"
    elseif context:get_option("toneless_hint") then comment = "ni" end
    yield(ShadowCandidate(candidate, candidate.type, candidate.text, comment))
  end
end
""".trimIndent())
    }
    private fun writeSavedOptionsFixture(directory: File) {
        File(directory, "default.yaml").appendText("\nswitcher:\n  save_options: [remembered, radio_a, radio_b, reset_preference]\n")
        val schema = File(directory, "direct_smoke.schema.yaml")
        schema.writeText(schema.readText().replace("\r\n", "\n").replace("engine:\n", """
  - name: remembered
    states: [关闭, 开启]
  - options: [radio_a, radio_b]
    states: [甲, 乙]
  - name: reset_preference
    reset: 1
    states: [关闭, 开启]
  - name: local_only
    states: [关闭, 开启]
engine:
""".trimStart('\n')))
    }

    @Test fun schemaSwitchPreferencesSurviveNewSessionsAndRestart(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Uses the disposable smoke fixture", fixtureArchive == null)
        suspend fun toggle(name: String) = connection.runOnReady {
            activateAction(statusArea().first { it.name == name }.id)
        }
        suspend fun selectRadio(name: String) = connection.runOnReady {
            activateAction(statusArea().first { it.name == "select-radio_a" }.menu!!.first { it.name == name }.id)
        }
        suspend fun expect(remembered: Boolean, radio: String, reset: Boolean = true, local: Boolean = false) = connection.runOnReady {
            val actions = statusArea()
            for ((name, enabled) in listOf("remembered" to remembered, "reset_preference" to reset, "local_only" to local)) {
                assertEquals(name, if (enabled) "开启 → 关闭" else "关闭 → 开启", actions.first { it.name == name }.shortText)
            }
            assertEquals(listOf(radio), actions.first { it.name == "select-radio_a" }.menu!!.filter { it.isChecked }.map { it.name })
        }
        suspend fun newSession(program: String) = connection.runOnReady {
            startInputSession(invalidateInputSession(), program)
        }

        toggle("remembered")
        selectRadio("radio_b")
        toggle("reset_preference")
        toggle("local_only")
        expect(remembered = true, radio = "radio_b", reset = false, local = true)
        // A different client forces a real librime session, not the ordinary same-app reuse path.
        newSession("frontend.options.second")
        expect(remembered = true, radio = "radio_b")
        // Deployment finalizes and initializes librime, exercising persistence beyond its config cache.
        connection.runOnReady { activateAction(11) }
        newSession("frontend.options.restarted")
        expect(remembered = true, radio = "radio_b")

        toggle("remembered")
        selectRadio("radio_a")
        newSession("frontend.options.disabled")
        expect(remembered = false, radio = "radio_a")

        val default = File(context.getExternalFilesDir(null), "data/$profile/default.yaml")
        default.writeText(default.readText().replace("[remembered, radio_a", "[local_only, radio_a"))
        connection.runOnReady { activateAction(11) }
        toggle("local_only")
        toggle("remembered")
        newSession("frontend.options.new-whitelist")
        expect(remembered = false, radio = "radio_a", local = true)
    }

    private suspend fun FcitxAPI.expectRimeState(schema: String, ascii: Boolean) {
        val actions = statusArea()
        val menu = actions.first { it.name == "rime" }.menu!!
        val selected = SubModeManager.resolveSelectedSchemaAction(actions)
        assertEquals("Actual Rime schema", schema, menu.first { it.id == selected }.name)
        assertEquals("Actual Rime ASCII option", ascii, menu.first { it.name == "ascii_mode" }.isChecked)
    }

    private suspend fun FcitxAPI.selectTestSchema(schema: String) {
        val selected = statusArea().first { it.name == "rime" }.menu!!.first { it.name == schema }
        assertTrue(activateSchemaActionIfCurrent(selected.id, inputSession))
    }

    @Test fun newSessionsRespectRimeAsciiReset(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Uses the disposable smoke fixture", fixtureArchive == null)
        connection.runOnReady {
            startInputSession(invalidateInputSession(), "frontend.state.same")
            expectRimeState("direct_smoke", ascii = false)
            toggleIme()
            expectRimeState("direct_smoke", ascii = true)

            // Ordinary editors in the same app reuse the native session and its mode.
            startInputSession(invalidateInputSession(), "frontend.state.same")
            expectRimeState("direct_smoke", ascii = true)
            // Another app creates a session, so the schema's reset: 0 applies again.
            startInputSession(invalidateInputSession(), "frontend.state.other")
            expectRimeState("direct_smoke", ascii = false)

            toggleIme()
            setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive))
            startInputSession(invalidateInputSession(), "frontend.state.other")
            expectRimeState("direct_smoke", ascii = false)
            setCapFlags(CapabilityFlags.DefaultFlags)

            toggleIme()
            activateAction(11)
            expectRimeState("direct_smoke", ascii = false)
            toggleIme()
            activateAction(12)
            expectRimeState("direct_smoke", ascii = false)
        }
    }

    @Test fun newSessionsKeepRimeSavedAscii(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Uses the disposable smoke fixture", fixtureArchive == null)
        connection.runOnReady {
            expectRimeState("direct_smoke", ascii = false)
            // This fixture declares save_options without an ascii_mode reset.
            activateAction(statusArea().first { it.name == "ascii_mode" }.id)
            expectRimeState("direct_smoke", ascii = true)
            startInputSession(invalidateInputSession(), "frontend.saved-ascii.other")
            expectRimeState("direct_smoke", ascii = true)
            activateAction(11)
            expectRimeState("direct_smoke", ascii = true)
            startInputSession(invalidateInputSession(), "frontend.saved-ascii.restarted")
            expectRimeState("direct_smoke", ascii = true)
        }
    }

    @Test fun modeMenuRemembersBothDirections(): Unit = runBlocking {
        connection.runOnReady {
            suspend fun mode() = statusArea().first { it.name == "rime" }.menu!!.first { it.name == "ascii_mode" }
            val english = context.getString(R.string.direct_rime_ascii_mode)
            val chinese = context.getString(R.string.direct_rime_chinese_mode)
            expectRimeState("direct_smoke", false)
            assertEquals(english, mode().shortText)
            activateAction(mode().id)
            expectRimeState("direct_smoke", true)
            assertEquals(english, inputPanelCached.auxUp.toString())
            assertEquals(chinese, mode().shortText)
            assertEquals(english, statusArea().first { it.name == "rime" }.shortText)

            startInputSession(invalidateInputSession(), "frontend.mode-menu.english")
            expectRimeState("direct_smoke", true)
            assertEquals(chinese, mode().shortText)
            activateAction(mode().id)
            expectRimeState("direct_smoke", false)
            assertEquals(chinese, inputPanelCached.auxUp.toString())
            assertEquals(english, mode().shortText)

            startInputSession(invalidateInputSession(), "frontend.mode-menu.chinese")
            expectRimeState("direct_smoke", false)
            val config = File(context.getExternalFilesDir(null), "data/$profile/user.yaml").readText()
            assertTrue("Menu must persist the disabled value", Regex("(?m)^\\s+ascii_mode: false\\s*$").containsMatchIn(config))
            activateAction(11) // Rebuild the engine and restore the saved disabled value.
            expectRimeState("direct_smoke", false)
            assertEquals(english, mode().shortText)
        }
    }

    @Test fun modeMenuWithoutSaveOptionsStaysSessionLocal(): Unit = runBlocking {
        connection.runOnReady {
            val mode = statusArea().first { it.name == "rime" }.menu!!.first { it.name == "ascii_mode" }
            activateAction(mode.id)
            expectRimeState("direct_smoke", true)
            startInputSession(invalidateInputSession(), "frontend.mode-menu.local")
            expectRimeState("direct_smoke", false)
            val file = File(context.getExternalFilesDir(null), "data/$profile/user.yaml")
            val config = if (file.exists()) file.readText() else ""
            assertFalse("An unlisted option must not be saved", Regex("(?m)^\\s+ascii_mode:").containsMatchIn(config))
        }
    }

    @Test fun newSessionsRespectRimeSchemaOrder(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Uses the disposable smoke fixture", fixtureArchive == null)
        connection.runOnReady {
            selectTestSchema("second")
            expectRimeState("second", ascii = false)
            startInputSession(invalidateInputSession(), "frontend.test")
            expectRimeState("second", ascii = false)

            // fix_schema_list_order chooses the first schema for a fresh native session.
            startInputSession(invalidateInputSession(), "frontend.schema.other")
            expectRimeState("direct_smoke", ascii = false)
            selectTestSchema("second")
            activateAction(11)
            expectRimeState("direct_smoke", ascii = false)
            selectTestSchema("second")
            activateAction(12)
            expectRimeState("direct_smoke", ascii = false)
        }
    }

    @Test fun newSessionsKeepRimeRecentSchema(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Uses the disposable smoke fixture", fixtureArchive == null)
        connection.runOnReady {
            selectTestSchema("second")
            expectRimeState("second", ascii = false)
            // Without fixed ordering, librime restores the recently selected schema itself.
            startInputSession(invalidateInputSession(), "frontend.recent-schema.other")
            expectRimeState("second", ascii = false)
            activateAction(11)
            expectRimeState("second", ascii = false)
            startInputSession(invalidateInputSession(), "frontend.recent-schema.restarted")
            expectRimeState("second", ascii = false)
        }
    }

    @Test fun newApplicationDoesNotInheritInlineAscii(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Uses the disposable smoke fixture", fixtureArchive == null)
        connection.runOnReady {
            sendKey('N')
            expectRimeState("direct_smoke", ascii = true)
            assertEquals("N", inputPanelCached.preedit.toString())
            startInputSession(invalidateInputSession(), "frontend.inline-ascii.other")
            expectRimeState("direct_smoke", ascii = false)
            assertTrue(inputPanelCached.preedit.isEmpty())
        }
    }

    private fun writeReloadFixture(directory: File, label: String, option: String) {
        val original = instrumentation.context.assets.open("smoke/direct_smoke.schema.yaml")
            .bufferedReader().use { it.readText() }
        val schema = File(directory, "direct_smoke.schema.yaml")
        val previousTime = schema.lastModified()
        schema.writeText(original
            .replace("name: 直连测试", "name: $label")
            .replace("engine:\n", "  - name: reload_$option\n    states: [关闭, 开启]\nengine:\n")
            .replace("  translators: [table_translator]", "  translators: [table_translator]\n  filters: [lua_filter@*redeploy_probe]"))
        if (previousTime > 0) check(schema.setLastModified(previousTime)) // Force redeploy even with preserved mtimes.
        File(directory, "lua").mkdirs()
        File(directory, "lua/redeploy_probe.lua").writeText("""
local label = "$label"
local software = rime_api.get_distribution_code_name() .. " " .. rime_api.get_distribution_version()
return function(input, env)
  for candidate in input:iter() do
    local result = ShadowCandidate(candidate, candidate.type, candidate.text, label .. ":" .. software)
    yield(result)
  end
end
""".trimIndent())
    }
    @Test fun redeployReloadsLuaAndSchema(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Uses the disposable smoke fixture", fixtureArchive == null)
        val directory = File(context.getExternalFilesDir(null), "data/$profile")
        suspend fun expectLoaded(label: String, option: String) {
            connection.runOnReady {
                assertTrue(acceptsInput)
                assertEquals(label, currentIme().subMode.label)
                assertEquals(listOf("reload_$option"), statusArea().filter { it.name.startsWith("reload_") }.map { it.name })
                sendKey('n', KeyState.Virtual.state); sendKey('i', KeyState.Virtual.state)
                val words = getCandidates(0, 10)
                assertEquals(listOf("你", "泥", "拟", "逆"), words.map { it.text })
                assertTrue("Lua must see the new source and the APK's Chinese distribution/version",
                    words.all { it.comment == "$label:中州鹅 ${BuildConfig.VERSION_NAME}" })
                assertTrue(selectGlobal(0))
            }
            assertEquals("你", withTimeout(10_000) { commits.receive() })
        }
        expectLoaded("部署前", "before") // Populate Lua require()'s module cache.
        for ((label, option, settingsEntry) in listOf(Triple("部署后", "after", true), Triple("再次部署", "again", false))) {
            writeReloadFixture(directory, label, option)
            File(directory, "third.schema.yaml").writeText(File(directory, "second.schema.yaml").readText()
                .replace("schema_id: second", "schema_id: third").replace("name: 第二方案", "name: 新增方案"))
            val default = File(directory, "default.yaml")
            val previousTime = default.lastModified()
            default.writeText(default.readText().replace("- schema: second", "- schema: third"))
            check(default.setLastModified(previousTime))
            connection.runOnReady {
                val previousGeneration = inputSession
                if (settingsEntry) setAddonSubConfig("rime", "deploy", RawConfig())
                else activateAction(11) // The expanded status area's deployment action.
                assertNotEquals(previousGeneration, inputSession)
                assertEquals(listOf(label, "新增方案"), SubModeManager.resolveSchemaMenuEntries(statusArea()).map { it.first })
            }
            expectLoaded(label, option)
        }
        assertNull("Deployment must not replay an old commit", withTimeoutOrNull(250) { commits.receive() })
    }
    /** Reproducible adapter measurements; deliberately not a UI latency benchmark. */
    @Test fun replayPerformanceWorkload(): Unit = runBlocking {
        val eventCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.runImmediately { eventFlow }.collect {
                eventCounts.getOrPut(it.javaClass.simpleName) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            }
        }
        val durations = mutableListOf<Long>()
        val delivered = mutableListOf<String>()
        val inputs = if (fixtureArchive == null) listOf("ni") else listOf("nihao", "zhongguo", "shurufa", "wode", "xingneng", "zhongzhou", "fangan", "zhongwen")
        suspend fun word(input: String, measure: Boolean) {
            for (key in input.map { it.toString() } + "space") {
                val start = System.nanoTime()
                connection.runOnReady { sendKey(key, KeyState.Virtual.state) }
                if (measure) durations += System.nanoTime() - start
            }
            val text = withTimeout(30_000) { commits.receive() }
            if (fixtureArchive == null) assertEquals("Every replayed word must commit exactly once", "你", text)
            else assertTrue("Wanxiang must produce a Chinese commit", text.any { it.code in 0x3400..0x9fff })
            if (measure) delivered += text
        }
        try {
            repeat(20) { word(inputs[it % inputs.size], false) }
            val modelMapped = fixtureArchive != null && File("/proc/self/maps").readText().contains("wanxiang-lts-zh-hans.gram")
            delay(150)
            eventCounts.clear()
            repeat(100) { word(inputs[it % inputs.size], true) }
            delay(150)
            assertNull("No duplicate commits after replay", withTimeoutOrNull(250) { commits.receive() })
            val sorted = durations.sorted()
            fun percentile(p: Int) = sorted[((sorted.size * p + 99) / 100 - 1).coerceAtLeast(0)] / 1_000_000.0
            val result = org.json.JSONObject().apply {
                put("metric", "sendKey completion including adapter and native owner; not input-to-display latency")
                put("harness_commit", BuildConfig.BUILD_GIT_HASH)
                put("fixture", fixtureArchive ?: "bundled-smoke")
                put("model_mapped", modelMapped)
                put("commits", org.json.JSONArray(delivered))
                put("words", 100)
                put("keys", sorted.size)
                put("p50_ms", percentile(50)); put("p95_ms", percentile(95)); put("p99_ms", percentile(99))
                put("events", org.json.JSONObject(eventCounts.mapValues { it.value.get() }))
            }
            File(context.filesDir, "direct-rime-replay.json").writeText(result.toString(2))
            android.util.Log.i("DirectRimeReplay", result.toString())
        } finally { collector.cancelAndJoin() }
    }
    @Test fun schemaCommentsAndSwitchGroupsMatchRime(): Unit = runBlocking {
        val candidates = Channel<Array<CandidateWord>>(Channel.UNLIMITED)
        val flow = connection.runImmediately { eventFlow }
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flow.collect { if (it is FcitxEvent.PagedCandidateEvent && FcitxDaemon.isCurrentInputSession(it.inputSession))
                candidates.send(it.data.candidates) }
        }
        suspend fun expectComment(comment: String) {
            withTimeout(10_000) {
                while (candidates.receive().firstOrNull { it.text == "你" }?.comment != comment) { }
            }
            assertEquals("Expanded candidates must show the same schema comment", comment,
                connection.runOnReady { getCandidates(0, 10) }.first { it.text == "你" }.comment)
        }
        try {
            connection.runOnReady {
                setCandidatePagingMode(1)
                val actions = statusArea()
                val menu = actions.first { it.name == "rime" }.menu!!
                assertFalse("Schema menu must not repeat option switches", menu.any { it.name in
                    listOf("emoji", "comment_off", "tone_hint", "toneless_hint", "raw_input") || it.name.startsWith("select-") })
                assertFalse("Switches without states stay hidden", actions.any { it.name == "internal_hint" })
                val groups = actions.filter { it.menu?.size == 3 }
                assertEquals("Each options group needs its own button", 2, groups.size)
                assertEquals("原编码", groups.first { it.name == "select-raw_input" }.shortText)
                assertEquals("注释关", groups.first { it.name == "select-comment_off" }.shortText)
                val toggle = actions.first { it.name == "emoji" }
                assertEquals("表情关 → 表情开", toggle.shortText)
                activateAction(toggle.id)
                assertEquals("表情开 → 表情关", statusArea().first { it.name == "emoji" }.shortText)
                sendKey('n', KeyState.Virtual.state); sendKey('i', KeyState.Virtual.state)
            }
            expectComment("")
            for ((option, label, comment) in listOf(Triple("tone_hint", "有声调", "nǐ"),
                Triple("toneless_hint", "无声调", "ni"), Triple("comment_off", "注释关", ""))) {
                connection.runOnReady {
                    val group = statusArea().first { it.name == "select-comment_off" }
                    activateAction(group.menu!!.first { it.name == option }.id)
                    val changed = statusArea().first { it.name == group.name }
                    assertEquals(label, changed.shortText)
                    assertEquals(listOf(option), changed.menu!!.filter { it.isChecked }.map { it.name })
                    assertEquals("原编码", statusArea().first { it.name == "select-raw_input" }.shortText)
                }
                expectComment(comment)
            }
        } finally { collector.cancelAndJoin(); candidates.close() }
    }
    @Test fun wanxiangAdvancedReadsPreserveDeployedFilesAndChineseInput(): Unit = runBlocking {
        val directory = File(context.getExternalFilesDir(null), "data/$profile")
        val paths = listOf("default.yaml", "wanxiang.schema.yaml", "build/default.yaml", "build/wanxiang.schema.yaml")
        val original = paths.associateWith { path -> File(directory, path).let { it.readText() to it.lastModified() } }
        connection.runOnReady {
            val generation = inputSession
            repeat(3) {
                assertNotNull(getAddonSubConfig("rime", "wanxiang-settings/default.yaml")["Snapshot"])
                assertNotNull(getAddonSubConfig("rime", "wanxiang-settings/wanxiang.schema.yaml")["Snapshot"])
            }
            assertEquals(generation, inputSession)
            assertEquals(original, paths.associateWith { path -> File(directory, path).let { it.readText() to it.lastModified() } })
            // Reproduce the previous reader's damage, then exercise the real repair path.
            assertTrue(File(directory, "build/default.yaml").delete())
            setAddonSubConfig("rime", "wanxiang-settings-repair", RawConfig(arrayOf(
                RawConfig("Profile", profile), RawConfig("Path", directory.canonicalPath))))
            assertTrue(File(directory, "build/default.yaml").isFile)
            sendKey('n', KeyState.Virtual.state); sendKey('i', KeyState.Virtual.state)
            assertEquals("ni", inputPanelCached.preedit.toString())
            assertEquals("你", getCandidates(0, 1).single().text)
            assertTrue(select(0))
        }
        assertEquals("你", withTimeout(10_000) { commits.receive() })
    }

    @Test fun candidateSchemaAndEditorContracts(): Unit = runBlocking {
        connection.runOnReady {
            assertTrue(ConfigDescriptor.parseTopLevel(getImConfig("rime")["desc"]).isRight())
            sendKey('n', KeyState.Virtual.state); sendKey('i', KeyState.Virtual.state)
            assertEquals("ni", inputPanelCached.preedit.toString())
            assertTrue(clientPreeditCached.isEmpty())
            assertEquals(listOf("你", "泥", "拟", "逆"), getCandidates(0, 10).map { it.text })
            offsetCandidatePage(1)
            assertTrue(select(0))
        }
        assertEquals("逆", withTimeout(10_000) { commits.receive() })
        connection.runOnReady {
            setCandidatePagingMode(1)
            sendKey('n', KeyState.Virtual.state); sendKey('i', KeyState.Virtual.state)
            assertTrue(selectGlobal(1))
        }
        assertEquals("泥", withTimeout(10_000) { commits.receive() })
        connection.runOnReady {
            setCandidatePagingMode(1)
            val menu = SubModeManager.resolveSchemaMenuEntries(statusArea())
            assertEquals(listOf("直连测试", "第二方案"), menu.map { it.first })
            toggleIme()
            activateAction(menu[1].second)
            assertEquals("fcitx-rime:second", currentIme().subMode.icon)
            assertEquals("第二方案", currentIme().subMode.label)
            activateIme("keyboard-us") // A stale system subtype during first bind.
            activateAction(menu[0].second)
            setImConfig("rime", RawConfig(arrayOf(RawConfig("PreeditMode", "ComposingText"))))
            sendKey('n', KeyState.Virtual.state); sendKey('i', KeyState.Virtual.state)
            assertEquals("ni", clientPreeditCached.toString())
            val expired = inputSession
            startInputSession(invalidateInputSession(), "editor.two")
            assertNotEquals(expired, inputSession)
            assertTrue(clientPreeditCached.isEmpty())
            sendKey('h', KeyState.Virtual.state); sendKey('a', KeyState.Virtual.state); sendKey('o', KeyState.Virtual.state)
            select(0)
        }
        assertEquals("好", withTimeout(10_000) { commits.receive() })
        assertNull("A UI refresh must not repeat a commit", withTimeoutOrNull(250) { commits.receive() })
    }
    @Test fun bindingReplayPreservesForwardedHardwareKeyPairs(): Unit = runBlocking {
        val forwarded = Channel<FcitxEvent.KeyEvent.Data>(Channel.UNLIMITED)
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.runImmediately { eventFlow }.collect {
                if (it is FcitxEvent.KeyEvent) forwarded.send(it.data)
            }
        }
        try {
            connection.runOnReady {
                // F12 is unbound in the fixture, so Android receives both halves.
                sendKey("F12", code = 88, timestamp = 1)
                startInputSession(inputSession, "frontend.test")
                sendKey("F12", code = 88, up = true, timestamp = 2)
            }
            val down = withTimeout(10_000) { forwarded.receive() }
            val up = withTimeout(10_000) { forwarded.receive() }
            assertFalse(down.up)
            assertTrue(up.up)
            assertEquals(down.sym, up.sym)
        } finally { collector.cancelAndJoin(); forwarded.close() }
    }
    @Test fun silentNonEditorCanBePromotedWithoutDroppingItsFirstKey(): Unit = runBlocking {
        val generation = FcitxDaemon.invalidateInputSession()
        FcitxDaemon.prepareInputSession(generation, "frontend.null", CapabilityFlags.DefaultFlags, false)
        connection.runOnReady {
            startInputSession(generation, "frontend.null")
            // Repeated focus/show callbacks for a known non-editor must remain
            // inactive and must not replace its generation behind queued keys.
            focus(true)
            focus(true)
            assertEquals(generation, inputSession)
            assertFalse(acceptsInput)
            assertTrue(inputPanelCached.preedit.isEmpty())
        }
        assertNull(FcitxDaemon.captureInputKey())
        assertTrue(FcitxDaemon.requireInputSession())
        val first = FcitxDaemon.captureInputKey()!!
        // Engine readiness may re-apply the original TYPE_NULL descriptor after
        // the first key was accepted. It must preserve that pending key demand.
        FcitxDaemon.prepareInputSession(generation, "frontend.null.updated", CapabilityFlags.DefaultFlags, false)
        connection.runOnReady {
            startInputSession(generation, "frontend.null")
            assertEquals("Promoting a non-editor must not change its generation", generation, inputSession)
            assertTrue(FcitxDaemon.canDeliverInputKey(first))
            sendKey('n', KeyState.Virtual.state)
            sendKey('i', KeyState.Virtual.state)
            assertTrue(select(0))
        }
        FcitxDaemon.releaseInputKey(first)
        assertEquals("你", withTimeout(10_000) { commits.receive() })
    }
    /** Only invoked by the isolated diagnostic workflow with the checksum-pinned full fixture. */
    @Test fun diagnoseColdCandidateCore(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(fixtureArchive != null &&
            InstrumentationRegistry.getArguments().getString("probeCold") == "true")
        val records = org.json.JSONArray()
        repeat(6) { round ->
            val record = org.json.JSONObject().put("round", round)
            connection.runOnReady {
                val before = System.nanoTime()
                startInputSession(invalidateInputSession(), "diagnostic.client.$round")
                record.put("bind_ms", (System.nanoTime() - before) / 1e6)
                for ((index, key) in "nihao".withIndex()) {
                    val start = System.nanoTime()
                    sendKey(key, KeyState.Virtual.state)
                    record.put("key_${index + 1}_ms", (System.nanoTime() - start) / 1e6)
                }
                val start = System.nanoTime()
                assertTrue(select(0))
                record.put("select_ms", (System.nanoTime() - start) / 1e6)
            }
            val commit = withTimeout(30_000) { commits.receive() }
            assertTrue(commit.any { it.code in 0x3400..0x9fff })
            record.put("commit_chars", commit.length)
            records.put(record)
        }
        assertNull("No duplicate commits", withTimeoutOrNull(250) { commits.receive() })
        assertTrue("The LTS model must be mapped", File("/proc/self/maps").readText().contains("wanxiang-lts-zh-hans.gram"))
        val report = org.json.JSONObject()
            .put("source", BuildConfig.BUILD_GIT_HASH).put("records", records)
            .put("metric", "Current Debug backend operation completion; not phone/UI latency or a before/after comparison")
        File(context.filesDir, "direct-rime-cold-probe.json").writeText(report.toString(2))
        // AGP may uninstall the target after connected tests. Preserve the report in
        // logcat before that teardown; do not depend on run-as after the test runner exits.
        android.util.Log.i("DirectRimeColdProbe", report.toString())
    }
    @Test fun modeAndSchemaHintsExpireWithoutReplayingCommits(): Unit = runBlocking {
        val catalogueStarted = SystemClock.uptimeMillis()
        val control = Key.parse("Control_L")
        val catalogueMs = SystemClock.uptimeMillis() - catalogueStarted
        connection.runOnReady {
            assertTrue("New sessions must not show a switch hint", inputPanelCached.auxUp.isEmpty())
            toggleIme()
            assertEquals(context.getString(R.string.direct_rime_ascii_mode), inputPanelCached.auxUp.toString())
            // Releasing a key must not immediately erase a mode hint.
            val generation = inputSession
            val releaseStarted = SystemClock.uptimeMillis()
            sendKey(control.sym, KeyState.Virtual.state, up = true)
            File(context.filesDir, "direct-rime-hint-release.json").writeText(org.json.JSONObject()
                .put("runtime_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                .put("catalogue_init_ms", catalogueMs).put("release_ms", SystemClock.uptimeMillis() - releaseStarted)
                .put("generation_before", generation).put("generation_after", inputSession)
                .put("hint_after_release", inputPanelCached.auxUp.toString()).toString(2))
            assertEquals("The release check must stay in the same editor", generation, inputSession)
            assertTrue("A release must not clear a hint before its deadline", inputPanelCached.auxUp.isNotEmpty())
            statusHintPresented(inputPanelCached.statusHintId, SystemClock.uptimeMillis()) // Model the UI's first visible frame.
        }
        delay(1800)
        connection.runOnReady {
            assertTrue("Mode hints should expire", inputPanelCached.auxUp.isEmpty())
            toggleIme()
            assertEquals(context.getString(R.string.direct_rime_chinese_mode), inputPanelCached.auxUp.toString())
            val second = statusArea().first { it.name == "rime" }.menu!!.first { it.name == "second" }
            activateAction(second.id)
            assertEquals("第二方案", inputPanelCached.auxUp.toString())
            sendKey('n', KeyState.Virtual.state)
            assertTrue("Typing must replace the hint with composition", inputPanelCached.auxUp.isEmpty())
            sendKey('i', KeyState.Virtual.state)
            select(0)
        }
        assertEquals("你", withTimeout(10_000) { commits.receive() })
        delay(1800)
        assertNull("Hint expiry must never replay a commit", withTimeoutOrNull(250) { commits.receive() })
        connection.runOnReady {
            toggleIme()
            startInputSession(invalidateInputSession(), "hint.new-editor")
            assertTrue("Hints must not leak across input sessions", inputPanelCached.auxUp.isEmpty())
        }
    }

    private fun writePunctuationFixture(directory: File) {
        val schema = File(directory, "direct_smoke.schema.yaml")
        val text = schema.readText().replace("\r\n", "\n")
        check(text.contains("[ascii_composer, speller,"))
        check(text.contains("[ascii_segmentor, abc_segmentor,"))
        check(text.contains("[table_translator]"))
        schema.writeText(text
            .replace("[ascii_composer, speller,", "[ascii_composer, punctuator, speller,")
            .replace("[ascii_segmentor, abc_segmentor,", "[ascii_segmentor, punct_segmentor, abc_segmentor,")
            .replace("[table_translator]", "[punct_translator, table_translator]") + """

            punctuator:
              half_shape:
                ".": { commit: "。" }
                ",": { commit: "，" }
            """.trimIndent() + "\n")
    }

    @Test fun symbolKeysAndPopupsUseRimePunctuation(): Unit = runBlocking {
        keyboardTouchScenario(false, punctuation = true)
    }

    @Test fun originalKeyboardCanTypeUsingTouch(): Unit = runBlocking { keyboardTouchScenario(false) }

    @Test fun immediateTypingAndCandidateTapAfterRebind(): Unit = runBlocking { keyboardTouchScenario(true) }

    @Test fun statusAreaModeMenuFollowsTouchSwitches(): Unit = runBlocking {
        val component = "${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        val previous = shell("settings get secure default_input_method")
        val enabled = component in shell("ime list -s")
        var activity: RimeImportActivity? = null
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        fun inputView(): InputView? {
            var result: InputView? = null
            instrumentation.runOnMainSync {
                result = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
                    .filterIsInstance<InputView>().firstOrNull { it.isShown && it.service.isInputViewShown }
            }
            return result
        }
        fun visibleText(text: String): android.widget.TextView? {
            var result: android.widget.TextView? = null
            instrumentation.runOnMainSync {
                result = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
                    .filterIsInstance<android.widget.TextView>().firstOrNull {
                        it.isShown && it.text.toString() == text && it.getGlobalVisibleRect(Rect())
                    }
            }
            return result
        }
        fun tap(view: View) {
            var bounds: Rect? = null
            var stable = 0
            await("The visible touch target must settle") {
                var next: Rect? = null
                instrumentation.runOnMainSync {
                    val visible = Rect()
                    if (view.isShown && view.getLocalVisibleRect(visible) && !visible.isEmpty) {
                        val origin = IntArray(2)
                        view.getLocationOnScreen(origin)
                        next = Rect(visible).apply { offset(origin[0], origin[1]) }
                    }
                }
                stable = if (next != null && next == bounds) stable + 1 else 0
                bounds = next
                stable >= 5
            }
            val target = bounds!!
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    target.exactCenterX(), target.exactCenterY(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
        }
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("ime enable $component"); shell("ime set $component")
            activity = instrumentation.startActivitySync(Intent(context, RimeImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RimeImportActivity
            val editor = activity.findViewById<EditText>(R.id.direct_rime_test_editor)
            instrumentation.runOnMainSync {
                editor.requestFocus()
                (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            await("The actual editor and keyboard must be attached") {
                val service = inputView()?.service
                var attached = false
                instrumentation.runOnMainSync {
                    attached = service?.currentInputEditorInfo?.fieldId == editor.id &&
                        service?.editorSession?.capture()?.isCurrent() == true
                }
                attached
            }
            for (ascii in listOf(false, true)) {
                connection.runOnReady { expectRimeState("direct_smoke", ascii) }
                val input = inputView()!!
                instrumentation.runOnMainSync { input.executeButtonAction("more") }
                val rootLabel = connection.runImmediately { statusAreaActionsCached.first { it.name == "rime" }.shortText }
                lateinit var grid: androidx.recyclerview.widget.RecyclerView
                var position = -1
                instrumentation.runOnMainSync {
                    grid = descendants(input).filterIsInstance<androidx.recyclerview.widget.RecyclerView>()
                        .first { it.adapter is org.fcitx.fcitx5.android.input.status.StatusAreaAdapter }
                    val adapter = grid.adapter as org.fcitx.fcitx5.android.input.status.StatusAreaAdapter
                    position = adapter.entries.indexOfFirst {
                        (it as? org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Fcitx)?.action?.name == "rime"
                    }
                    assertTrue("Status area must contain the Rime menu entry", position >= 0)
                    grid.scrollToPosition(position)
                }
                var rootButton: View? = null
                await("The current Rime menu entry must be visible") {
                    instrumentation.runOnMainSync {
                        val holder = grid.findViewHolderForAdapterPosition(position) as?
                            org.fcitx.fcitx5.android.input.status.StatusAreaAdapter.Holder
                        rootButton = holder?.itemView?.takeIf {
                            holder.ui.label.text.toString() == rootLabel && it.isShown && it.getLocalVisibleRect(Rect())
                        }
                    }
                    rootButton != null
                }
                // The encoding hint can have the same text as this status-area button.
                tap(rootButton!!)
                val target = context.getString(if (ascii) R.string.direct_rime_chinese_mode else R.string.direct_rime_ascii_mode)
                await("The actual popup must display the target mode") { visibleText(target) != null }
                shell("screencap -p /data/local/tmp/direct-rime-mode-menu-from-${if (ascii) "english" else "chinese"}.png")
                tap(visibleText(target)!!)
                await("Touching the popup must switch the actual Rime option") {
                    connection.runImmediately {
                        statusAreaActionsCached.first { it.name == "rime" }.menu!!.first { it.name == "ascii_mode" }.isChecked == !ascii
                    }
                }
                connection.runOnReady { assertEquals(target, inputPanelCached.auxUp.toString()) }
            }
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
            if (!enabled) shell("ime disable $component")
        }
    }

    @Test fun candidateActionMenuCannotOutliveItsEditor(): Unit = runBlocking {
        val component = "${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        val previous = shell("settings get secure default_input_method")
        val prefs = AppPrefs.getInstance()
        val oldFloating = prefs.candidates.mode.getValue()
        val oldLayout = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        var activity: RimeImportActivity? = null
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        fun inputView(): InputView? {
            fun find() = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
                .filterIsInstance<InputView>().firstOrNull { it.isShown }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return find()
            var result: InputView? = null
            instrumentation.runOnMainSync {
                result = find()
            }
            return result
        }
        fun menuVisible(): Boolean {
            var visible = false
            instrumentation.runOnMainSync {
                visible = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
                    .filterIsInstance<android.widget.TextView>().any { it.isShown && it.text.toString() == "忘记此词" }
            }
            return visible
        }
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            instrumentation.runOnMainSync {
                prefs.candidates.mode.setValue(FloatingCandidatesMode.Disabled)
                prefs.keyboard.textKeyboardLayoutProfile.setValue("default")
            }
            shell("ime enable $component"); shell("ime set $component")
            activity = instrumentation.startActivitySync(Intent(context, RimeImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RimeImportActivity
            val editor = activity!!.findViewById<EditText>(R.id.direct_rime_test_editor)
            instrumentation.runOnMainSync {
                editor.inputType = InputType.TYPE_CLASS_TEXT
                editor.setText("")
                editor.requestFocus()
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            var generation = Long.MIN_VALUE
            var stableSince = SystemClock.uptimeMillis()
            await("The candidate menu needs a settled visible editor") {
                val next = connection.runImmediately { inputSession }
                if (next != generation) { generation = next; stableSince = SystemClock.uptimeMillis() }
                inputView()?.service?.isInputViewShown == true && imm.isActive(editor) &&
                    SystemClock.uptimeMillis() - stableSince >= 750
            }
            connection.runOnReady { sendKey('n'); sendKey('i') }
            var anchor: org.fcitx.fcitx5.android.input.keyboard.CustomGestureView? = null
            await("Candidate view must bind the Chinese fixture") {
                instrumentation.runOnMainSync {
                    anchor = inputView()?.let { view -> descendants(view)
                        .filterIsInstance<org.fcitx.fcitx5.android.input.keyboard.CustomGestureView>()
                        .firstOrNull { root -> descendants(root).filterIsInstance<android.widget.TextView>()
                            .any { it.text.toString() == "你" } } }
                }
                anchor != null
            }
            instrumentation.runOnMainSync {
                val view = requireNotNull(inputView())
                val field = org.fcitx.fcitx5.android.input.BaseInputView::class.java.getDeclaredField("displayedCandidateContext")
                    .apply { isAccessible = true }
                assertTrue("Candidate view must handle events", view.handleEvents)
                assertTrue("The original view must own the service", view.service.inputView === view)
                assertNotNull("The editor session must still be active", view.service.editorSession.capture())
                assertEquals("Displayed candidates must keep their producer context",
                    connection.runImmediately { candidateActionContext }, field.get(view))
                view.showCandidateActionMenu(0, "你", requireNotNull(anchor))
            }
            await("The current candidate action menu must be usable") { menuVisible() }
            delay(800)
            assertTrue("The current candidate menu must stay open", menuVisible())
            instrumentation.runOnMainSync {
                assertTrue("Candidate actions must keep the editor window focused", activity!!.hasWindowFocus())
                assertTrue(editor.hasFocus())
                val menu = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
                    .filterIsInstance<android.widget.TextView>().first { it.isShown && it.text.toString() == "忘记此词" }
                val flags = (menu.rootView.layoutParams as WindowManager.LayoutParams).flags
                assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertEquals(0, flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
            assertEquals("Candidate actions must not rebind the editor", generation,
                connection.runImmediately { inputSession })
            shell("screencap -p /data/local/tmp/candidate-menu-current.png")
            instrumentation.runOnMainSync {
                editor.inputType = InputType.TYPE_CLASS_NUMBER
                imm.restartInput(editor)
            }
            await("Changing the editor must dismiss its old candidate actions") { !menuVisible() }
            shell("screencap -p /data/local/tmp/candidate-menu-invalidated.png")
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.runOnMainSync {
                prefs.candidates.mode.setValue(oldFloating)
                prefs.keyboard.textKeyboardLayoutProfile.setValue(oldLayout)
            }
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }

    @Test fun schemaMenuKeepsPasswordEditorFocused(): Unit = runBlocking {
        val component = "${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        val previous = shell("settings get secure default_input_method")
        val enabled = component in shell("ime list -s")
        val prefs = AppPrefs.getInstance()
        val oldFloating = prefs.candidates.mode.getValue()
        val oldLayout = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        var activity: RimeImportActivity? = null
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        fun inputView(): InputView? {
            var result: InputView? = null
            instrumentation.runOnMainSync {
                result = WindowInspector.getGlobalWindowViews().asSequence()
                    .flatMap(::descendants).filterIsInstance<InputView>().firstOrNull { it.isShown }
            }
            return result
        }
        fun menuChoice(): RadioButton? {
            var result: RadioButton? = null
            instrumentation.runOnMainSync {
                result = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
                    .filterIsInstance<RadioButton>().firstOrNull { it.isShown && it.text.toString() == "第二方案" }
            }
            return result
        }
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            instrumentation.runOnMainSync {
                prefs.candidates.mode.setValue(FloatingCandidatesMode.Disabled)
                prefs.keyboard.textKeyboardLayoutProfile.setValue("default")
            }
            shell("ime enable $component"); shell("ime set $component")
            activity = instrumentation.startActivitySync(Intent(context, RimeImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RimeImportActivity
            val host = requireNotNull(activity)
            val editor = host.findViewById<EditText>(R.id.direct_rime_test_editor)
            for (type in listOf(InputType.TYPE_CLASS_TEXT,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
                connection.runOnReady {
                    activateAction(SubModeManager.resolveSchemaMenuEntries(statusArea()).first().second)
                }
                instrumentation.runOnMainSync {
                    editor.inputType = type
                    editor.setText("1237") // Disposable fixture, never a user's password.
                    editor.setSelection(editor.length())
                    editor.requestFocus()
                }
                // Changing inputType restarts the input connection asynchronously. Model the
                // user's explicit focus gesture after that change, instead of relying on a
                // show request submitted against the previous connection.
                val editorBounds = Rect()
                await("The inputType=$type editor must be visible before touching it") {
                    var ready = false
                    instrumentation.runOnMainSync {
                        ready = host.hasWindowFocus() && editor.getGlobalVisibleRect(editorBounds)
                        if (ready) {
                            val origin = IntArray(2)
                            editor.rootView.getLocationOnScreen(origin)
                            editorBounds.offset(origin[0], origin[1])
                        }
                    }
                    ready
                }
                val touchDown = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(touchDown, SystemClock.uptimeMillis(), action,
                        editorBounds.exactCenterX(), editorBounds.exactCenterY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                instrumentation.runOnMainSync { imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
                var generation = Long.MIN_VALUE
                var stableSince = SystemClock.uptimeMillis()
                var editorState = ""
                try {
                    await("Password editor $type must own a settled, visible IME") {
                        val next = connection.runImmediately { inputSession }
                        if (next != generation) { generation = next; stableSince = SystemClock.uptimeMillis() }
                        var ready = false
                        instrumentation.runOnMainSync {
                            ready = host.hasWindowFocus() && editor.hasFocus() && imm.isActive(editor)
                            val views = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
                                .filterIsInstance<InputView>().map {
                                    "shown=${it.isShown},imeShown=${it.service.isInputViewShown},type=${it.service.currentInputEditorInfo?.inputType}"
                                }.toList()
                            editorState = "window=${host.hasWindowFocus()},focus=${editor.hasFocus()},active=${imm.isActive(editor)},views=$views"
                        }
                        ready && inputView()?.service?.isInputViewShown == true &&
                            SystemClock.uptimeMillis() - stableSince >= 750
                    }
                } catch (failure: AssertionError) {
                    shell("screencap -p /data/local/tmp/schema-editor-$type-failure.png")
                    throw AssertionError("${failure.message}; $editorState; ime=${shell("settings get secure default_input_method")}", failure)
                }
                val service = inputView()!!.service
                val secondSchemaAction = connection.runImmediately {
                    SubModeManager.resolveSchemaMenuEntries(statusArea()).first { it.first == "第二方案" }.second
                }
                var focusLosses = 0
                val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) focusLosses++ }
                instrumentation.runOnMainSync { host.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener) }
                try {
                    fun assertEditorHeld() {
                        instrumentation.runOnMainSync {
                            assertTrue("Menu must keep host window focus for inputType=$type", host.hasWindowFocus())
                            assertEquals("Menu must not transiently steal password focus", 0, focusLosses)
                            assertTrue(editor.hasFocus())
                            assertEquals("The tested editor type must reach the IME", type, service.currentInputEditorInfo.inputType)
                            assertTrue("Menu must keep the keyboard visible", service.isInputViewShown)
                            assertEquals("Opening a menu must not change editor text", "1237", editor.text.toString())
                        }
                        assertEquals("Opening/closing menus must not rebind the editor", generation,
                            connection.runImmediately { inputSession })
                    }
                    suspend fun openMenu() {
                        instrumentation.runOnMainSync {
                            // Exact application-action entry used by MacroStep.AppAction.
                            service.inputView!!.executeButtonAction("rime_schema_menu")
                        }
                        await("Schema menu must stay available in password inputType=$type") { menuChoice() != null }
                        delay(800) // The reported focus/hide cycle occurs about 350 ms after show.
                        val choice = menuChoice()
                        assertNotNull("Schema menu disappeared after showing", choice)
                        val secondSelected = connection.runImmediately {
                            SubModeManager.resolveSelectedSchemaAction(statusArea()) == secondSchemaAction
                        }
                        instrumentation.runOnMainSync {
                            assertEquals("Menu selection must use the engine schema, including password mode",
                                secondSelected, choice!!.isChecked)
                            val flags = (choice!!.rootView.layoutParams as WindowManager.LayoutParams).flags
                            assertTrue("The shown menu must not take editor focus",
                                flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                            assertEquals("AlertDialog must not restore ALT_FOCUSABLE_IM while creating its content",
                                0, flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                        }
                        assertEditorHeld()
                    }
                    openMenu()
                    val bounds = Rect()
                    val choice = menuChoice()!!
                    instrumentation.runOnMainSync {
                        val origin = IntArray(2)
                        choice.getLocationOnScreen(origin)
                        bounds.set(origin[0], origin[1], origin[0] + choice.width, origin[1] + choice.height)
                    }
                    val down = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                            bounds.exactCenterX(), bounds.exactCenterY(), 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                        finally { event.recycle() }
                    }
                    await("Touch must activate the selected schema and dismiss its menu") {
                        menuChoice() == null && connection.runImmediately {
                            SubModeManager.resolveSelectedSchemaAction(statusArea()) == secondSchemaAction
                        }
                    }
                    assertEquals("Password editors must retain direct-input presentation",
                        if (type == InputType.TYPE_CLASS_TEXT) "第二方案" else "abc",
                        connection.runImmediately { inputMethodEntryCached.subMode.label })
                    delay(500)
                    assertEditorHeld()
                    openMenu()
                    shell("input keyevent KEYCODE_BACK")
                    await("Back must dismiss the menu") { menuChoice() == null }
                    delay(500)
                    assertEditorHeld()
                    if (type == InputType.TYPE_CLASS_TEXT) {
                        openMenu()
                        val menu = menuChoice()!!
                        var x = 0
                        var y = 0
                        instrumentation.runOnMainSync {
                            // GlobalVisibleRect uses each window's own root
                            // coordinates. Compare actual screen positions.
                            val toolbar = descendants(host.window.decorView)
                                .filterIsInstance<androidx.appcompat.widget.Toolbar>().first()
                            val origin = IntArray(2)
                            toolbar.getLocationOnScreen(origin)
                            x = origin[0] + toolbar.width - 2
                            y = origin[1] + toolbar.height / 2
                            menu.rootView.getLocationOnScreen(origin)
                            val menuBounds = Rect(origin[0], origin[1],
                                origin[0] + menu.rootView.width, origin[1] + menu.rootView.height)
                            assertFalse("Outside-touch fixture must be outside the menu", menuBounds.contains(x, y))
                        }
                        shell("input tap $x $y")
                        await("Touching outside must dismiss the non-focusable menu") { menuChoice() == null }
                        delay(500)
                        assertEditorHeld()
                    }
                    instrumentation.runOnMainSync { editor.setSelection(editor.length()) }
                    shell("input text 7")
                    await("Typing must still reach the same editor after menu dismissal") {
                        var text = ""
                        instrumentation.runOnMainSync { text = editor.text.toString() }
                        text == "12377"
                    }
                } finally {
                    instrumentation.runOnMainSync {
                        host.window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
                    }
                }
            }
            shell("input keyevent KEYCODE_BACK")
            await("Back must hide the keyboard normally when no menu is open") {
                inputView()?.service?.isInputViewShown != true
            }
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.runOnMainSync {
                prefs.candidates.mode.setValue(oldFloating)
                prefs.keyboard.textKeyboardLayoutProfile.setValue(oldLayout)
            }
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
            if (!enabled) shell("ime disable $component")
        }
    }

    private suspend fun keyboardTouchScenario(immediate: Boolean, punctuation: Boolean = false) {
        val component = "${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        val previous = shell("settings get secure default_input_method")
        val enabled = component in shell("ime list -s")
        val prefs = AppPrefs.getInstance()
        val oldFloating = prefs.candidates.mode.getValue()
        val oldLayout = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        var activity: RimeImportActivity? = null
        var hintView: InputView? = null
        var hintListener: ViewTreeObserver.OnPreDrawListener? = null
        val hintFrames = org.json.JSONArray()
        val hintPresentations = org.json.JSONArray()
        var restoreHintPresentationCallback: (() -> Unit)? = null
        val hintPanels = java.util.concurrent.ConcurrentLinkedQueue<org.json.JSONObject>()
        var hintEvents: Job? = null
        data class AndroidBinding(val service: org.fcitx.fcitx5.android.input.FcitxInputMethodService, val generation: Int)
        // isActive(editor) changes in the client before the IME receives onStartInput.
        // Observe that actual Android callback, without waiting for native readiness.
        val bindingCounter = org.fcitx.fcitx5.android.input.FcitxInputMethodService::class.java
            .getDeclaredField("inputSessionGeneration").apply { isAccessible = true }
        fun androidBinding(editor: EditText? = null): AndroidBinding? {
            var binding: AndroidBinding? = null
            instrumentation.runOnMainSync {
                fun find(view: View) {
                    if (binding != null || !view.isShown) return
                    if (view is InputView) {
                        val service = view.service
                        val info = service.currentInputEditorInfo
                        if (service.isInputViewShown && service.editorSession.capture()?.isCurrent() == true &&
                            info != null && (editor == null ||
                                (info.packageName == context.packageName && info.fieldId == editor.id && info.inputType == editor.inputType))) {
                            binding = AndroidBinding(service, bindingCounter.getInt(service))
                        }
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))
                }
                WindowInspector.getGlobalWindowViews().forEach(::find)
            }
            return binding
        }
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("settings put system screen_off_timeout 1800000")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            instrumentation.runOnMainSync {
                prefs.candidates.mode.setValue(FloatingCandidatesMode.Disabled)
                prefs.keyboard.textKeyboardLayoutProfile.setValue("default")
            }
            shell("ime enable $component"); shell("ime set $component")
            val beforeShowBinding = if (immediate) androidBinding() else null
            activity = instrumentation.startActivitySync(Intent(context, RimeImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RimeImportActivity
            val editor = activity.findViewById<EditText>(R.id.direct_rime_test_editor)
            instrumentation.runOnMainSync {
                editor.requestFocus()
                (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            // Use the public window inspector to find the actual custom TextView.
            // AutoScaleTextView draws its own text instead of updating TextView.mText,
            // so AccessibilityNodeInfo.text is empty for these existing keyboard cells.
            fun key(text: String, root: View? = null): Rect? {
                var result: Rect? = null
                instrumentation.runOnMainSync {
                    fun find(view: android.view.View): Rect? {
                        if (!view.isShown) return null
                        if (view is org.fcitx.fcitx5.android.input.AutoScaleTextView) {
                            val label = view.text.toString().trim()
                            if (label.equals(text, ignoreCase = true) || (text == "你" && label.startsWith(text))) {
                                val rect = Rect()
                                if (view.getGlobalVisibleRect(rect) && !rect.isEmpty) {
                                    val origin = IntArray(2)
                                    view.getLocationOnScreen(origin)
                                    return Rect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height)
                                }
                            }
                        }
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount)
                            find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    result = if (root != null) find(root) else
                        android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { find(it) }
                }
                return result
            }
            val touchTrace = org.json.JSONArray()
            fun tap(text: String, settle: Boolean = true) {
                // Showing the first preedit can resize the IME window. Wait for
                // its actual screen position before injecting the next touch.
                var bounds: Rect? = null
                var stable = 0
                await("Original keyboard did not settle key '$text'") {
                    val next = key(text)
                    stable = if (next != null && next == bounds) stable + 1 else 0
                    bounds = next
                    if (settle) stable >= 5 else next != null
                }
                val target = bounds!!
                val trace = if (immediate) org.json.JSONObject().apply {
                    put("key", text); put("bounds", target.toShortString())
                    put("at_uptime_ms", SystemClock.uptimeMillis())
                }.also(touchTrace::put) else null
                val down = SystemClock.uptimeMillis()
                fun inject(action: Int) {
                    val eventTime = SystemClock.uptimeMillis()
                    trace?.put(if (action == MotionEvent.ACTION_DOWN) "down_ms" else "up_ms", eventTime)
                    trace?.put(if (action == MotionEvent.ACTION_DOWN) "generation_at_down" else "generation_at_up",
                        FcitxDaemon.currentInputSession)
                    val event = MotionEvent.obtain(down, eventTime, action,
                        target.exactCenterX(), target.exactCenterY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue("Touch injection failed for '$text' at $target",
                        // Waiting for DOWN to finish on TCG can turn a requested 20 ms
                        // tap into a >300 ms hold. Queue DOWN promptly; synchronize UP
                        // to flush both events before reading native/editor state.
                        instrumentation.uiAutomation.injectInputEvent(event, action == MotionEvent.ACTION_UP)) }
                    finally { event.recycle() }
                    trace?.put(if (action == MotionEvent.ACTION_DOWN) "down_return_ms" else "up_return_ms", SystemClock.uptimeMillis())
                }
                inject(MotionEvent.ACTION_DOWN)
                SystemClock.sleep(if (settle) 50 else 20)
                inject(MotionEvent.ACTION_UP)
                instrumentation.waitForIdleSync()
            }
            fun expectPreedit(expected: String) {
                try { await("Touch keys did not produce '$expected'") {
                    connection.runImmediately { inputPanelCached.preedit.toString() } == expected
                } } catch (e: AssertionError) {
                    shell("screencap -p /data/local/tmp/direct-frontend-failure.png")
                    throw AssertionError("${e.message}; actual='${connection.runImmediately { inputPanelCached.preedit }}'; " +
                        "ime=${connection.runImmediately { inputMethodEntryCached }}", e)
                }
            }
            await("Keyboard must be visible before mode switching") { key("n") != null }
            if (punctuation) {
                await("Punctuation test must bind its actual editor") { androidBinding(editor) != null }
                val symbolLabel = if (key(".") != null) "." else "。"
                tap(symbolLabel)
                await("The SymbolKey must let Rime convert period to Chinese punctuation") {
                    var text = ""
                    instrumentation.runOnMainSync { text = editor.text.toString() }
                    text == "。"
                }
                assertEquals("。", withTimeout(10_000) { commits.receive() })
                val inputView = requireNotNull(androidBinding(editor)?.service?.inputView)
                val popup = InputView::class.java.getDeclaredField("popup").apply { isAccessible = true }
                    .get(inputView) as org.fcitx.fcitx5.android.input.popup.PopupComponent
                val bounds = requireNotNull(key(symbolLabel))
                val downTime = SystemClock.uptimeMillis()
                fun inject(action: Int, x: Float, y: Float) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try {
                        val accepted = instrumentation.uiAutomation.injectInputEvent(event, true)
                        // Cancellation only releases an unfinished gesture during failure cleanup.
                        if (action != MotionEvent.ACTION_CANCEL) assertTrue("Touch action $action must be accepted", accepted)
                    }
                    finally { event.recycle() }
                }
                inject(MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY())
                var released = false
                try {
                    await("Long-press must show the literal comma popup candidate") {
                        key(",", popup.root) != null
                    }
                    shell("screencap -p /data/local/tmp/direct-frontend-punctuation-popup.png")
                    val comma = requireNotNull(key(",", popup.root))
                    // Popup rows switch focus above the lower 30% of a cell.
                    val commaY = comma.bottom - 1f
                    inject(MotionEvent.ACTION_MOVE, comma.exactCenterX(), commaY)
                    inject(MotionEvent.ACTION_UP, comma.exactCenterX(), commaY)
                    released = true
                } finally {
                    if (!released) inject(MotionEvent.ACTION_CANCEL, bounds.exactCenterX(), bounds.exactCenterY())
                }
                try {
                    await("Selecting the popup comma must pass through Rime instead of directly committing ASCII") {
                        var text = ""
                        instrumentation.runOnMainSync { text = editor.text.toString() }
                        text == "。，"
                    }
                } catch (error: AssertionError) {
                    var text = ""
                    instrumentation.runOnMainSync { text = editor.text.toString() }
                    shell("screencap -p /data/local/tmp/direct-frontend-punctuation-failure.png")
                    throw AssertionError("${error.message}; editor='$text'", error)
                }
                assertEquals("，", withTimeout(10_000) { commits.receive() })
                assertNull("Popup punctuation must commit exactly once", withTimeoutOrNull(250) { commits.receive() })
                shell("screencap -p /data/local/tmp/direct-frontend-punctuation.png")
                return
            }
            if (immediate) {
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
                await("The IME must receive the target editor's Android binding callback") {
                    val binding = androidBinding(editor)
                    imm.isActive(editor) && binding != null &&
                        (beforeShowBinding == null || binding.service !== beforeShowBinding.service ||
                            binding.generation > beforeShowBinding.generation)
                }
                val rounds = org.json.JSONArray()
                repeat(6) { round ->
                    // Wait only for Android's new binding callback, never for native
                    // readiness or a warm-up word. The first round uses the first show.
                    if (round > 0) {
                        val previousGeneration = connection.runImmediately { inputSession }
                        val previousBinding = requireNotNull(androidBinding(editor))
                        instrumentation.runOnMainSync { imm.restartInput(editor) }
                        await("restartInput must deliver a new Android binding for this editor") {
                            val next = androidBinding(editor)
                            next != null && (next.service !== previousBinding.service || next.generation > previousBinding.generation) &&
                                connection.runImmediately { inputSession } > previousGeneration
                        }
                    }
                    val bindingAtTouch = requireNotNull(androidBinding(editor))
                    val generation = connection.runImmediately { inputSession }
                    val firstTouch = SystemClock.elapsedRealtimeNanos()
                    var stage = "first key"
                    try {
                        tap("n", false)
                        await("The first key after binding must be retained") {
                            connection.runImmediately { inputPanelCached.preedit.toString() } == "n"
                        }
                        val firstPreedit = SystemClock.elapsedRealtimeNanos()
                        stage = "second key and candidates"
                        tap("i", false)
                        await("The candidate view must bind immediately after typing") { key("你") != null }
                        val candidateBound = SystemClock.elapsedRealtimeNanos()
                        val selectTouch = SystemClock.elapsedRealtimeNanos()
                        stage = "candidate commit"
                        tap("你", false)
                        await("The first candidate tap after binding must reach the editor exactly once") {
                            var text = ""
                            instrumentation.runOnMainSync { text = editor.text.toString() }
                            text == "你".repeat(round + 1)
                        }
                        assertEquals("你", withTimeout(10_000) { commits.receive() })
                        rounds.put(org.json.JSONObject().apply {
                            put("round", round + 1); put("generation", generation)
                            put("android_binding_generation", bindingAtTouch.generation)
                            put("first_touch_to_preedit_observed_ms", (firstPreedit - firstTouch) / 1_000_000.0)
                            put("first_touch_to_candidate_view_bound_ms", (candidateBound - firstTouch) / 1_000_000.0)
                            put("candidate_touch_to_editor_observed_ms", (SystemClock.elapsedRealtimeNanos() - selectTouch) / 1_000_000.0)
                        })
                    } catch (error: Throwable) {
                        // Preserve the original assertion and criterion; collect state before
                        // finally closes the editor, so a missed touch can be distinguished
                        // from a key/session/commit defect on a slow emulator.
                        runCatching {
                            val diagnostic = connection.runImmediately { org.json.JSONObject().apply {
                                put("harness_compile_sha", BuildConfig.BUILD_GIT_HASH)
                                put("round", round + 1); put("stage", stage)
                                put("generation_before_touch_lookup", generation); put("generation_at_failure", inputSession)
                                put("android_binding_at_touch", bindingAtTouch.generation)
                                put("accepts_input", acceptsInput); put("ready", isReady)
                                put("preedit", inputPanelCached.preedit.toString())
                                put("client_preedit", clientPreeditCached.toString())
                                put("ime", inputMethodEntryCached.toString())
                                put("status", statusAreaActionsCached.joinToString())
                                put("touches", touchTrace)
                            } }
                            diagnostic.put("android_binding_at_failure", androidBinding(editor)?.generation)
                            diagnostic.put("target_package_version",
                                context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                            instrumentation.runOnMainSync {
                                diagnostic.put("editor_text", editor.text.toString())
                                diagnostic.put("editor_id", editor.id)
                                diagnostic.put("editor_input_type", editor.inputType)
                                diagnostic.put("editor_focused", editor.hasFocus())
                                diagnostic.put("editor_window_focused", editor.hasWindowFocus())
                                diagnostic.put("android_active", imm.isActive(editor))
                            }
                            diagnostic.put("n_bounds_at_failure", key("n")?.toShortString())
                            diagnostic.put("candidate_bounds_at_failure", key("你")?.toShortString())
                            diagnostic.put("observed_commit", commits.tryReceive().getOrNull())
                            File(context.filesDir, "direct-rime-touch-failure.json").writeText(diagnostic.toString(2))
                            shell("screencap -p /data/local/tmp/direct-rime-touch-failure.png")
                        }
                        throw error
                    }

                }
                assertNull("Rebinding and candidate refresh must not duplicate commits", withTimeoutOrNull(250) { commits.receive() })
                val modelMapped = File("/proc/self/maps").readText().contains("wanxiang-lts-zh-hans.gram")
                if (fixtureArchive != null) assertTrue("The Wanxiang LTS model must actually be mapped", modelMapped)
                File(context.filesDir, "direct-rime-rebind.json").writeText(org.json.JSONObject().apply {
                    put("harness_commit", InstrumentationRegistry.getArguments().getString("harnessCommit") ?: BuildConfig.BUILD_GIT_HASH)
                    put("target_package_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                    put("fixture", fixtureArchive ?: "bundled-smoke")
                    put("model_mapped", modelMapped)
                    put("metric", "UI-injected touch through observed backend/view/editor state; includes injection and polling; not frame presentation latency")
                    put("rounds", rounds)
                    put("touches", touchTrace)
                }.toString(2))
                return
            }
            // A previous test can leave its keyboard window visible while Android is
            // still binding this editor. A new input session correctly clears hints.
            var settledSession = Long.MIN_VALUE
            var settledSince = SystemClock.uptimeMillis()
            await("The editor's input session must settle before checking a transient hint") {
                val session = connection.runImmediately { inputSession }
                if (session != settledSession) {
                    settledSession = session
                    settledSince = SystemClock.uptimeMillis()
                }
                (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .isActive(editor) && SystemClock.uptimeMillis() - settledSince >= 750
            }
            val hintService = androidBinding(editor)?.service
            instrumentation.runOnMainSync {
                hintView = hintService?.inputView
                val view = requireNotNull(hintView)
                val component = InputView::class.java.getDeclaredField("preedit").apply { isAccessible = true }
                    .get(view) as org.fcitx.fcitx5.android.input.preedit.PreeditComponent
                val previousPresentation = component.onStatusHintPresented
                component.onStatusHintPresented = { id, frameTime ->
                    hintPresentations.put(org.json.JSONObject().put("id", id).put("frame_uptime_ms", frameTime))
                    previousPresentation(id, frameTime)
                }
                restoreHintPresentationCallback = { component.onStatusHintPresented = previousPresentation }
                var lastFrame = ""
                hintListener = ViewTreeObserver.OnPreDrawListener {
                    val root = component.ui.root
                    fun texts(node: View): List<String> = when (node) {
                        is android.widget.TextView -> listOf("${node.text}|shown=${node.isShown}|size=${node.width}x${node.height}")
                        is ViewGroup -> (0 until node.childCount).flatMap { texts(node.getChildAt(it)) }
                        else -> emptyList()
                    }
                    val state = "handle=${view.handleEvents},shown=${view.isShown},display=${component.shouldDisplay}," +
                        "rootShown=${root.isShown},visibility=${root.visibility},size=${root.width}x${root.height},texts=${texts(root)}"
                    if (state != lastFrame && hintFrames.length() < 64) {
                        lastFrame = state
                        hintFrames.put(org.json.JSONObject().put("uptime_ms", SystemClock.uptimeMillis()).put("state", state))
                    }
                    true
                }.also { view.viewTreeObserver.addOnPreDrawListener(it) }
            }
            hintEvents = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                connection.runImmediately { eventFlow }.collect { event ->
                    if (event is FcitxEvent.InputPanelEvent && hintPanels.size < 64) {
                        hintPanels.add(org.json.JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
                            .put("session", event.inputSession).put("aux_up", event.data.auxUp.toString())
                            .put("preedit", event.data.preedit.toString()))
                    }
                }
            }
            fun hintIsVisible(text: String): Boolean {
                var visible = false
                instrumentation.runOnMainSync {
                    fun contains(view: android.view.View): Boolean {
                        if (!view.isShown) return false
                        if (view is android.widget.TextView && view.text.toString() == text) return true
                        return view is android.view.ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) }
                    }
                    visible = android.view.inspector.WindowInspector.getGlobalWindowViews().any(::contains)
                }
                return visible
            }
            var expectedModeHintId = 0L
            connection.runOnReady {
                toggleIme()
                assertEquals("Mode hint must belong to the settled editor session",
                    context.getString(R.string.direct_rime_ascii_mode), inputPanelCached.auxUp.toString())
                expectedModeHintId = inputPanelCached.statusHintId
            }
            await("English-mode hint was not displayed in the preedit area") {
                hintIsVisible(context.getString(R.string.direct_rime_ascii_mode))
            }
            await("The visible English hint must acknowledge its own first frame") {
                var acknowledged = false
                instrumentation.runOnMainSync {
                    acknowledged = (0 until hintPresentations.length()).any {
                        hintPresentations.getJSONObject(it).getLong("id") == expectedModeHintId
                    }
                }
                acknowledged
            }
            shell("screencap -p /data/local/tmp/direct-rime-english-hint.png")
            delay(1800)
            connection.runOnReady {
                assertTrue("The first visible frame must arm normal hint expiry", inputPanelCached.auxUp.isEmpty())
            }
            await("The English hint must disappear from the actual preedit view") {
                !hintIsVisible(context.getString(R.string.direct_rime_ascii_mode))
            }
            connection.runOnReady {
                toggleIme()
                activateAction(statusArea().first { it.name == "rime" }.menu!!.first { it.name == "second" }.id)
            }
            await("Schema hint was not displayed in the preedit area") { hintIsVisible("第二方案") }
            connection.runOnReady {
                activateAction(statusArea().first { it.name == "rime" }.menu!!.first { it.name == "direct_smoke" }.id)
            }
            tap("n"); expectPreedit("n")
            tap("i"); expectPreedit("ni")
            shell("screencap -p /data/local/tmp/direct-frontend-keyboard.png")
            // Candidate taps exercise the original candidate adapter, not a hardware-space shortcut.
            await("Original candidate bar did not show Chinese") { key("你") != null }
            tap("你")
            await("Candidate tap did not commit Chinese into Android editor") {
                var text = ""; instrumentation.runOnMainSync { text = editor.text.toString() }; text == "你"
            }
            shell("screencap -p /data/local/tmp/direct-frontend-commit.png")
            assertTrue("The original app must install its layout presets", File(context.getExternalFilesDir(null), "config")
                .listFiles().orEmpty().any { it.name.startsWith("TextKeyboardLayout.") })
            val maps = File("/proc/self/maps").readText()
            assertFalse("Fcitx libraries must not be loaded in the direct APK", maps.contains("libFcitx5Core.so") ||
                maps.contains("libFcitx5Utils.so") || maps.contains("libFcitx5Config.so") ||
                maps.contains("libnative-lib.so") || maps.contains("librime.so"))
        } finally {
            hintEvents?.cancelAndJoin()
            instrumentation.runOnMainSync {
                restoreHintPresentationCallback?.invoke()
                hintListener?.let { hintView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(it) }
            }
            if (hintListener != null) File(context.filesDir, "direct-rime-hint-ui.json").writeText(org.json.JSONObject()
                .put("runtime_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                .put("harness_commit", InstrumentationRegistry.getArguments().getString("harnessCommit") ?: BuildConfig.BUILD_GIT_HASH)
                .put("frames", hintFrames)
                .put("presentations", hintPresentations)
                .put("panels", org.json.JSONArray(hintPanels.toList())).toString(2))
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.runOnMainSync {
                prefs.candidates.mode.setValue(oldFloating)
                prefs.keyboard.textKeyboardLayoutProfile.setValue(oldLayout)
            }
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
            if (!enabled) shell("ime disable $component")
        }
    }
    @Test fun profileImportSelectionAndMigration(): Unit = runBlocking {
        val folder = File(context.cacheDir, "rime-import").apply { mkdirs() }
        val archive = File(folder, "frontend-valid.zip")
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            instrumentation.context.assets.list("smoke")!!.forEach { name ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                instrumentation.context.assets.open("smoke/$name").use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        try {
            connection.runOnReady {
                val items = getAddonSubConfig("rime", "schema-selector")["Items"].value
                assertTrue(items.contains("direct_smoke") && items.contains("second"))
                setAddonSubConfig("rime", "schema-selector", RawConfig(arrayOf(
                    RawConfig("Items", "0\tdirect_smoke\t直连测试\n1\tsecond\t第二方案")
                )))
                assertEquals(listOf("第二方案"), SubModeManager.resolveSchemaMenuEntries(statusArea()).map { it.first })
                setAddonSubConfig("rime", "import-profile", RawConfig(arrayOf(RawConfig("Archive", archive.absolutePath))))
                val imported = getAddonSubConfig("rime", "profile-manager")["Current"].value
                createdImports += imported
                assertTrue(imported.startsWith("import-"))
                assertTrue(File(context.getExternalFilesDir(null), "data/$profile/direct_smoke.dict.yaml").exists())
                assertEquals(listOf("直连测试"), SubModeManager.resolveSchemaMenuEntries(statusArea()).map { it.first })
                java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("../escape")); zip.write(byteArrayOf(1)); zip.closeEntry()
                }
                var rejected = false
                try { setAddonSubConfig("rime", "import-profile", RawConfig(arrayOf(RawConfig("Archive", archive.absolutePath)))) }
                catch (e: IllegalArgumentException) { rejected = true }
                catch (e: java.util.zip.ZipException) { rejected = true } // Android 14+ validates before our own path check.
                assertTrue("Unsafe archive must be rejected", rejected)
                assertEquals(imported, getAddonSubConfig("rime", "profile-manager")["Current"].value)
                sendKey('n', KeyState.Virtual.state); sendKey('i', KeyState.Virtual.state); select(0)
            }
            assertEquals("你", withTimeout(10_000) { commits.receive() })
        } finally { archive.delete() }
        val isolated = File(context.cacheDir, "migration-${System.nanoTime()}").apply { mkdirs() }
        try {
            val wrapped = object : android.content.ContextWrapper(context) {
                override fun getFilesDir() = File(isolated, "files").apply { mkdirs() }
                override fun getExternalFilesDir(type: String?) = File(isolated, "external").apply { mkdirs() }
            }
            val old = File(wrapped.filesDir, "rime/user").apply { mkdirs() }
            File(old, "default.yaml").writeText("schema_list: []\n")
            val migrated = DirectRimeConfig(wrapped).apply { load() }
            assertEquals("schema_list: []\n", File(migrated.user, "default.yaml").readText())
            File(migrated.user, "default.yaml").writeText("customized")
            DirectRimeConfig(wrapped).load()
            assertEquals("customized", File(migrated.user, "default.yaml").readText())
            assertTrue(File(old, "default.yaml").exists())
        } finally { isolated.deleteRecursively() }
    }
    @Test fun originalSettingsAndLayoutEditorOpen(): Unit = runBlocking {
        val component = "${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        val previous = shell("settings get secure default_input_method")
        val enabled = component in shell("ime list -s")
        shell("ime enable $component"); shell("ime set $component")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val main = instrumentation.startActivitySync(Intent(context, org.fcitx.fcitx5.android.ui.main.MainActivity::class.java)
            .setAction(Intent.ACTION_RUN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(org.fcitx.fcitx5.android.ui.main.MainActivity.EXTRA_SETTINGS_ROUTE,
                org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute.Index)) as org.fcitx.fcitx5.android.ui.main.MainActivity
        var editor: org.fcitx.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutEditorActivity? = null
        try {
            val host = main.supportFragmentManager.fragments.filterIsInstance<androidx.navigation.fragment.NavHostFragment>().single()
            fun page(route: org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute, expected: String, screenshot: String) {
                instrumentation.runOnMainSync { host.navController.navigate(route) }
                await("Settings page did not open: $expected") {
                    var actual = ""
                    instrumentation.runOnMainSync { actual = host.childFragmentManager.primaryNavigationFragment?.javaClass?.simpleName.orEmpty() }
                    actual == expected
                }
                instrumentation.waitForIdleSync()
                shell("screencap -p /data/local/tmp/$screenshot.png")
            }
            shell("screencap -p /data/local/tmp/direct-frontend-settings.png")
            page(org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute.InputMethodConfig("Rime", "rime"), "InputMethodConfigFragment", "direct-frontend-rime-settings")
            page(org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute.GlobalConfig, "InputMethodConfigFragment", "direct-frontend-legacy-settings")
            val entries = org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupSpecs.find("input")!!.entries(context)
            assertFalse(entries.any { it.title == R.string.global_options })
            page(org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute.About, "AboutFragment", "direct-frontend-about")
            instrumentation.runOnMainSync {
                val about = host.childFragmentManager.primaryNavigationFragment as androidx.preference.PreferenceFragmentCompat
                fun titles(group: androidx.preference.PreferenceGroup): List<String> = (0 until group.preferenceCount).flatMap {
                    val item = group.getPreference(it)
                    listOf(item.title.toString()) + if (item is androidx.preference.PreferenceGroup) titles(item) else emptyList()
                }
                val visible = titles(about.preferenceScreen)
                assertTrue(visible.contains(context.getString(R.string.direct_rime_app_name)))
                assertTrue(visible.contains(context.getString(R.string.direct_rime_about_builds)))
                assertTrue(visible.contains(context.getString(R.string.direct_rime_about_package)))
            }
            page(org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute.Theme, "ThemeFragment", "direct-frontend-themes")
            page(org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute.VirtualKeyboard, "KeyboardSettingsFragment", "direct-frontend-keyboard-settings")
            editor = instrumentation.startActivitySync(Intent(context,
                org.fcitx.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutEditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as org.fcitx.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutEditorActivity
            fun rows(view: android.view.View): Boolean {
                if (view is androidx.recyclerview.widget.RecyclerView &&
                    view.adapter is org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter.KeyboardLayoutAdapter &&
                    (view.adapter?.itemCount ?: 0) > 1) return true
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) if (rows(view.getChildAt(i))) return true
                return false
            }
            await("Layout editor did not load editable keyboard rows") {
                var loaded = false
                instrumentation.runOnMainSync { loaded = rows(editor!!.window.decorView) }
                loaded
            }
            shell("screencap -p /data/local/tmp/direct-frontend-layout-editor.png")
        } finally {
            instrumentation.runOnMainSync { editor?.finish(); main.finish() }
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
            if (!enabled) shell("ime disable $component")
        }
    }
}
