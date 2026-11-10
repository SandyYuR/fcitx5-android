/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.config.ConfigProvider
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.ImageAltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.picker.SymbolPanelStyle
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.ui.main.settings.im.RimeImportActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/** Real IME windows; fixtures stay in memory and all changed preferences are restored. */
@SdkSuppress(minSdkVersion = 29)
class ThemeLabelsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun selectedTabColorsAndDualLabelBoundsInTheRealIme() {
        check(BuildConfig.DEBUG && context.packageName.endsWith(".debug"))
        val ime = "${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        assertEquals("Enable the existing Debug IME before this test", ime,
            shell("settings get secure default_input_method"))
        val prefs = AppPrefs.getInstance()
        val themePrefs = ThemeManager.prefs
        val changed = listOf(themePrefs.normalModeTheme, themePrefs.followSystemDayNightTheme,
            themePrefs.moveMainTextForAltLabel, themePrefs.keyBorder,
            prefs.keyboard.keyboardHeightPercent, prefs.keyboard.keyboardHeightPercentLandscape,
            prefs.symbols.symbolPanelStyle)
        val previousValues = changed.associateWith { it.sharedPreferences.all[it.key] }
        val previousThemes = onMainValue { ThemeManager.getAllThemes().filterIsInstance<Theme.Custom>() }
        val previousProvider = ConfigProviders.provider
        val previousHardwareIme = shell("settings get secure show_ime_with_hard_keyboard")
        val suffix = UUID.randomUUID().toString()
        val light = ThemePreset.PixelLight.deriveCustomNoBackground("labels-light-$suffix").copy(
            genericActiveBackgroundColor = Color.WHITE,
            genericActiveForegroundColor = 0xff08744e.toInt(),
            accentKeyTextColor = Color.WHITE)
        val dark = ThemePreset.PixelDark.deriveCustomNoBackground("labels-dark-$suffix").copy(
            genericActiveBackgroundColor = 0xff173b32.toInt(),
            genericActiveForegroundColor = 0xff91f4cf.toInt(),
            accentKeyTextColor = Color.WHITE)
        val layout = fixtureLayout()
        var activity: Activity? = null
        try {
            onMain {
                ConfigProviders.provider = object : ConfigProvider by previousProvider {
                    override fun textKeyboardLayoutFile(): File? = null
                    override fun textKeyboardLayoutJson() = layout
                }
                ThemeManager.refreshThemes(previousThemes + listOf(light, dark))
                themePrefs.followSystemDayNightTheme.setValue(false)
                themePrefs.keyBorder.setValue(true)
                ThemeManager.setNormalModeTheme(light)
            }
            shell("settings put secure show_ime_with_hard_keyboard 1")
            activity = instrumentation.startActivitySync(Intent(context, RimeImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val editor = activity.findViewById<EditText>(R.id.direct_rime_test_editor)
            onMain {
                editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                editor.imeOptions = editor.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                editor.setText("")
                editor.requestFocus()
            }
            await("The test editor must own window focus") { editor.hasWindowFocus() && editor.isShown }
            tap(editor)
            onMain {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            await("The real input method must attach") { inputView() != null }

            for ((name, theme) in listOf("light" to light, "dark" to dark)) {
                onMain { ThemeManager.setNormalModeTheme(theme) }
                await("The real keyboard must apply $name") {
                    inputView()?.let { view -> keys(view).any { it.theme.name == theme.name } } == true
                }
                val fixedBounds = mutableMapOf<Int, List<Rect>>()
                for (move in listOf(false, true)) {
                    for (height in listOf(26, 36)) {
                        onMain {
                            themePrefs.moveMainTextForAltLabel.setValue(move)
                            prefs.keyboard.keyboardHeightPercent.setValue(height)
                            prefs.keyboard.keyboardHeightPercentLandscape.setValue(height)
                        }
                        await("The dual-label fixture must attach") {
                            inputView()?.let { view ->
                                keys(view).filterIsInstance<AltTextKeyView>().any {
                                    it.mainText.text.toString().equals("Q", ignoreCase = true) && it.altText1.text.toString() == "~"
                                }
                            } == true
                        }
                        settle()
                        onMain {
                            val visible = keys(checkNotNull(inputView())).toList()
                            val text = visible.filterIsInstance<AltTextKeyView>().first {
                                it.mainText.text.toString().equals("Q", ignoreCase = true)
                            }
                            val image = visible.filterIsInstance<ImageAltTextKeyView>().first()
                            assertLabelsInside(text, listOf(text.altText, text.altText1))
                            assertLabelsInside(image, listOf(image.altText, image.altText1))
                            val bounds = listOf(text.mainText, text.altText, text.altText1,
                                image.img, image.altText, image.altText1).map { Rect(it.left, it.top, it.right, it.bottom) }
                            if (move) assertEquals("Dual labels must not move when the preference changes",
                                fixedBounds[height], bounds)
                            else fixedBounds[height] = bounds
                        }
                        capture("keyboard-$name-move-$move-height-$height").recycle()
                    }
                }
                for (style in SymbolPanelStyle.entries) {
                    onMain { prefs.symbols.symbolPanelStyle.setValue(style) }
                    for (picker in listOf(PickerWindow.Key.Symbol, PickerWindow.Key.Emoji, PickerWindow.Key.Kaomoji)) {
                        onMain { windows(checkNotNull(inputView())).attachWindow(picker) }
                        await("$picker groups must attach in $style") { groupList()?.childCount?.let { it >= 2 } == true }
                        settle()
                        val second = onMainValue { checkNotNull(groupList()).getChildAt(1) as TextView }
                        val selectedName = onMainValue { second.text.toString() }
                        tap(second)
                        var selected: TextView? = null
                        await("The selected category must use its configured foreground") {
                            selected = groupList()?.let(::descendants)?.filterIsInstance<TextView>()?.firstOrNull {
                                it.isShown && it.text.toString() == selectedName &&
                                    it.currentTextColor == theme.genericActiveForegroundColor
                            }
                            selected != null
                        }
                        val bounds = onMainValue { screenBounds(checkNotNull(selected)) }
                        val bitmap = capture("picker-$name-$style-$picker")
                        try {
                            var foregroundPixels = 0
                            var backgroundPixels = 0
                            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                                when (bitmap.getPixel(x, y)) {
                                    theme.genericActiveForegroundColor -> foregroundPixels++
                                    theme.genericActiveBackgroundColor -> backgroundPixels++
                                }
                            }
                            assertTrue("Selected label ink must reach the real screen", foregroundPixels > 5)
                            assertTrue("Selected background must reach the real screen", backgroundPixels > 50)
                        } finally { bitmap.recycle() }
                    }
                }
                onMain { windows(checkNotNull(inputView())).attachWindow(KeyboardWindow) }
            }
        } finally {
            onMain {
                activity?.finish()
                ConfigProviders.provider = previousProvider
                TextKeyboard.cachedLayoutJsonMap = null
                previousValues.forEach { (pref, value) ->
                    pref.sharedPreferences.edit().apply {
                        when (value) {
                            null -> remove(pref.key)
                            is String -> putString(pref.key, value)
                            is Boolean -> putBoolean(pref.key, value)
                            is Int -> putInt(pref.key, value)
                            else -> error("Unexpected saved preference type for ${pref.key}")
                        }
                    }.commit()
                }
                ThemeManager.refreshThemes(previousThemes)
            }
            if (previousHardwareIme == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $previousHardwareIme")
        }
    }

    private fun fixtureLayout(): JsonObject {
        val rows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM").map { letters ->
            letters.mapIndexed { index, letter ->
                """{"type":"MacroKey","label":"$letter","swipeUpLabel":"${index + 1}",
                    "swipeDownLabel":"~","tap":{"macro":[{"type":"text","text":"$letter"}]},
                    "swipeUp":{"macro":[{"type":"text","text":"1"}]},
                    "swipeDown":{"macro":[{"type":"text","text":"~"}]}}"""
            }.joinToString(",", "[", "]")
        }
        val bottom = """[{"type":"SpaceKey","weight":3},
            {"type":"BackspaceKey","swipeUpLabel":"清空","swipeDownLabel":"删除",
            "swipeUp":{"macro":[{"type":"text","text":""}]},
            "swipeDown":{"macro":[{"type":"text","text":""}]}}]"""
        return Json.parseToJsonElement("{\"default\":[${(rows + bottom).joinToString(",")}]}").jsonObject
    }

    private fun assertLabelsInside(key: KeyView, labels: List<TextView>) {
        for (label in labels) {
            assertTrue("Both directions remain visible", label.isShown)
            val parent = label.parent as View
            assertTrue("${label.text}: top=${label.top}, margin=${key.vMargin}", label.top >= key.vMargin)
            assertTrue("${label.text}: bottom=${label.bottom}, height=${parent.height}",
                label.bottom <= parent.height - key.vMargin)
        }
    }

    private fun inputView() = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
        .filterIsInstance<InputView>().firstOrNull { it.isShown && it.service.isInputViewShown }
    private fun keys(view: View) = descendants(view).filterIsInstance<KeyView>().filter { it.isShown }
    private fun groupList() = inputView()?.let(::descendants)?.filterIsInstance<RecyclerView>()
        ?.firstOrNull { it.isShown && it.layoutManager !is GridLayoutManager && it.childCount >= 2 && it.getChildAt(0) is TextView }
    private fun windows(view: InputView) = InputView::class.java.getDeclaredField("windowManager")
        .apply { isAccessible = true }.get(view) as InputWindowManager
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(250); instrumentation.waitForIdleSync() }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMainValue(condition)) return
            SystemClock.sleep(40)
        }
        capture("failure").recycle()
        val state = onMainValue {
            inputView()?.let(::keys)?.joinToString { key ->
                if (key is AltTextKeyView) "${key.mainText.text}[${key.altText.text},${key.altText1.text}]"
                else key.javaClass.simpleName
            }
        }
        fail("$message: $state")
    }
    private fun tap(view: View) {
        val bounds = onMainValue { screenBounds(view) }
        val now = SystemClock.uptimeMillis()
        for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
            val event = MotionEvent.obtain(now, now + index * 40L, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            if (index == 0) SystemClock.sleep(40)
        }
        settle()
    }
    private fun screenBounds(view: View) = Rect().also {
        assertTrue(view.getLocalVisibleRect(it))
        val position = IntArray(2).also(view::getLocationOnScreen)
        it.offset(position[0], position[1])
    }
    private fun capture(name: String): Bitmap {
        settle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(context.getExternalFilesDir(null), "theme-label-diagnostics").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> onMainValue(block: () -> T): T {
        var result: T? = null
        onMain { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
    }
}
