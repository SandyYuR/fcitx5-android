/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.test.filters.SdkSuppress
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.ui.common.ProgressBarDialogIndeterminate
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.common.SettingsSlider
import org.fcitx.fcitx5.android.ui.common.MaterialSpinner
import org.fcitx.fcitx5.android.ui.main.settings.im.RimeImportActivity
import org.fcitx.fcitx5.android.ui.main.settings.im.RimeEngineManagerActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupSpecs
import org.fcitx.fcitx5.android.ui.setup.SetupActivity
import org.fcitx.fcitx5.android.ui.main.update.ReleaseInfo
import org.fcitx.fcitx5.android.ui.main.update.UpdateCheckActivity
import org.fcitx.fcitx5.android.ui.main.update.UpdateHostsSettingsActivity
import org.fcitx.fcitx5.android.ui.main.update.UpdateMirrorEditActivity
import org.fcitx.fcitx5.android.ui.main.update.UpdateMirrorListActivity
import org.fcitx.fcitx5.android.ui.main.update.UpdateRepository
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Actually launch the pages: an XML Toolbar/MaterialToolbar mismatch compiles successfully. */
@SdkSuppress(minSdkVersion = 29)
class MaterialSettingsPagesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var originalSelection: SettingsUiPreferences.Selection
    private val releaseField = UpdateRepository::class.java.getDeclaredField("cachedReleaseInfo").apply { isAccessible = true }
    private val releaseTimeField = UpdateRepository::class.java.getDeclaredField("cachedReleaseAt").apply { isAccessible = true }
    private var originalRelease: Any? = null
    private var originalReleaseTime = 0L

    @Before fun setup() {
        originalSelection = SettingsUiPreferences.read(context)
        originalRelease = releaseField.get(null)
        originalReleaseTime = releaseTimeField.getLong(null)
        // Exercise real onCreate/onResume without depending on GitHub or downloading an APK.
        releaseField.set(null, ReleaseInfo("v0.0.0-test", "UI fixture", "", emptyList()))
        releaseTimeField.setLong(null, System.currentTimeMillis())
    }

    @After fun restore() {
        SettingsUiPreferences.save(context, originalSelection)
        releaseField.set(null, originalRelease)
        releaseTimeField.setLong(null, originalReleaseTime)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun modes(block: (Boolean) -> Unit) {
        for (dark in listOf(false, true)) {
            SettingsUiPreferences.save(context, originalSelection.copy(
                mode = if (dark) SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
                followKeyboardTheme = false
            ))
            block(dark)
        }
    }

    private fun page(type: Class<out Activity>, extras: Intent.() -> Unit = {},
                     ready: (Activity) -> Boolean = { true }, check: (Activity) -> Unit) {
        val intent = Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(extras)
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            val deadline = android.os.SystemClock.uptimeMillis() + 8000
            var pageReady = false
            while (!pageReady && android.os.SystemClock.uptimeMillis() < deadline) {
                onMain { pageReady = ready(activity) }
                if (!pageReady) android.os.SystemClock.sleep(30)
            }
            onMain {
                assertFalse("${type.simpleName} unexpectedly closed", activity.isFinishing)
                assertTrue("${type.simpleName} did not finish loading its fixture", pageReady)
                check(activity)
            }
            capture(activity)
        } finally {
            onMain { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun themedToolbar(activity: Activity, dark: Boolean) {
        assertTrue(activity is SettingsUiActivity)
        assertTrue(activity.findViewById<View>(R.id.toolbar) is MaterialToolbar)
        val night = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        assertEquals(if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO, night)
    }

    @Test fun brandIconsKeepTransparentMarginsAndRenderInBothModes() = modes { dark ->
        val activity = instrumentation.startActivitySync(Intent(context, UpdateMirrorListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var dialog: androidx.appcompat.app.AlertDialog? = null
        try {
            onMain {
                // Rasterize the packaged foregrounds. A white source-image corner or an
                // opaque monochrome tile would violate the adaptive icon's safe circle.
                for (id in listOf(R.drawable.ic_launcher_foreground,
                    R.drawable.ic_launcher_foreground_debug,
                    R.drawable.ic_launcher_foreground_monochrome,
                    R.drawable.ic_launcher_foreground_monochrome_debug)) {
                    val pixels = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
                    try {
                        activity.getDrawable(id)!!.apply {
                            setBounds(0, 0, pixels.width, pixels.height)
                            draw(Canvas(pixels))
                        }
                        var ink = 0
                        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                            if (Color.alpha(pixels.getPixel(x, y)) == 0) continue
                            ink++
                            val dx = x + 0.5 - 216
                            val dy = y + 0.5 - 216
                            assertTrue("Foreground escaped the 66dp safe circle: ${resourcesName(id)}",
                                dx * dx + dy * dy <= 132 * 132)
                        }
                        assertTrue("Missing or opaque icon: ${resourcesName(id)}", ink in 9000..52000)
                        if (id == R.drawable.ic_launcher_foreground_monochrome ||
                            id == R.drawable.ic_launcher_foreground_monochrome_debug) {
                            // Day and night intentionally have different opacity roles. Check
                            // interior pixels so the light body cannot leak into night resources.
                            val body = Color.alpha(pixels.getPixel(190, 248))
                            val foot = Color.alpha(pixels.getPixel(131, 292))
                            assertTrue("Wrong body opacity in ${if (dark) "dark" else "light"} mode",
                                body in if (dark) 254..255 else 24..27)
                            assertTrue("Wrong foot opacity in ${if (dark) "dark" else "light"} mode",
                                foot in if (dark) 164..167 else 151..155)
                        }
                    } finally { pixels.recycle() }
                }

                val release = activity.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
                val debug = activity.getDrawable(R.mipmap.ic_launcher_debug) as AdaptiveIconDrawable
                val mono = if (Build.VERSION.SDK_INT >= 33) checkNotNull(release.monochrome)
                    else checkNotNull(activity.getDrawable(R.drawable.ic_launcher_foreground_monochrome))
                val debugMono = if (Build.VERSION.SDK_INT >= 33) checkNotNull(debug.monochrome)
                    else checkNotNull(activity.getDrawable(R.drawable.ic_launcher_foreground_monochrome_debug))
                val onContainer = activity.settingsColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
                val container = activity.settingsColor(com.google.android.material.R.attr.colorSecondaryContainer)
                assertTrue(ColorUtils.calculateContrast(onContainer, container) >= 3.0)
                fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
                val sheet = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(8), dp(24), dp(24))
                }
                fun row(label: String, icons: List<Drawable>, size: Int = 64) {
                    sheet.addView(TextView(activity).apply {
                        text = label
                        setPadding(0, dp(16), 0, dp(8))
                        setTextColor(activity.settingsColor(com.google.android.material.R.attr.colorOnSurface))
                    })
                    sheet.addView(LinearLayout(activity).apply {
                        for (icon in icons) addView(ImageView(activity).apply {
                            setImageDrawable(icon)
                        }, LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(16) })
                    })
                }
                row("Launcher · Release / Debug / installed", listOf(release, debug,
                    activity.packageManager.getApplicationIcon(activity.packageName)))
                // Android 12+ system palette roles, as used by themed launchers.
                val systemBackground = if (Build.VERSION.SDK_INT >= 31) activity.getColor(
                    if (dark) android.R.color.system_accent1_800 else android.R.color.system_accent1_100
                ) else container
                val systemForeground = if (Build.VERSION.SDK_INT >= 31) activity.getColor(
                    if (dark) android.R.color.system_accent1_100 else android.R.color.system_accent1_800
                ) else onContainer
                row("Monet · Release / Debug", listOf(mono, debugMono).map {
                    AdaptiveIconDrawable(ColorDrawable(systemBackground), it.mutate().apply { setTint(systemForeground) })
                })
                row("In-app · 24dp", listOf(activity.getDrawable(R.drawable.ic_brand_goose)!!.mutate().apply {
                    setTint(activity.settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                }), 24)
                dialog = MaterialAlertDialogBuilder(activity).setTitle("中州鹅 · 图标")
                    .setView(sheet).show()
            }
            instrumentation.waitForIdleSync()
            capture(activity, "brand-icons")
        } finally {
            onMain { dialog?.dismiss(); activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun resourcesName(id: Int) = context.resources.getResourceEntryName(id)

    @Test fun setupAndAboutShowTheBrandIconInBothModes() {
        var needNotifications by AppPrefs.getInstance().internal.needNotifications
        val originalNeedNotifications = needNotifications
        try {
            // Keep the notification explanation from obscuring this disposable UI fixture.
            needNotifications = false
            modes { dark ->
                page(SetupActivity::class.java, ready = {
                    it.findViewById<ImageView>(R.id.page_icon)?.isShown == true
                }) { activity ->
                    assertEquals(dark, SettingsUiPreferences.isDark(activity))
                    assertNotNull(activity.findViewById<ImageView>(R.id.page_icon).drawable)
                }
                page(MainActivity::class.java, extras = {
                    action = Intent.ACTION_RUN
                    putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.About)
                }, ready = { activity ->
                    descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == activity.getString(R.string.direct_rime_about_description)
                    }
                }) { activity ->
                    assertEquals(dark, SettingsUiPreferences.isDark(activity))
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<ImageView>().any {
                        it.id == android.R.id.icon && it.isShown && it.drawable != null
                    })
                }
            }
        } finally { needNotifications = originalNeedNotifications }
    }

    @Test fun updateOverflowMenuOpensMirrorManagement() {
        val monitor = instrumentation.addMonitor(UpdateMirrorListActivity::class.java.name, null, false)
        val parent = instrumentation.startActivitySync(Intent(context, UpdateCheckActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var child: Activity? = null
        try {
            instrumentation.waitForIdleSync()
            onMain {
                val menu = android.widget.PopupMenu(parent, View(parent)).menu
                parent.onCreateOptionsMenu(menu)
                val item = menu.findItem(R.id.action_update_mirrors)
                assertNotNull(item)
                assertTrue(item.isVisible)
                assertTrue(parent.onOptionsItemSelected(item))
            }
            child = instrumentation.waitForMonitorWithTimeout(monitor, 30_000)
            assertTrue(child is UpdateMirrorListActivity)
            instrumentation.waitForIdleSync()
            onMain { assertTrue(child!!.findViewById<View>(R.id.addMirrorRuleButton).isShown) }
            capture(child!!)
        } finally {
            instrumentation.removeMonitor(monitor)
            onMain { child?.finish(); parent.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun allUpdaterPagesLaunchWithMaterialControlsInBothModes() = modes { dark ->
        for (type in listOf(UpdateCheckActivity::class.java, UpdateHostsSettingsActivity::class.java,
            UpdateMirrorEditActivity::class.java, UpdateMirrorListActivity::class.java)) {
            page(type) { activity ->
                themedToolbar(activity, dark)
                when (activity) {
                    is UpdateCheckActivity -> {
                        val row = activity.layoutInflater.inflate(R.layout.item_update_asset, null)
                        assertTrue(row.findViewById<View>(R.id.assetProgress) is LinearProgressIndicator)
                    }
                    is UpdateHostsSettingsActivity -> {
                        assertTrue(activity.findViewById<View>(R.id.hostsEnableSwitch) is MaterialSwitch)
                        assertTrue(activity.findViewById<View>(R.id.hostsUpdateButton) is MaterialButton)
                        assertTrue(descendants(activity.window.decorView).any { it is TextInputLayout })
                    }
                    is UpdateMirrorEditActivity -> assertEquals(3,
                        descendants(activity.window.decorView).count { it is TextInputLayout })
                    is UpdateMirrorListActivity -> {
                        val add = activity.findViewById<View>(R.id.addMirrorRuleButton)
                        val background = activity.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer)
                        val pixels = Bitmap.createBitmap(add.width, add.height, Bitmap.Config.ARGB_8888)
                        try {
                            pixels.eraseColor(background)
                            add.draw(Canvas(pixels))
                            assertTrue("Add icon must be visible in both modes", ColorUtils.calculateContrast(
                                pixels.getPixel(add.width / 2, add.height / 2), background) >= 3.0)
                        } finally { pixels.recycle() }
                    }
                    else -> Unit
                }
            }
        }
    }

    @Test fun logAndCrashPagesLaunchWithMaterialThemeInBothModes() = modes { dark ->
        page(LogActivity::class.java) { themedToolbar(it, dark) }
        page(LogActivity::class.java, {
            putExtra(LogActivity.FROM_CRASH, true)
            putExtra(LogActivity.CRASH_STACK_TRACE, "UI smoke fixture")
        }) { activity ->
            themedToolbar(activity, dark)
            val window = WindowInspector.getGlobalWindowViews().first { root ->
                root !== activity.window.decorView && root.findViewById<View>(android.R.id.button1)?.isShown == true
            }
            assertTrue(materialShape(window.background))
            assertTrue(window.findViewById<View>(android.R.id.button1) is MaterialButton)
            window.findViewById<View>(android.R.id.button1).performClick()
        }
    }

    @Test fun importCropAndClipboardSettingsUseSameThemeInBothModes() {
        val image = File(context.cacheDir, "material-crop-fixture.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            modes { dark ->
                for (type in listOf(RimeImportActivity::class.java,
                    org.fcitx.fcitx5.android.clipboardsync.ui.ClipboardSyncSettingsActivity::class.java)) {
                    page(type) { themedToolbar(it, dark) }
                }
                page(RimeEngineManagerActivity::class.java, ready = { activity ->
                    descendants(activity.window.decorView).filterIsInstance<MaterialButton>().any {
                        it.text == activity.getString(R.string.rime_engine_import_so) &&
                            it.height >= (48 * activity.resources.displayMetrics.density).toInt()
                    }
                }) { activity ->
                    themedToolbar(activity, dark)
                    val import = descendants(activity.window.decorView).filterIsInstance<MaterialButton>()
                        .first { it.text == activity.getString(R.string.rime_engine_import_so) }
                    assertTrue(import.height >= (48 * activity.resources.displayMetrics.density).toInt())
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text == activity.getString(R.string.rime_engine_variants_description)
                    })
                }
                page(CropImageActivity::class.java, {
                    putExtra(CropImageActivity.CROP_OPTIONS,
                        CropImageActivity.CropOption.Edit(64, 64, Uri.fromFile(image)))
                }) { activity ->
                    assertTrue(activity is SettingsUiActivity)
                    assertTrue(descendants(activity.window.decorView).any { it is MaterialToolbar })
                }
            }
        } finally { image.delete() }
    }

    @Test fun engineManagerToolbarBackClosesPage() {
        val activity = instrumentation.startActivitySync(Intent(context, RimeEngineManagerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var back: ImageButton
            val readyDeadline = SystemClock.uptimeMillis() + 8000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < readyDeadline) {
                instrumentation.waitForIdleSync()
                onMain {
                    val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
                    val candidate = (0 until toolbar.childCount).map(toolbar::getChildAt)
                        .filterIsInstance<ImageButton>().firstOrNull()
                    if (candidate != null && candidate.isShown && candidate.width > 0) {
                        back = candidate
                        ready = true
                    }
                }
                if (!ready) SystemClock.sleep(30)
            }
            assertTrue("Engine manager navigation button did not become tappable", ready)
            tap(back)
            val finishDeadline = SystemClock.uptimeMillis() + 5000
            while (!activity.isFinishing && !activity.isDestroyed &&
                SystemClock.uptimeMillis() < finishDeadline) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(30)
            }
            assertTrue("Toolbar back did not close the engine manager",
                activity.isFinishing || activity.isDestroyed)
        } finally {
            onMain { if (!activity.isFinishing && !activity.isDestroyed) activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun rimeEngineEntrySummaryStaysOnOneLine() {
        page(MainActivity::class.java, extras = {
            action = Intent.ACTION_RUN
            putExtra(MainActivity.EXTRA_SETTINGS_ROUTE,
                SettingsRoute.SettingsGroup(SettingsGroupSpecs.ID_INPUT))
        }, ready = { activity ->
            descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                it.isShown && it.height > 0 &&
                    it.text == activity.getString(R.string.rime_engine_variants_summary)
            }
        }) { activity ->
            val summary = descendants(activity.window.decorView).filterIsInstance<TextView>().single {
                it.isShown && it.text == activity.getString(R.string.rime_engine_variants_summary)
            }
            assertEquals(1, summary.maxLines)
            assertEquals(android.text.TextUtils.TruncateAt.END, summary.ellipsize)
            assertEquals(1, summary.lineCount)
        }
    }

    @Test fun engineManagerDestroyRemovesOnlyItsStagingDirectory() {
        lateinit var manager: Activity
        lateinit var ownedStagingFile: File
        page(RimeEngineManagerActivity::class.java, ready = { activity ->
            descendants(activity.window.decorView).filterIsInstance<MaterialButton>().any {
                it.text == activity.getString(R.string.rime_engine_import_so)
            }
        }) { activity ->
            manager = activity
            val tokenField = RimeEngineManagerActivity::class.java
                .getDeclaredField("stagingToken").apply { isAccessible = true }
            val token = tokenField.get(activity) as String
            ownedStagingFile = File(activity.codeCacheDir,
                "rime-engine-import/$token/pending.so")
            assertTrue(ownedStagingFile.parentFile!!.mkdirs())
            ownedStagingFile.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            RimeEngineManagerActivity::class.java.getDeclaredField("pendingImport").apply {
                isAccessible = true
                set(activity, ownedStagingFile)
            }
            assertTrue(ownedStagingFile.isFile)
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (ownedStagingFile.exists() && android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(30)
        }
        assertTrue("Engine manager did not reach onDestroy", manager.isDestroyed)
        assertFalse("Destroyed engine manager left its staged payload", ownedStagingFile.exists())
        assertFalse("Destroyed engine manager left its token directory", ownedStagingFile.parentFile!!.exists())
    }

    @Test fun clipboardEditorAndSharedDialogsUseMaterialComponents() {
        val fixture = runBlocking {
            checkNotNull(ClipboardManager.importLocalEntry("Material UI clipboard fixture", notifyListeners = false))
        }
        try {
        modes { dark ->
            page(ClipboardEditActivity::class.java,
                extras = { putExtra(ClipboardEditActivity.ENTRY_ID, fixture.id) },
                ready = { it.findViewById<TextView>(R.id.clipboard_edit_text)?.text?.toString() == fixture.text }
            ) { activity ->
                assertTrue(activity is SettingsUiActivity)
                assertEquals(dark, SettingsUiPreferences.isDark(activity))
                for (id in listOf(R.id.clipboard_edit_copy, R.id.clipboard_edit_cancel, R.id.clipboard_edit_ok)) {
                    assertTrue(activity.findViewById<View>(id) is MaterialButton)
                }
                assertTrue(descendants(activity.window.decorView).any { it is TextInputLayout })
                assertTrue(materialShape(activity.window.decorView.background))
            }
            page(org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.TextKeyboardLayoutProfilePickerActivity::class.java) {
                assertTrue(it is SettingsUiActivity)
                assertEquals(dark, SettingsUiPreferences.isDark(it))
                assertTrue(WindowInspector.getGlobalWindowViews().any { root ->
                    root !== it.window.decorView && materialShape(root.background)
                })
            }
            page(UpdateMirrorListActivity::class.java) { activity ->
                themedToolbar(activity, dark)
                val dialog = activity.ProgressBarDialogIndeterminate(R.string.loading).setMessage("Fixture progress").show()
                try {
                    assertTrue(materialShape(dialog.window!!.decorView.background))
                    assertTrue(dialog.isShowing)
                } finally { dialog.dismiss() }
                // The clipboard-sync forms also inflate in the selected dialog theme.
                for (layout in listOf(R.layout.dialog_storage_location, R.layout.dialog_server_profile,
                    R.layout.dialog_sync_account, R.layout.dialog_test_push)) {
                    assertTrue(descendants(activity.layoutInflater.inflate(layout, null)).any { it is TextInputLayout })
                }
            }
        }
        } finally { runBlocking { ClipboardManager.delete(fixture.id) } }
    }

    @Test fun materialSlidersAndStyledSpinnerKeepEditorSelectionAndProgressCallbacks() = modes { _ ->
        val activity = instrumentation.startActivitySync(Intent(context, UpdateMirrorListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var spinner: MaterialSpinner
        lateinit var slider: SettingsSlider
        var dialog: androidx.appcompat.app.AlertDialog? = null
        var userChange = false
        try {
            onMain {
                spinner = MaterialSpinner(activity).apply {
                    adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("one", "two", "three"))
                    setSelection(1)
                }
                slider = SettingsSlider(activity).apply {
                    max = 40
                    progress = 20
                    setOnSeekBarChangeListener(object : SettingsSlider.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit
                        override fun onStopTrackingTouch(seekBar: SettingsSlider) = Unit
                        override fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean) {
                            userChange = userChange || fromUser
                        }
                    })
                }
                val content = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(spinner, LinearLayout.LayoutParams(-1, -2))
                    addView(slider, LinearLayout.LayoutParams(-1, -2))
                }
                dialog = MaterialAlertDialogBuilder(activity).setView(content).show()
            }
            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(1, spinner.selectedItemPosition)
                val now = android.os.SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now, action, slider.width * 0.75f, slider.height * 0.5f, 0)
                    slider.dispatchTouchEvent(event)
                    event.recycle()
                }
                assertTrue(userChange)
                assertTrue(slider.progress > 20)
                spinner.performClick()
            }
            instrumentation.waitForIdleSync()
            onMain {
                val popup = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
                    .filterIsInstance<ListView>().first { it.isShown && it.adapter?.count == 3 }
                popup.performItemClick(popup.getChildAt(2), 2, popup.adapter.getItemId(2))
            }
            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(2, spinner.selectedItemPosition)
                // Check rendered pixels after focus/popup state changes, not only the tint API.
                val pixels = Bitmap.createBitmap(spinner.width, spinner.height, Bitmap.Config.ARGB_8888)
                try {
                    pixels.eraseColor(spinner.context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
                    spinner.draw(Canvas(pixels))
                    val inset = (24 * spinner.resources.displayMetrics.density).toInt()
                    val arrowX = if (spinner.layoutDirection == View.LAYOUT_DIRECTION_RTL) inset else spinner.width - inset
                    val foreground = pixels.getPixel(arrowX, spinner.height / 2)
                    val background = pixels.getPixel(spinner.width / 2, spinner.height / 4)
                    assertTrue("Dropdown indicator must contrast with the field in both modes",
                        ColorUtils.calculateContrast(foreground, background) >= 3.0)
                } finally { pixels.recycle() }
            }
            capture(activity, "controls")
        } finally {
            onMain { dialog?.dismiss(); activity.finish() }
        }
    }

    private fun capture(activity: Activity, suffix: String = "") {
        val mode = if (SettingsUiPreferences.isDark(activity)) "dark" else "light"
        val name = activity.javaClass.simpleName + if (suffix.isEmpty()) "" else "-$suffix"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/direct-frontend-md3-$name-$mode.png"
        )).use { it.readBytes() }
    }

    private fun tap(view: View) {
        val bounds = Rect()
        onMain {
            assertTrue("The control must be visible before tapping", view.getLocalVisibleRect(bounds))
            val location = IntArray(2).also(view::getLocationOnScreen)
            bounds.offset(location[0], location[1])
        }
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(now, now + index * 30L, action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun materialShape(drawable: Drawable?): Boolean = when (drawable) {
        is MaterialShapeDrawable -> true
        is InsetDrawable -> materialShape(drawable.drawable)
        else -> false
    }
}
