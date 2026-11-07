/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.data.stats.InputStats
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.keyboardtest.KeyboardTestPanel
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.im.RimeImportActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.IdentityHashMap
import java.util.UUID

/** Real IME, physical soft-key taps and real InputConnections. Requires a disposable emulator. */
@SdkSuppress(minSdkVersion = 29)
class InputStatisticsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val name = "stats-ui-${UUID.randomUUID()}"
    private val connection by lazy { FcitxDaemon.connect(name) }
    private val activities = mutableListOf<Activity>()
    private data class ServiceStatsState(val store: InputStats?, val layout: Any?, val epoch: Long)
    private val stores = IdentityHashMap<FcitxInputMethodService, ServiceStatsState>()
    private val storeField = FcitxInputMethodService::class.java.getDeclaredField("statisticsStore").apply { isAccessible = true }
    private val layoutField = FcitxInputMethodService::class.java.getDeclaredField("lastStatisticsLayout").apply { isAccessible = true }
    private val epochField = FcitxInputMethodService::class.java.getDeclaredField("lastStatisticsLayoutEpoch").apply { isAccessible = true }
    private lateinit var stats: InputStats

    @Test fun realInputCountsAndKeyboardTestPasswordsStayPrivate() = runBlocking {
        assertTrue("Only the isolated Debug application may run this test", BuildConfig.DEBUG && context.packageName.endsWith(".debug"))
        val component = "${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        val previousIme = shell("settings get secure default_input_method")
        val wasEnabled = component in shell("ime list -s").lineSequence().toList()
        val previousHardwareIme = shell("settings get secure show_ime_with_hard_keyboard")
        val previousFontScale = shell("settings get system font_scale")
        val previousUi = SettingsUiPreferences.read(context)
        val prefs = AppPrefs.getInstance()
        val previousFloating = prefs.candidates.mode.getValue()
        val previousLayout = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        val previousNotifications = prefs.internal.needNotifications.getValue()
        val temporary = File(context.cacheDir, name).canonicalFile
        assertEquals(context.cacheDir.canonicalFile, temporary.parentFile)
        assertTrue(temporary.mkdir())
        // Inject a test-owned actor into the actual service. The user's statistics singleton,
        // its opt-in flags, vocabulary and database are never cleared or replaced.
        stats = InputStats(object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = temporary
        }, "test.db")
        var previousProfile: String? = null
        var createdProfile = false
        try {
            withTimeout(10_000) { while (!stats.settings.value.loaded) delay(10) }
            assertNull(stats.settings.value.error)
            stats.setEnabled(true)
            stats.setWordFrequencyEnabled(true)
            connection.runOnReady {
                previousProfile = getAddonSubConfig("rime", "profile-manager")["Current"].value
                setAddonSubConfig("rime", "profile-manager", profileCommand("create", name))
                createdProfile = true
            }
            val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "data/$name").canonicalFile
            assertEquals(File(context.getExternalFilesDir(null) ?: context.filesDir, "data").canonicalFile, directory.parentFile)
            instrumentation.context.assets.list("smoke")!!.forEach { file ->
                instrumentation.context.assets.open("smoke/$file").use { input ->
                    File(directory, file).outputStream().use { input.copyTo(it) }
                }
            }
            connection.runOnReady { setAddonSubConfig("rime", "profile-manager", profileCommand("switch", name)) }
            onMain {
                prefs.candidates.mode.setValue(FloatingCandidatesMode.Disabled)
                prefs.keyboard.textKeyboardLayoutProfile.setValue("default")
                prefs.internal.needNotifications.setValue(false)
            }
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("settings put system font_scale 1.0")
            shell("ime enable $component")
            shell("ime set $component")

            val activity = launch(RimeImportActivity::class.java)
            val editor = activity.findViewById<EditText>(R.id.direct_rime_test_editor)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            onMain {
                editor.inputType = InputType.TYPE_CLASS_TEXT
                editor.setText("")
                editor.requestFocus()
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            val service = settledService(editor)
            onMain { service.restoreVirtualKeyboardForKawaiiBarAction() }
            tapSoftKey("n")
            await("First real key must reach the native composition") {
                connection.runImmediately { clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty() }
            }
            tapSoftKey("i")
            tapSoftKey("Space")
            await("The real editor must accept the fixture's Chinese commit") { editor.text.toString() == "你" }
            withTimeout(10_000) {
                while (stats.snapshot().totals.characters != 1L) delay(20)
            }
            val ordinary = stats.snapshot(schemaId = "direct_smoke")
            assertEquals(1L, ordinary.totals.characters)
            assertEquals(3L, ordinary.totals.keys)
            assertEquals(3L, ordinary.totals.totalCodeLength)
            assertEquals(1L, ordinary.words["你"])
            assertEquals(1L, ordinary.keyHeatmap["n"])
            assertEquals(1L, ordinary.keyHeatmap["i"])
            assertEquals(1L, ordinary.keyHeatmap["Space"])
            assertTrue("Heatmap must retain the actual visible keyboard geometry", ordinary.layout?.geometry?.isNotEmpty() == true)
            capture("accepted-real-input")
            close(activity)

            for ((suffix, dark, fontScale) in listOf(
                Triple("light", false, 1f), Triple("dark", true, 1f), Triple("large", false, 1.6f)
            )) {
                shell("settings put system font_scale $fontScale")
                SettingsUiPreferences.save(context, previousUi.copy(
                    mode = if (dark) SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
                    palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false))
                val main = launch(MainActivity::class.java, Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_RUN
                    putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.Index)
                })
                if (fontScale > 1f) onMain { main.window.setLayout(dp(main, 320), ViewGroup.LayoutParams.MATCH_PARENT) }
                var entry: View? = null
                await("The settings Toolbar must expose keyboard testing") {
                    entry = descendants(main.window.decorView).firstOrNull {
                        it.isShown && it.contentDescription == main.getString(R.string.keyboard_test_title)
                    }
                    entry != null
                }
                tap(checkNotNull(entry))
                var panel: KeyboardTestPanel? = null
                await("The Toolbar should open the four-tab input panel") {
                    panel = windows().filterIsInstance<KeyboardTestPanel>().firstOrNull { it.isShown }
                    panel != null
                }
                val form = checkNotNull(panel)
                onMain {
                    assertEquals(4, form.tabs.childCount)
                    assertEquals(if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO,
                        form.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    if (fontScale > 1f) assertTrue(form.resources.configuration.fontScale >= 1.5f)
                }
                settledService(form.input)
                capture("keyboard-test-$suffix-text")
                for (kind in listOf(KeyboardTestPanel.Kind.PASSWORD, KeyboardTestPanel.Kind.PIN)) {
                    val typeButton = onMainValue { (0 until form.tabs.childCount).map(form.tabs::getChildAt)
                        .filterIsInstance<MaterialButton>().single { it.text == form.context.getString(kind.label) } }
                    tap(typeButton)
                    val privateService = settledService(form.input, kind.inputType)
                    onMain {
                        assertEquals(kind.inputType, checkNotNull(privateService.currentInputEditorInfo).inputType)
                        assertFalse(privateService.isStatisticsRecordingEnabled())
                        assertTrue(form.input.transformationMethod is PasswordTransformationMethod)
                        assertFalse(form.input.isSaveEnabled)
                    }
                    sendDigits(privateService)
                    await("The private test editor should receive digits") { form.input.text.toString() == "1234" }
                    val privateResult = stats.snapshot()
                    assertEquals(ordinary.totals.characters, privateResult.totals.characters)
                    assertEquals(ordinary.totals.keys, privateResult.totals.keys)
                    assertEquals(ordinary.words, privateResult.words)
                    capture("keyboard-test-$suffix-${kind.name.lowercase()}")
                }
                val closeButton = onMainValue { descendants(form).first { it.contentDescription == form.context.getString(R.string.keyboard_test_close) } }
                tap(closeButton)
                await("Closing the panel must clear its private input") { !form.isAttachedToWindow && form.input.text.isNullOrEmpty() }
                close(main)
            }
        } catch (error: Throwable) {
            runCatching { capture("failure") }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        } finally {
            try {
                onMain { activities.forEach(Activity::finish) }
                instrumentation.waitForIdleSync()
                if (createdProfile) withTimeout(30_000) { connection.runOnReady {
                    previousProfile?.let { setAddonSubConfig("rime", "profile-manager", profileCommand("switch", it)) }
                    setAddonSubConfig("rime", "profile-manager", profileCommand("delete", name))
                } }
            } finally {
                onMain {
                    stores.forEach { (service, original) ->
                        storeField.set(service, original.store)
                        layoutField.set(service, original.layout)
                        epochField.setLong(service, original.epoch)
                    }
                    prefs.candidates.mode.setValue(previousFloating)
                    prefs.keyboard.textKeyboardLayoutProfile.setValue(previousLayout)
                    prefs.internal.needNotifications.setValue(previousNotifications)
                }
                SettingsUiPreferences.save(context, previousUi)
                try { stats.close() } finally {
                    assertEquals(context.cacheDir.canonicalFile, temporary.parentFile)
                    temporary.deleteRecursively()
                    restoreSetting("system", "font_scale", previousFontScale)
                    restoreSetting("secure", "show_ime_with_hard_keyboard", previousHardwareIme)
                    if (previousIme.isNotBlank() && previousIme != "null") shell("ime set $previousIme")
                    if (!wasEnabled) shell("ime disable $component")
                    FcitxDaemon.disconnect(name)
                }
            }
        }
    }

    private fun settledService(editor: EditText, expectedType: Int? = null): FcitxInputMethodService {
        var service: FcitxInputMethodService? = null
        var generation = -1L
        var stableAt = SystemClock.uptimeMillis()
        await("The real IME should bind and display this editor") {
            val candidate = windows().filterIsInstance<InputView>().firstOrNull { it.isShown }?.service
            val next = connection.runImmediately { inputSession }
            if (next != generation) { generation = next; stableAt = SystemClock.uptimeMillis() }
            val active = candidate?.isInputViewShown == true && editor.hasFocus() &&
                (expectedType == null || candidate.currentInputEditorInfo?.inputType == expectedType) &&
                SystemClock.uptimeMillis() - stableAt >= 500
            if (active) {
                service = candidate
                if (!stores.containsKey(candidate)) stores[candidate!!] = ServiceStatsState(
                    storeField.get(candidate) as InputStats?, layoutField.get(candidate), epochField.getLong(candidate))
                storeField.set(candidate, stats)
            }
            active
        }
        return checkNotNull(service)
    }

    private suspend fun sendDigits(service: FcitxInputMethodService) {
        var job: Job? = null
        onMain { job = service.postFcitxKeyJob {
            for (digit in "1234") {
                sendKey(digit, KeyState.Virtual.state)
                sendKey(digit, KeyState.Virtual.state, up = true)
            }
        } }
        withTimeout(10_000) { checkNotNull(job).join() }
    }

    private fun tapSoftKey(label: String) {
        var key: KeyView? = null
        await("Missing real keyboard key $label") {
            key = windows().filterIsInstance<KeyView>().firstOrNull { it.statisticsKeyLabel == label && it.isShown && it.isEnabled }
            key != null
        }
        tap(checkNotNull(key))
    }
    private fun tap(view: View) {
        onMain { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true) }
        instrumentation.waitForIdleSync()
        val rect = onMainValue { Rect().also {
            assertTrue("Control is not visible", view.getLocalVisibleRect(it))
            val location = IntArray(2).also(view::getLocationOnScreen)
            it.offset(location[0], location[1])
        } }
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(now, now + index * 40L, action, rect.exactCenterX(), rect.exactCenterY(), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun <T : Activity> launch(type: Class<T>, intent: Intent = Intent(context, type)): T {
        val activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        activities += activity
        instrumentation.waitForIdleSync()
        return type.cast(activity)!!
    }
    private fun close(activity: Activity) { onMain { activity.finish() }; instrumentation.waitForIdleSync(); activities.remove(activity) }
    private fun profileCommand(action: String, profile: String) = RawConfig(arrayOf(RawConfig("Action", action), RawConfig("Name", profile)))
    private fun capture(suffix: String) {
        instrumentation.waitForIdleSync()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(context.getExternalFilesDir(null), "input-statistics-diagnostics").apply { mkdirs() }
            File(directory, "$suffix.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 25_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMainValue(condition)) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(40)
        }
        fail(message)
    }
    private fun windows() = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> onMainValue(block: () -> T): T {
        var value: T? = null
        onMain { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
    }
    private fun restoreSetting(table: String, key: String, value: String) {
        if (value.matches(Regex("-?\\d+(?:\\.\\d+)?"))) shell("settings put $table $key $value")
        else shell("settings delete $table $key")
    }
}
