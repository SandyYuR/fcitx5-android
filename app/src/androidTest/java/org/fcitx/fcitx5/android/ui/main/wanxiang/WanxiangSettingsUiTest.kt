/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.WanxiangAdvancedFiles
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.ui.common.MaterialSpinner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/** Real windows and the production native configuration path, using disposable profiles only. */
@SdkSuppress(minSdkVersion = 29)
class WanxiangSettingsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val connectionName = "WanxiangSettingsUiTest-${UUID.randomUUID()}"
    private val connection by lazy { FcitxDaemon.connect(connectionName) }
    private lateinit var originalUi: SettingsUiPreferences.Selection
    private var originalSelection: String? = null
    private var originalFontScale = "1.0"
    private var prepared = false

    @Before fun prepare() {
        assertTrue("This test only runs against the isolated Debug application", BuildConfig.DEBUG && context.packageName.endsWith(".debug"))
        assertTrue("Do not interfere with an active update", WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WanxiangUpdates.WORK).get().none { !it.state.isFinished })
        originalUi = SettingsUiPreferences.read(context)
        originalSelection = WanxiangUpdates.preferences(context).getString("selection", null)
        originalFontScale = shell("settings get system font_scale")
        prepared = true
        WanxiangUpdates.preferences(context).edit().remove("selection").commit()
    }

    @After fun restore() {
        try {
            if (!prepared) return
            SettingsUiPreferences.save(context, originalUi)
            val edit = WanxiangUpdates.preferences(context).edit()
            originalSelection?.let { edit.putString("selection", it) } ?: edit.remove("selection")
            edit.commit()
            originalFontScale.toFloatOrNull()?.let { shell("settings put system font_scale $it") }
                ?: shell("settings delete system font_scale")
        } finally { FcitxDaemon.disconnect(connectionName) }
    }

    @Test fun updaterShowsItsScopeAndOnlyRelevantOptionsInBothThemes() = withProfile(fixture = false) {
        setFontScale(1f)
        for (dark in listOf(false, true)) {
            theme(dark)
            page(WanxiangUpdateActivity::class.java, "updater-${mode(dark)}", ready = { form(it).update.isEnabled }) { activity ->
                onMain {
                    assertTrue(texts(activity).any { it.text == activity.getString(R.string.wanxiang_only_title) && it.isShown })
                    spinner(activity, R.string.wanxiang_scope).setSelection(WanxiangSelection.Scope.ALL.ordinal)
                }
                await("Full update choices should be visible") {
                    spinner(activity, R.string.wanxiang_scheme).isShown && spinner(activity, R.string.wanxiang_channel).isShown
                }
                onMain { assertVisibleAction(activity, form(activity).update) }
                capture("updater-${mode(dark)}-all")
                onMain { spinner(activity, R.string.wanxiang_scope).setSelection(WanxiangSelection.Scope.MODEL.ordinal) }
                await("Model updates must hide irrelevant scheme and release controls") {
                    !spinner(activity, R.string.wanxiang_scheme).isShown && !spinner(activity, R.string.wanxiang_channel).isShown
                }
                onMain {
                    assertEquals(WanxiangSelection.Scope.MODEL, form(activity).selection().scope)
                    assertVisibleAction(activity, form(activity).update)
                    assertFalse(ancestors(form(activity).update).any { it is ScrollView })
                }
                capture("updater-${mode(dark)}-model")
                onMain { back(activity).performClick() }
                await("Toolbar back should close the update page") { activity.isFinishing }
            }
        }
    }

    @Test fun narrowLargeTextPagesKeepActionsReachableAndNativeReadsPreserveTheFixture() = withProfile(fixture = true) { directory ->
        setFontScale(1.6f)
        val before = configurationSnapshot(directory)
        for (dark in listOf(false, true)) {
            theme(dark)
            page(WanxiangUpdateActivity::class.java, "updater-narrow-${mode(dark)}", narrow = true,
                ready = { form(it).update.isEnabled }) { activity ->
                onMain {
                    assertTrue("The Activity must use the actual large font setting", activity.resources.configuration.fontScale >= 1.5f)
                    spinner(activity, R.string.wanxiang_scope).setSelection(WanxiangSelection.Scope.MODEL.ordinal)
                }
                await("Model choices should settle") { !spinner(activity, R.string.wanxiang_scheme).isShown }
                onMain { assertVisibleAction(activity, form(activity).update) }
            }
            page(WanxiangAdvancedActivity::class.java, "advanced-narrow-${mode(dark)}", narrow = true,
                ready = { !model(it).state.value.busy }) { activity ->
                onMain {
                    assertNull(model(activity).state.value.error)
                    assertTrue(model(activity).state.value.compiled)
                    assertTrue(texts(activity).any { it.text == activity.getString(R.string.wanxiang_advanced_scope_hint) && it.isShown })
                    assertVisibleAction(activity, button(activity, R.string.wanxiang_advanced_save))
                }
                capture("advanced-narrow-${mode(dark)}-common")
                var preview: TextView? = null
                onMain {
                    preview = texts(activity).first { it.text.toString() == "3" }
                    assertFalse(preview!!.isTextSelectable)
                    preview!!.requestRectangleOnScreen(Rect(0, 0, preview!!.width, preview!!.height), true)
                }
                instrumentation.waitForIdleSync()
                tap(checkNotNull(preview))
                await("Tapping a configuration value should open its editor") { model(activity).pending != null }
                capture("advanced-narrow-${mode(dark)}-editor")
                onMain {
                    val cancel = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
                        .filterIsInstance<TextView>().last { it.id == android.R.id.button2 && it.isShown }
                    cancel.performClick()
                }
                await("Cancelling the editor should keep the page draft clean") { model(activity).pending == null }
                onMain { assertEquals(0, model(activity).state.value.dirty) }
            }
        }
        assertEquals("Opening and cancelling settings must not rebuild or edit source/deployed files", before, configurationSnapshot(directory))
    }

    @Test fun anEmptyProfileExplainsTheMissingSchemeAndOffersRetryAndBack() = withProfile(fixture = false) {
        setFontScale(1f)
        theme(false)
        page(WanxiangAdvancedActivity::class.java, "advanced-empty", ready = { !model(it).state.value.busy }) { activity ->
            onMain {
                assertNotNull(model(activity).state.value.error)
                assertTrue(texts(activity).any { it.text == activity.getString(R.string.wanxiang_advanced_scope_hint) })
                assertFalse(button(activity, R.string.wanxiang_advanced_save).isEnabled)
                assertTrue(button(activity, R.string.wanxiang_advanced_reload).isEnabled)
            }
            capture("advanced-empty-retry")
            onMain { button(activity, R.string.wanxiang_advanced_reload).performClick() }
            await("Retry should return to an actionable missing-scheme state") { !model(activity).state.value.busy }
            onMain {
                assertNotNull(model(activity).state.value.error)
                assertTrue(button(activity, R.string.wanxiang_advanced_reload).isEnabled)
                back(activity).performClick()
            }
            await("Toolbar back must remain available after a loading error") { activity.isFinishing }
        }
    }

    private fun withProfile(fixture: Boolean, body: (File) -> Unit) {
        val name = "wanxiang-ui-${UUID.randomUUID()}"
        var previous: String? = null
        var created = false
        try {
            runBlocking { withTimeout(30_000) { connection.runOnReady {
                previous = getAddonSubConfig("rime", "profile-manager")["Current"].value
                setAddonSubConfig("rime", "profile-manager", command("create", name))
                created = true
            } } }
            val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "data/$name").canonicalFile
            assertEquals(File(context.getExternalFilesDir(null) ?: context.filesDir, "data").canonicalFile, directory.parentFile)
            assertTrue(directory.isDirectory)
            if (fixture) {
                instrumentation.context.assets.list("smoke")!!.forEach { file ->
                    instrumentation.context.assets.open("smoke/$file").use { input -> File(directory, file).outputStream().use { input.copyTo(it) } }
                }
                File(directory, "wanxiang.schema.yaml").writeText(File(directory, "direct_smoke.schema.yaml").readText()
                    .replace("schema_id: direct_smoke", "schema_id: wanxiang"))
                val defaults = File(directory, "default.yaml")
                defaults.writeText(defaults.readText().replace("schema: direct_smoke", "schema: wanxiang"))
            }
            runBlocking { withTimeout(30_000) { connection.runOnReady {
                setAddonSubConfig("rime", "profile-manager", command("switch", name))
            } } }
            body(directory)
        } finally {
            if (created) runBlocking { withTimeout(30_000) { connection.runOnReady {
                previous?.let { setAddonSubConfig("rime", "profile-manager", command("switch", it)) }
                setAddonSubConfig("rime", "profile-manager", command("delete", name))
            } } }
        }
    }

    private fun command(action: String, name: String) = RawConfig(arrayOf(RawConfig("Action", action), RawConfig("Name", name)))
    private fun configurationSnapshot(directory: File) = listOf("default.yaml", "wanxiang.schema.yaml", "build/default.yaml", "build/wanxiang.schema.yaml")
        .associateWith { name -> File(directory, name).let { file ->
            assertTrue("Missing deployed fixture: $name", file.isFile)
            WanxiangAdvancedFiles.hash(file) to file.lastModified()
        } }

    private fun page(type: Class<out Activity>, label: String, narrow: Boolean = false,
                     ready: (Activity) -> Boolean, body: (Activity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            if (narrow) onMain { activity.window.setLayout(dp(activity, 320), ViewGroup.LayoutParams.MATCH_PARENT) }
            instrumentation.waitForIdleSync()
            await("${type.simpleName} did not finish loading") { ready(activity) }
            onMain { assertFalse(activity.isFinishing) }
            body(activity)
            if (!activity.isFinishing) capture(label)
        } catch (error: Throwable) {
            runCatching { capture("$label-failure") }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        } finally {
            onMain { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun form(activity: Activity) = descendants(activity.window.decorView).filterIsInstance<WanxiangUpdateForm>().single()
    private fun model(activity: Activity) = ViewModelProvider(activity as WanxiangAdvancedActivity)[WanxiangAdvancedViewModel::class.java]
    private fun texts(activity: Activity) = descendants(activity.window.decorView).filterIsInstance<TextView>()
    private fun spinner(activity: Activity, label: Int) = descendants(activity.window.decorView).filterIsInstance<MaterialSpinner>()
        .single { it.contentDescription == activity.getString(label) }
    private fun button(activity: Activity, label: Int) = descendants(activity.window.decorView).filterIsInstance<MaterialButton>()
        .single { it.text == activity.getString(label) }
    private fun back(activity: Activity): ImageButton {
        val toolbar = descendants(activity.window.decorView).filterIsInstance<MaterialToolbar>().single()
        return (0 until toolbar.childCount).map(toolbar::getChildAt).filterIsInstance<ImageButton>().first()
    }

    private fun assertVisibleAction(activity: Activity, action: View) {
        val root = activity.window.decorView as ViewGroup
        val rect = Rect(); action.getDrawingRect(rect); root.offsetDescendantRectToMyCoords(action, rect)
        assertTrue("Action escaped the real window: $rect of ${root.width} x ${root.height}",
            action.isShown && rect.left >= 0 && rect.right <= root.width && rect.top >= 0 && rect.bottom <= root.height)
        assertTrue("Action touch height is below 48 dp", action.height >= dp(activity, 48))
    }

    private fun tap(view: View) {
        val bounds = Rect()
        onMain { assertTrue("The setting must be visible before tapping", view.getGlobalVisibleRect(bounds)) }
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(now, now + index * 30L, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Could not capture the device window" }
        try {
            val directory = File(context.getExternalFilesDir(null), "wanxiang-ui-diagnostics").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        var matched = false
        while (!matched && SystemClock.uptimeMillis() < deadline) {
            onMain { matched = condition() }
            if (!matched) SystemClock.sleep(40)
        }
        assertTrue(message, matched)
        instrumentation.waitForIdleSync()
    }
    private fun theme(dark: Boolean) = SettingsUiPreferences.save(context, originalUi.copy(
        mode = if (dark) SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
        palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false))
    private fun mode(dark: Boolean) = if (dark) "dark" else "light"
    private fun setFontScale(value: Float) { shell("settings put system font_scale $value"); instrumentation.waitForIdleSync() }
    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { input -> input.readText().trim() }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun ancestors(view: View) = generateSequence(view.parent) { it.parent }
}
