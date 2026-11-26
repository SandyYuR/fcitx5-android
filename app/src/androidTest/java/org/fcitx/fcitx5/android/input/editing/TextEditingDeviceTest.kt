/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.editing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.IdleUi
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.status.StatusAreaWindow
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.input.font.FontProviderApi
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.ui.main.settings.im.RimeImportActivity
import org.fcitx.fcitx5.android.ui.main.settings.icon.IconThemeEditorActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/** Uses the existing IME and temporary layout copies; always restores user settings and clipboard. */
class TextEditingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { input -> input.readText().trim() }
    }
    private fun await(message: String, block: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            main { done = block() }
            if (done) return
            SystemClock.sleep(50)
        }
        fail(message)
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun inputView(): InputView? = WindowInspector.getGlobalWindowViews().asSequence()
        .flatMap(::descendants).filterIsInstance<InputView>()
        .firstOrNull { it.isShown && it.service.isInputViewShown }
    private fun manager(input: InputView) = InputView::class.java.getDeclaredField("windowManager")
        .apply { isAccessible = true }.get(input) as InputWindowManager
    private fun toolbarMenu(input: InputView): View {
        val bar = InputView::class.java.getDeclaredField("kawaiiBar")
            .apply { isAccessible = true }.get(input) as KawaiiBarComponent
        val idle = KawaiiBarComponent::class.java.getDeclaredMethod("getIdleUi")
            .apply { isAccessible = true }.invoke(bar) as IdleUi
        return idle.menuButton
    }
    private fun editorUi(window: TextEditingWindow) = TextEditingWindow::class.java.getDeclaredMethod("getUi")
        .apply { isAccessible = true }.invoke(window) as TextEditingUi
    private fun openEditor(input: InputView): TextEditingWindow {
        input.executeButtonAction("cursor_move")
        return manager(input).currentWindowOrNull() as? TextEditingWindow
            ?: error("The toolbar editing action did not open the editing window")
    }
    private fun swipe(view: View, deltaX: Float) {
        val location = IntArray(2)
        var x = 0f; var y = 0f
        main { view.getLocationOnScreen(location); x = location[0] + view.width / 3f; y = location[1] + view.height / 2f }
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, targetX: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, targetX, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, x)
        SystemClock.sleep(30)
        inject(MotionEvent.ACTION_MOVE, x + deltaX)
        inject(MotionEvent.ACTION_UP, x + deltaX)
        instrumentation.waitForIdleSync()
    }
    private fun tap(view: View) {
        val location = IntArray(2)
        var x = 0f; var y = 0f
        main {
            assertTrue("Touch target must be visible", view.isShown)
            view.getLocationOnScreen(location)
            x = location[0] + view.width / 2f; y = location[1] + view.height / 2f
        }
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(30)
        inject(MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
    }
    private fun visibleKeyboardEdges(): List<List<Int>> {
        var edges = emptyList<List<Int>>()
        main {
            val cells = descendants(requireNotNull(inputView())).filterIsInstance<KeyView>()
                .filter { it.isShown && it.width > 0 && it.height > 0 }.map { view ->
                    val xy = IntArray(2)
                    view.getLocationOnScreen(xy)
                    listOf(xy[0], xy[1], xy[0] + view.width)
                }.toList()
            edges = cells.groupBy { it[1] }.toSortedMap().values.map { row ->
                val left = row.minBy { it[0] }
                val right = row.maxBy { it[2] }
                listOf(left[0], left[2], right[0], right[2])
            }
            assertEquals("Four measured keyboard rows", 4, edges.size)
        }
        return edges
    }

    private fun screenshot(name: String) {
        // UI idleness can occur between animation frames; retain normal animations and
        // let both the IME window transition and a queued keyboard relayout finish.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val dir = File(context.getExternalFilesDir(null), "test-evidence/foxy-update").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @Test fun bundledEditorsKeepSpansSelectionActionsAndFoxyFallback() {
        val prefs = AppPrefs.getInstance()
        val oldLayout = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        val oldStyle = prefs.textEditing.style.getValue()
        val oldOverride = prefs.textEditing.layoutOverride.getValue()
        val oldNumericOverride = prefs.keyboard.numericLayoutOverride.getValue()
        val oldListening = prefs.clipboard.clipboardListening.getValue()
        val oldNormalTheme = ThemeManager.prefs.normalModeTheme.getValue()
        val oldFollowing = ThemeManager.prefs.followSystemDayNightTheme.getValue()
        val oldIcons = IconThemeManager.activeTheme
        val oldSpecialKeyOval = ThemeManager.prefs.specialKeyOvalShape.getValue()
        val oldFontProvider = FontProviders.provider
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var oldClip: ClipData? = null
        var clipboardCaptured = false
        val component = "${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        val previousIme = shell("settings get secure default_input_method")
        val previousHard = shell("settings get secure show_ime_with_hard_keyboard")
        val enabled = component in shell("ime list -s")
        val created = mutableListOf<File>()
        val numericGeometry = org.json.JSONArray()
        var activity: RimeImportActivity? = null
        var iconEditor: IconThemeEditorActivity? = null
        var testFailure: Throwable? = null
        try {
            main {
                prefs.clipboard.clipboardListening.setValue(false)
                ThemeManager.prefs.specialKeyOvalShape.setValue(false)
                val iconFont = Typeface.createFromAsset(context.assets, "bundled/fonts/字体图标.otf")
                FontProviders.provider = object : FontProviderApi by oldFontProvider {
                    override val fontTypefaceMap = oldFontProvider.fontTypefaceMap.toMutableMap().apply {
                        put("key_alt_font", iconFont)
                    }
                    override val fontSizeMap = oldFontProvider.fontSizeMap.toMutableMap()
                    override val fontDataVersion = oldFontProvider.fontDataVersion + 1L
                    override fun clearCache() = Unit
                }
                FontProviders.markNeedsRefresh()
            }
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("ime enable $component"); shell("ime set $component")
            for ((themeName, imagePrefix) in listOf("水彩" to "watercolor", "普蓝" to "pulan")) {
                val profile = "editor-device-${UUID.randomUUID()}"
                val file = requireNotNull(UserConfigFiles.textKeyboardLayoutJson(profile))
                file.parentFile!!.mkdirs()
                context.assets.open("bundled/keyboard_layouts/TextKeyboardLayout.$themeName.json").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                created += file
                val icons = context.assets.open("bundled/icon_themes/$themeName.json").bufferedReader().use {
                    Json { ignoreUnknownKeys = true }.decodeFromString<IconTheme>(it.readText())
                }
                await("Installed theme is loaded after asynchronous preset upgrade") {
                    val loaded = ThemeManager.getTheme(themeName)
                    loaded != null && (themeName != "水彩" ||
                        loaded.altKeyBackgroundColor == -1447447 && loaded.accentKeyBackgroundColor == -3481622)
                }
                main {
                    prefs.textEditing.style.setValue(TextEditingStyle.Default)
                    prefs.textEditing.layoutOverride.setValue("")
                    prefs.keyboard.numericLayoutOverride.setValue("")
                    prefs.keyboard.textKeyboardLayoutProfile.setValue(profile)
                    ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
                    ThemeManager.setNormalModeTheme(requireNotNull(ThemeManager.getTheme(themeName)))
                    IconThemeManager.selectTheme(icons)
                }
                activity = instrumentation.startActivitySync(Intent(context, RimeImportActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RimeImportActivity
                val editor = activity!!.findViewById<EditText>(R.id.direct_rime_test_editor)
                main {
                    editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    editor.setText("abcd\nefgh")
                    editor.setSelection(2)
                    editor.requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
                await("Visible IME") { inputView() != null }
                if (!clipboardCaptured) main { oldClip = clipboard.primaryClip; clipboardCaptured = true }
                var input: InputView? = null
                var window: TextEditingWindow? = null
                var ui: ConfiguredTextEditingUi? = null
                fun open() {
                    main {
                        input = requireNotNull(inputView())
                        window = openEditor(input!!)
                        ui = editorUi(window!!) as? ConfiguredTextEditingUi
                    }
                    await("Configured editor has 15 measured keys") {
                        ui?.keyViews?.let { keys -> keys.size == 15 && keys.all { it.view.width > 0 && it.view.height > 0 } } == true
                    }
                }
                open()
                val keys = ui!!.keyViews
                fun key(row: Int, index: Int) = keys.single { it.row == row && it.index == index }.view
                main {
                    assertEquals(15, keys.map { it.view }.distinct().size)
                    assertTrue(kotlin.math.abs(key(0, 1).height * 3 - key(0, 0).height) <= 2)
                    assertEquals(key(0, 0).height, key(0, 2).height)
                }
                screenshot("$imagePrefix-editor")
                tap(key(1, 0))
                await("Selection lock on") { key(1, 0).isActivated }
                tap(key(0, 2))
                await("Right extends selection") { kotlin.math.abs(editor.selectionEnd - editor.selectionStart) == 1 }
                screenshot("$imagePrefix-editor-selected")
                tap(key(1, 0))
                await("Selection lock off") { !key(1, 0).isActivated && editor.selectionStart == editor.selectionEnd }
                main { editor.setSelection(2) }
                tap(key(2, 1))
                await("Home moves to line start") { editor.selectionStart == 0 && editor.selectionEnd == 0 }
                tap(key(2, 2))
                await("End moves to line end") { editor.selectionEnd == 4 }
                tap(key(3, 1))
                await("Space inserts") { editor.text.toString() == "abcd \nefgh" }
                tap(key(3, 2))
                await("Backspace removes inserted space") { editor.text.toString() == "abcd\nefgh" }
                tap(key(0, 3))
                await("Select all") { editor.selectionStart == 0 && editor.selectionEnd == editor.length() }
                main { clipboard.setPrimaryClip(ClipData.newPlainText("editor-test", "sentinel-${UUID.randomUUID()}")) }
                tap(key(1, 1))
                await("Copy") { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "abcd\nefgh" }
                tap(key(0, 3))
                await("Reselect before cut") { editor.selectionStart == 0 && editor.selectionEnd == editor.length() }
                tap(key(0, 4))
                await("Cut") { editor.text.isEmpty() }
                tap(key(1, 2))
                await("Paste") { editor.text.toString() == "abcd\nefgh" }
                main { editor.setSelection(editor.length()) }
                tap(key(3, 3))
                await("Return inserts newline") { editor.text.toString() == "abcd\nefgh\n" }
                tap(key(3, 0))
                await("ABC returns to keyboard") { manager(requireNotNull(inputView())).currentWindowOrNull() !is TextEditingWindow }
                screenshot("$imagePrefix-keyboard")
                val mainEdges = visibleKeyboardEdges()
                val operationEdges = mainEdges[2]
                operationEdges.zip(mainEdges[3]).forEach { (a, b) ->
                    assertTrue("Main Shift/backspace and numpad/return boundaries agree", kotlin.math.abs(a - b) <= 1)
                }
                if (themeName == "水彩") {
                    var symbolKey: View? = null
                    main { symbolKey = descendants(requireNotNull(inputView())).filterIsInstance<KeyView>()
                        .single { it.isShown && it.themedIconSlot == "keys.symbols" } }
                    tap(requireNotNull(symbolKey))
                    await("Dedicated symbol key opens symbols") {
                        manager(requireNotNull(inputView())).currentWindowOrNull() is PickerWindow
                    }
                    screenshot("$imagePrefix-symbols")
                    fun pickerBack(): KeyView = descendants(requireNotNull(inputView()))
                        .filterIsInstance<KeyView>().single { it.isShown && it.themedIconSlot == "toolbar.virtual_keyboard" }
                    tap(pickerBack())
                    await("Symbol utility key returns to keyboard") {
                        manager(requireNotNull(inputView())).currentWindowOrNull() is KeyboardWindow
                    }
                    main { (manager(requireNotNull(inputView())).getEssentialWindow(KeyboardWindow) as KeyboardWindow)
                        .switchLayout("Emoji", remember = false, fromUserKey = true) }
                    await("Emoji panel shown") {
                        manager(requireNotNull(inputView())).currentWindowOrNull() is PickerWindow
                    }
                    screenshot("$imagePrefix-emoji")
                    tap(pickerBack())
                    await("Emoji utility key returns to keyboard") {
                        manager(requireNotNull(inputView())).currentWindowOrNull() is KeyboardWindow
                    }
                }
                tap(toolbarMenu(requireNotNull(inputView())))
                await("Toolbar menu opens status area") {
                    manager(requireNotNull(inputView())).currentWindowOrNull() is StatusAreaWindow
                }
                screenshot("$imagePrefix-status")
                main { manager(requireNotNull(inputView())).attachWindow(KeyboardWindow) }
                await("Return from status area") {
                    manager(requireNotNull(inputView())).currentWindowOrNull() is KeyboardWindow
                }
                main { (manager(requireNotNull(inputView())).getEssentialWindow(KeyboardWindow) as KeyboardWindow)
                    .switchLayout(NumberKeyboard.Name, remember = false, fromUserKey = true) }
                await("Configured numeric page") { TextKeyboard.isManualNumericLayoutShowing() }
                screenshot("$imagePrefix-numpad")
                val numericEdges = visibleKeyboardEdges()
                numericEdges.forEachIndexed { row, edges ->
                    operationEdges.zip(edges).forEachIndexed { boundary, (main, numeric) ->
                        assertTrue("$themeName numeric row $row boundary $boundary: $main versus $numeric",
                            kotlin.math.abs(main - numeric) <= 1)
                    }
                }
                numericGeometry.put(org.json.JSONObject().put("theme", themeName)
                    .put("mainEdges", org.json.JSONArray(operationEdges))
                    .put("numericRowEdges", org.json.JSONArray(numericEdges.map { org.json.JSONArray(it) })))
                main { (manager(requireNotNull(inputView())).getEssentialWindow(KeyboardWindow) as KeyboardWindow)
                    .switchLayout(TextKeyboard.Name, remember = false, fromUserKey = true) }
                await("Return from numeric page") { !TextKeyboard.isManualNumericLayoutShowing() }
                main { prefs.textEditing.style.setValue(TextEditingStyle.FoxySwipe) }
                main {
                    input = requireNotNull(inputView())
                    window = openEditor(input!!)
                    assertTrue(editorUi(window!!) is FoxyTextEditingUi)
                }
                await("Foxy swipe bar measured") { editorUi(window!!).root.height > 0 }
                screenshot("$imagePrefix-swipe")
                main { editor.setSelection(0) }
                val swipeUi = editorUi(window!!) as FoxyTextEditingUi
                swipe(swipeUi.cursorPad, prefs.textEditing.cursorStepDp.getValue() * context.resources.displayMetrics.density * 2.1f)
                await("Foxy swipe moves cursor without selection") { editor.selectionStart == 2 && editor.selectionEnd == 2 }
                main {
                    prefs.textEditing.style.setValue(TextEditingStyle.Default)
                    prefs.textEditing.layoutOverride.setValue("@builtin")
                }
                main {
                    input = requireNotNull(inputView())
                    window = openEditor(input!!)
                    assertEquals(TextEditingUi::class.java, editorUi(window!!).javaClass)
                }
                main { activity?.finish(); activity = null }
                instrumentation.waitForIdleSync()
            }
            File(context.getExternalFilesDir(null), "test-evidence/foxy-update/numeric-edge-bounds.json")
                .writeText(numericGeometry.toString(2))
            val iconThemeFile = File(context.getExternalFilesDir(null), "icon_themes/水彩.json")
            val iconThemeBeforePreview = iconThemeFile.readBytes()
            iconEditor = instrumentation.startActivitySync(Intent(context, IconThemeEditorActivity::class.java)
                .putExtra("icon_theme_name", "水彩").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as IconThemeEditorActivity
            await("Icon theme list has visible rows") {
                descendants(iconEditor!!.window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>()
                    .any { it.isShown && it.childCount > 2 }
            }
            main {
                assertEquals("水彩", iconEditor!!.supportActionBar?.title.toString())
                assertTrue("SVG source must not appear in the list",
                    descendants(iconEditor!!.window.decorView).filterIsInstance<android.widget.TextView>()
                        .filter { it.isShown }.none { it.text.contains("<svg", ignoreCase = true) || it.text.startsWith("svg:") })
            }
            screenshot("watercolor-icon-list")
            main {
                val list = descendants(iconEditor!!.window.decorView)
                    .filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.isShown }
                (list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager)
                    .scrollToPositionWithOffset(IconTheme.KEY_SLOTS.indexOf("keys.pageup") + 1, 0)
            }
            instrumentation.waitForIdleSync()
            screenshot("watercolor-navigation-icons")
            main { iconEditor?.finish(); iconEditor = null }
            instrumentation.waitForIdleSync()
            assertArrayEquals("Preview must not rewrite the installed icon theme", iconThemeBeforePreview, iconThemeFile.readBytes())
        } catch (error: Throwable) {
            testFailure = error
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun restore(name: String, block: () -> Unit) {
                try { block() } catch (error: Throwable) { cleanupFailures += AssertionError("Could not restore $name", error) }
            }
            restore("clipboard") { main {
                if (clipboardCaptured) {
                    if (oldClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(oldClip!!)
                    assertEquals(oldClip?.itemCount ?: 0, clipboard.primaryClip?.itemCount ?: 0)
                }
            } }
            restore("layout profile") { main { prefs.keyboard.textKeyboardLayoutProfile.setValue(oldLayout); assertEquals(oldLayout, prefs.keyboard.textKeyboardLayoutProfile.getValue()) } }
            restore("editing style") { main { prefs.textEditing.style.setValue(oldStyle); assertEquals(oldStyle, prefs.textEditing.style.getValue()) } }
            restore("editing override") { main { prefs.textEditing.layoutOverride.setValue(oldOverride); assertEquals(oldOverride, prefs.textEditing.layoutOverride.getValue()) } }
            restore("numeric override") { main { prefs.keyboard.numericLayoutOverride.setValue(oldNumericOverride); assertEquals(oldNumericOverride, prefs.keyboard.numericLayoutOverride.getValue()) } }
            restore("theme") { main { ThemeManager.setNormalModeTheme(oldNormalTheme) } }
            restore("system theme following") { main { ThemeManager.prefs.followSystemDayNightTheme.setValue(oldFollowing) } }
            restore("icon theme") { main { IconThemeManager.selectTheme(oldIcons); assertEquals(oldIcons, IconThemeManager.activeTheme) } }
            restore("operation key shape") { main { ThemeManager.prefs.specialKeyOvalShape.setValue(oldSpecialKeyOval); assertEquals(oldSpecialKeyOval, ThemeManager.prefs.specialKeyOvalShape.getValue()) } }
            restore("font provider") { main { FontProviders.provider = oldFontProvider; FontProviders.markNeedsRefresh(); assertSame(oldFontProvider, FontProviders.provider) } }
            restore("clipboard listening") { main { prefs.clipboard.clipboardListening.setValue(oldListening); assertEquals(oldListening, prefs.clipboard.clipboardListening.getValue()) } }
            restore("icon preview activity") { main { iconEditor?.finish() } }
            restore("test activity") { main { activity?.finish() } }
            created.forEach { file -> restore("temporary profile") { check(!file.exists() || file.delete()) } }
            restore("input method") {
                if (previousIme.isNotBlank() && previousIme != "null") shell("ime set $previousIme")
                else shell("settings delete secure default_input_method")
            }
            restore("hardware keyboard visibility") {
                if (previousHard == "null") shell("settings delete secure show_ime_with_hard_keyboard")
                else shell("settings put secure show_ime_with_hard_keyboard $previousHard")
            }
            restore("enabled input method") { if (!enabled) shell("ime disable $component") }
            restore("system input settings verification") {
                assertEquals(previousIme, shell("settings get secure default_input_method"))
                assertEquals(previousHard, shell("settings get secure show_ime_with_hard_keyboard"))
            }
            if (cleanupFailures.isNotEmpty()) {
                val cleanupError = AssertionError("${cleanupFailures.size} editor fixture restoration failures")
                cleanupFailures.forEach(cleanupError::addSuppressed)
                if (testFailure != null) testFailure.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }
}
