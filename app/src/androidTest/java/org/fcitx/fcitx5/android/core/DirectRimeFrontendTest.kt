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
import java.io.File
import java.io.FileInputStream

/** Disposable emulator only: original UI on the direct backend, not the small lab service. */
class DirectRimeFrontendTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val connection by lazy { FcitxDaemon.connect(javaClass.name) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commits = Channel<String>(Channel.UNLIMITED)
    private var previousProfile = "rime"
    private val createdImports = mutableListOf<String>()
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
        assertTrue("Build with -PdirectRimeFrontend=true", BuildConfig.DIRECT_RIME)
        connection.runOnReady {
            previousProfile = getAddonSubConfig("rime", "profile-manager")["Current"].value
            setAddonSubConfig("rime", "profile-manager", RawConfig(arrayOf(RawConfig("Action", "create"), RawConfig("Name", profile))))
        }
        val directory = File(context.getExternalFilesDir(null), "data/$profile")
        instrumentation.context.assets.list("smoke")!!.forEach { name ->
            instrumentation.context.assets.open("smoke/$name").use { input -> File(directory, name).outputStream().use { input.copyTo(it) } }
        }
        File(directory, "second.schema.yaml").writeText(File(directory, "direct_smoke.schema.yaml").readText()
            .replace("schema_id: direct_smoke", "schema_id: second").replace("name: 直连测试", "name: 第二方案"))
        File(directory, "default.yaml").appendText("\n")
        val default = File(directory, "default.yaml")
        default.writeText(default.readText().replace("- schema: direct_smoke", "- schema: direct_smoke\n  - schema: second"))
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
    @Test fun originalKeyboardCanTypeUsingTouch(): Unit = runBlocking {
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
            fun tap(text: String) {
                // Showing the first preedit can resize the IME window. Wait for
                // its actual screen position before injecting the next touch.
                var bounds: Rect? = null
                var stable = 0
                await("Original keyboard did not settle key '$text'") {
                    val next = key(text)
                    stable = if (next != null && next == bounds) stable + 1 else 0
                    bounds = next
                    stable >= 5
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
                SystemClock.sleep(50)
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
            assertFalse("Fcitx routing must not be loaded in the direct APK", maps.contains("libFcitx5Core.so") || maps.contains("libnative-lib.so") || maps.contains("librime.so"))
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
