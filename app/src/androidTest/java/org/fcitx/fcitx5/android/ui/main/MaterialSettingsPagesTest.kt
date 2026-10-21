/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
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
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.ui.common.ProgressBarDialogIndeterminate
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.common.SettingsSlider
import org.fcitx.fcitx5.android.ui.common.MaterialSpinner
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

    private fun page(type: Class<out Activity>, extras: Intent.() -> Unit = {}, check: (Activity) -> Unit) {
        val intent = Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(extras)
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            onMain { assertFalse(activity.isFinishing); check(activity) }
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

    @Test fun cropAndClipboardSettingsUseSameThemeInBothModes() {
        val image = File(context.cacheDir, "material-crop-fixture.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            modes { dark ->
                for (type in listOf(org.fcitx.fcitx5.android.clipboardsync.ui.ClipboardSyncSettingsActivity::class.java)) {
                    page(type) { themedToolbar(it, dark) }
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

    @Test fun clipboardEditorAndSharedDialogsUseMaterialComponents() {
        modes { dark ->
            page(ClipboardEditActivity::class.java) { activity ->
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

    @Test fun attachedMaterialMenuKeepsTextAndPasswordEditorsFocused() {
        val component = "${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        val previousIme = shell("settings get secure default_input_method")
        val wasEnabled = component in shell("ime list -s")
        val previousHardwareIme = shell("settings get secure show_ime_with_hard_keyboard")
        val prefs = org.fcitx.fcitx5.android.data.prefs.AppPrefs.getInstance()
        val previousFloating = prefs.candidates.mode.getValue()
        var host: Activity? = null
        var dialog: androidx.appcompat.app.AlertDialog? = null
        try {
            onMain { prefs.candidates.mode.setValue(org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode.Disabled) }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("ime enable $component")
            shell("ime set $component")
            val activity = instrumentation.startActivitySync(Intent(context, UpdateMirrorEditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            host = activity
            val editor = activity.findViewById<android.widget.EditText>(R.id.mirrorNameInput)
            val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            for (type in listOf(android.text.InputType.TYPE_CLASS_TEXT,
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
                onMain {
                    editor.inputType = type
                    editor.setText("1237") // Disposable test field, never user data.
                    editor.setSelection(editor.length())
                    editor.requestFocus()
                    imm.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                var service: org.fcitx.fcitx5.android.input.FcitxInputMethodService? = null
                awaitUi("IME must bind to editor type=$type") {
                    service = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
                        .filterIsInstance<org.fcitx.fcitx5.android.input.InputView>()
                        .firstOrNull { it.isShown }?.service
                    activity.hasWindowFocus() && editor.hasFocus() && service?.isInputViewShown == true &&
                        service?.currentInputEditorInfo?.inputType == type
                }
                Thread.sleep(800) // Let the initial editor/window binding settle.
                var focusLosses = 0
                val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                    if (!focused) focusLosses++
                }
                onMain {
                    activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                    val themed = SettingsUiPreferences.themedContext(service!!, SettingsUiPreferences.read(context))
                    dialog = MaterialAlertDialogBuilder(themed).setTitle("UI fixture")
                        .setItems(arrayOf("one", "two")) { _, _ -> }.create()
                    service!!.showDialog(dialog!!)
                }
                try {
                    Thread.sleep(800) // The reported focus/hide cycle occurs after showing the menu.
                    onMain {
                        assertTrue(dialog!!.isShowing)
                        assertTrue(materialShape(dialog!!.window!!.decorView.background))
                        val flags = dialog!!.window!!.attributes.flags
                        assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                        assertEquals(0, flags and android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                        assertEquals(0, focusLosses)
                        assertTrue(activity.hasWindowFocus())
                        assertTrue(editor.hasFocus())
                        assertTrue(service!!.isInputViewShown)
                        assertEquals("1237", editor.text.toString())
                    }
                    shell("input keyevent KEYCODE_BACK")
                    awaitUi("Back must close the attached menu and preserve the editor") {
                        dialog?.isShowing == false && activity.hasWindowFocus() && editor.hasFocus() &&
                            service?.isInputViewShown == true
                    }
                } finally {
                    onMain {
                        activity.window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
                        dialog?.dismiss()
                    }
                }
            }
        } finally {
            onMain { dialog?.dismiss(); host?.finish(); prefs.candidates.mode.setValue(previousFloating) }
            if (previousIme.isNotBlank() && previousIme != "null") shell("ime set $previousIme")
            if (!wasEnabled) shell("ime disable $component")
            if (previousHardwareIme == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $previousHardwareIme")
        }
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    private fun awaitUi(message: String, predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000
        do {
            var ready = false
            onMain { ready = predicate() }
            if (ready) return
            Thread.sleep(50)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        fail(message)
    }

    private fun capture(activity: Activity, suffix: String = "") {
        val mode = if (SettingsUiPreferences.isDark(activity)) "dark" else "light"
        val name = activity.javaClass.simpleName + if (suffix.isEmpty()) "" else "-$suffix"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/md3-settings-$name-$mode.png"
        )).use { it.readBytes() }
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
