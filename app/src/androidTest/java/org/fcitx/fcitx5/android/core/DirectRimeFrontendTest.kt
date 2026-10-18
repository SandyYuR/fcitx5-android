/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
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
        assertEquals("io.github.sandyyur.rime.direct", context.packageName)
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
            File(directory, "second.schema.yaml").writeText(File(directory, "direct_smoke.schema.yaml").readText()
                .replace("schema_id: direct_smoke", "schema_id: second").replace("name: 直连测试", "name: 第二方案"))
            File(directory, "default.yaml").appendText("\n")
            val default = File(directory, "default.yaml")
            default.writeText(default.readText().replace("- schema: direct_smoke", "- schema: direct_smoke\n  - schema: second"))
        }
        connection.runOnReady {
            setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "switch"), RawConfig("Name", profile))))
            setImConfig("rime", RawConfig(arrayOf(RawConfig("PreeditMode", "No"))))
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
    private fun writeCommentFixture(directory: File) {
        // Wanxiang uses a three-way comment group and Lua to rewrite/clear comments.
        // Reproduce that contract with a tiny dictionary, without bundling its data.
        val schema = File(directory, "direct_smoke.schema.yaml")
        schema.writeText(schema.readText().replace("engine:\n", """
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
            assertFalse(acceptsInput)
            assertTrue(inputPanelCached.preedit.isEmpty())
        }
        assertNull(FcitxDaemon.captureInputKey())
        assertTrue(FcitxDaemon.requireInputSession())
        val first = FcitxDaemon.captureInputKey()!!
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
    @Test fun modeAndSchemaHintsExpireWithoutReplayingCommits(): Unit = runBlocking {
        connection.runOnReady {
            assertTrue("New sessions must not show a switch hint", inputPanelCached.auxUp.isEmpty())
            toggleIme()
            assertEquals(context.getString(R.string.direct_rime_ascii_mode), inputPanelCached.auxUp.toString())
            // Releasing a key must not immediately erase a mode hint.
            sendKey("Control_L", KeyState.Virtual.state, up = true)
            assertTrue(inputPanelCached.auxUp.isNotEmpty())
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

    @Test fun originalKeyboardCanTypeUsingTouch(): Unit = runBlocking { keyboardTouchScenario(false) }

    @Test fun immediateTypingAndCandidateTapAfterRebind(): Unit = runBlocking { keyboardTouchScenario(true) }

    private suspend fun keyboardTouchScenario(immediate: Boolean) {
        val component = "${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        val previous = shell("settings get secure default_input_method")
        val enabled = component in shell("ime list -s")
        val prefs = AppPrefs.getInstance()
        val oldFloating = prefs.candidates.mode.getValue()
        val oldLayout = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        var activity: RimeImportActivity? = null
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("settings put system screen_off_timeout 1800000")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            instrumentation.runOnMainSync {
                prefs.candidates.mode.setValue(FloatingCandidatesMode.Disabled)
                prefs.keyboard.textKeyboardLayoutProfile.setValue("default")
            }
            shell("ime enable $component"); shell("ime set $component")
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
            fun key(text: String): Rect? {
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
                    result = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { find(it) }
                }
                return result
            }
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
                val down = SystemClock.uptimeMillis()
                fun inject(action: Int) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        target.exactCenterX(), target.exactCenterY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue("Touch injection failed for '$text' at $target",
                        instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
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
            if (immediate) {
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
                await("The target editor must own the Android input connection") { imm.isActive(editor) }
                val rounds = org.json.JSONArray()
                repeat(6) { round ->
                    // Wait only for Android's new binding callback, never for native
                    // readiness or a warm-up word. The first round uses the first show.
                    if (round > 0) {
                        val previousGeneration = connection.runImmediately { inputSession }
                        instrumentation.runOnMainSync { imm.restartInput(editor) }
                        await("restartInput must establish a new logical editor") {
                            connection.runImmediately { inputSession } > previousGeneration
                        }
                    }
                    val generation = connection.runImmediately { inputSession }
                    val firstTouch = SystemClock.elapsedRealtimeNanos()
                    tap("n", false)
                    await("The first key after binding must be retained") {
                        connection.runImmediately { inputPanelCached.preedit.toString() } == "n"
                    }
                    val firstPreedit = SystemClock.elapsedRealtimeNanos()
                    tap("i", false)
                    await("The candidate view must bind immediately after typing") { key("你") != null }
                    val candidateBound = SystemClock.elapsedRealtimeNanos()
                    val selectTouch = SystemClock.elapsedRealtimeNanos()
                    tap("你", false)
                    await("The first candidate tap after binding must reach the editor exactly once") {
                        var text = ""
                        instrumentation.runOnMainSync { text = editor.text.toString() }
                        text == "你".repeat(round + 1)
                    }
                    assertEquals("你", withTimeout(10_000) { commits.receive() })
                    rounds.put(org.json.JSONObject().apply {
                        put("round", round + 1); put("generation", generation)
                        put("first_touch_to_preedit_observed_ms", (firstPreedit - firstTouch) / 1_000_000.0)
                        put("first_touch_to_candidate_view_bound_ms", (candidateBound - firstTouch) / 1_000_000.0)
                        put("candidate_touch_to_editor_observed_ms", (SystemClock.elapsedRealtimeNanos() - selectTouch) / 1_000_000.0)
                    })
                }
                assertNull("Rebinding and candidate refresh must not duplicate commits", withTimeoutOrNull(250) { commits.receive() })
                val modelMapped = File("/proc/self/maps").readText().contains("wanxiang-lts-zh-hans.gram")
                if (fixtureArchive != null) assertTrue("The Wanxiang LTS model must actually be mapped", modelMapped)
                File(context.filesDir, "direct-rime-rebind.json").writeText(org.json.JSONObject().apply {
                    put("harness_commit", BuildConfig.BUILD_GIT_HASH)
                    put("fixture", fixtureArchive ?: "bundled-smoke")
                    put("model_mapped", modelMapped)
                    put("metric", "UI-injected touch through observed backend/view/editor state; includes injection and polling; not frame presentation latency")
                    put("rounds", rounds)
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
            connection.runOnReady {
                toggleIme()
                assertEquals("Mode hint must belong to the settled editor session",
                    context.getString(R.string.direct_rime_ascii_mode), inputPanelCached.auxUp.toString())
            }
            await("English-mode hint was not displayed in the preedit area") {
                hintIsVisible(context.getString(R.string.direct_rime_ascii_mode))
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
