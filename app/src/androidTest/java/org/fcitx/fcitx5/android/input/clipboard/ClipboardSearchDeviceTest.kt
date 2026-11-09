/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Window
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.ImageView
import android.widget.ListView
import androidx.room.Room
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.chip.Chip
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardCategory
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.voice.js.JsAppDialog
import org.fcitx.fcitx5.android.input.voice.js.JsAppEditText
import org.fcitx.fcitx5.android.input.voice.js.JsAppPermissions
import org.fcitx.fcitx5.android.input.voice.js.JsApps
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceProviders
import org.fcitx.fcitx5.android.ui.main.settings.im.RimeImportActivity
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import splitties.dimensions.dp

/** Actual attached window, soft keys and app connection; run only on a disposable Debug emulator. */
@SdkSuppress(minSdkVersion = 29)
class ClipboardSearchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val name = "clipboard-search-${UUID.randomUUID()}"
    private val connection by lazy { FcitxDaemon.connect(name) }
    private val activities = mutableListOf<Activity>()
    private data class Appearance(val name: String, val selection: SettingsUiPreferences.Selection, val scale: Float = 1f)

    @Test fun realKeyboardSearchGroupsPastingAndCompactWindowRemainUsable() = runBlocking {
        assertTrue(BuildConfig.DEBUG && context.packageName.endsWith(".debug"))
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
        val previousListening = prefs.clipboard.clipboardListening.getValue()
        val toolbarFile = checkNotNull(ConfigProviders.provider.buttonsLayoutConfigFile())
        val previousToolbar = toolbarFile.takeIf { it.isFile }?.readBytes()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val previousCategory = preferences.getString("clipboard_last_opened_category", null)
        val databaseName = "$name.db"
        val database = Room.databaseBuilder(context, ClipboardDatabase::class.java, databaseName).allowMainThreadQueries().build()
        val daoField = ClipboardManager::class.java.getDeclaredField("clbDao").apply { isAccessible = true }
        val originalDao = daoField.get(null)
        val mediaFiles = mutableListOf<File>()
        var previousProfile: String? = null
        var createdProfile = false
        try {
            toolbarFile.parentFile!!.mkdirs()
            toolbarFile.writeText(Json.encodeToString(ButtonsLayoutConfig.default().copy(
                kawaiiBarButtons = listOf(ConfigurableButton("undo"), ConfigurableButton("redo"),
                    ConfigurableButton("clipboard"), ConfigurableButton("ai_assistant")))))
            // Search exercises a private fixture database, leaving application history intact.
            daoField.set(null, database.clipboardDao())
            val dao = database.clipboardDao()
            dao.insert(ClipboardEntry(id = 1, text = "你好，剪贴板搜索", timestamp = 1))
            dao.insert(ClipboardEntry(id = 2, text = "Alpha 置顶记录", pinned = true, timestamp = 2))
            dao.insert(ClipboardEntry(id = 3, text = "Beta 远端记录", source = ClipboardEntry.SOURCE_REMOTE, timestamp = 3))
            for (id in 4..71) dao.insert(ClipboardEntry(id = id, text = "颜色记录 $id · #FF51AF90", timestamp = id.toLong()))
            val media = listOf(
                fixtureImage(mediaFiles, "wide", 240, 60),
                fixtureImage(mediaFiles, "tall", 60, 160),
                fixtureImage(mediaFiles, "square", 96, 96),
                Uri.fromFile(File(context.cacheDir, "$name-missing.png")).toString(),
                File(context.cacheDir, "$name.txt").also { it.writeText("clipboard fixture"); mediaFiles += it }
                    .let(Uri::fromFile).toString())
            media.forEachIndexed { index, uri ->
                val id = index + 72
                dao.insert(ClipboardEntry(id = id, text = uri, type = if (index == 4) "text/plain" else "image/png", timestamp = id.toLong()))
            }
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
                prefs.clipboard.clipboardListening.setValue(true)
            }
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("ime enable $component"); shell("ime set $component")

            val light = previousUi.copy(mode = SettingsUiPreferences.MODE_LIGHT,
                palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false,
                pureBlack = false, contrast = SettingsUiPreferences.CONTRAST_STANDARD)
            for ((suffix, selection, scale) in listOf(
                Appearance("light", light),
                Appearance("dark", light.copy(mode = SettingsUiPreferences.MODE_DARK)),
                Appearance("large", light, 1.6f),
                Appearance("dark-amber", light.copy(mode = SettingsUiPreferences.MODE_DARK,
                    palette = SettingsUiPreferences.PALETTE_AMBER, contrast = SettingsUiPreferences.CONTRAST_HIGH, pureBlack = true)),
                Appearance("keyboard", light.copy(followKeyboardTheme = true)))) {
                shell("settings put system font_scale $scale")
                SettingsUiPreferences.save(context, selection)
                val activity = instrumentation.startActivitySync(Intent(context, RimeImportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                activities += activity
                val editor = activity.findViewById<EditText>(R.id.direct_rime_test_editor)
                onMain {
                    editor.inputType = InputType.TYPE_CLASS_TEXT
                    editor.setText("原应用"); editor.setSelection(editor.text.length); editor.requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
                var service: FcitxInputMethodService? = null
                await("The actual keyboard must bind to the fixture editor") {
                    service = windows().filterIsInstance<InputView>().firstOrNull { it.isShown }?.service
                    service?.isInputViewShown == true && editor.hasFocus()
                }
                val ime = checkNotNull(service)
                if (suffix == "light") {
                    val manager = inputWindows(ime.inputView!!)
                    onMain { manager.attachWindow(KeyboardWindow) }
                    var clipboardButton: View? = null
                    await("The real keyboard toolbar must expose clipboard") {
                        clipboardButton = descendants(ime.inputView!!).firstOrNull {
                            it.isShown && it.contentDescription == context.getString(R.string.clipboard)
                        }; manager.currentWindowOrNull() is KeyboardWindow && clipboardButton != null &&
                            !clipboardButton!!.isLayoutRequested && !ime.inputView!!.isLayoutRequested
                    }
                    tap(checkNotNull(clipboardButton))
                    var remote: View? = null
                    await("The ordinary clipboard page must expose its remote group") {
                        remote = descendants(ime.inputView!!).filterIsInstance<TextView>().firstOrNull {
                            it.isShown && it.text.toString() == context.getString(ClipboardCategory.Remote.titleRes)
                        }; remote != null
                    }
                    onMain {
                        ClipboardCategory.values().forEach { value ->
                            val group = descendants(ime.inputView!!).filterIsInstance<TextView>().single {
                                it.isShown && it.text.toString() == context.getString(value.titleRes)
                            }
                            assertEquals("Ordinary clipboard groups must retain their original height", context.dp(30), group.height)
                        }
                    }
                    capture("clipboard-keyboard-compact-groups")
                    tap(checkNotNull(remote))
                    var search: View? = null
                    await("The ordinary clipboard page must expose search") {
                        search = descendants(ime.inputView!!).firstOrNull {
                            it.isShown && it.contentDescription == context.getString(R.string.search)
                        }; search != null
                    }
                    tap(checkNotNull(search))
                } else onMain { ime.startClipboardSearch(ClipboardCategory.Remote) }
                val query = query()
                val results = results()
                val adapter = results.adapter as ClipboardAdapter
                await("Empty query must show the selected remote group") { adapter.snapshot().items.map { it.id } == listOf(3) }
                assertPanel(ime, query, results)
                tap(category(query, ClipboardCategory.All))
                await("All entries must replace the remote group") { adapter.itemCount >= 48 }
                for (page in 1..8) {
                    onMain { results.scrollToPosition(adapter.itemCount - 1) }
                    instrumentation.waitForIdleSync(); SystemClock.sleep(120)
                    if (onMainValue { adapter.itemCount == 76 }) break
                }
                await("The panel must page beyond the old 50-result limit") { adapter.itemCount == 76 }
                onMain { results.scrollToPosition(0) }
                await("Newest card must be laid out before capturing the panel") {
                    (results.layoutManager as androidx.recyclerview.widget.StaggeredGridLayoutManager)
                        .findFirstVisibleItemPositions(null).any { it == 0 } &&
                        !results.hasPendingAdapterUpdates() && !results.isLayoutRequested &&
                        visibleText(results, "Alpha 置顶记录")
                }
                capture("clipboard-search-$suffix")
                assertPanel(ime, query, results)
                assertColors(query, results)
                assertCursorBlinks(query, "clipboard-search-$suffix")
                assertEquals("原应用", onMainValue { editor.text.toString() })
                tap(category(query, ClipboardCategory.Media))
                await("Media group must display loaded previews and a missing-image placeholder") {
                    adapter.snapshot().items.map { it.id }.toSet() == (72..76).toSet() &&
                        results.childCount == 5 && !results.hasPendingAdapterUpdates() && !results.isLayoutRequested &&
                        (0 until results.childCount).map { results.getChildViewHolder(results.getChildAt(it)) as ClipboardAdapter.ViewHolder }
                            .count { it.entryUi.preview.visibility == View.VISIBLE && it.entryUi.preview.drawable != null } == 3
                }
                assertColors(query, results)
                assertMediaCardSurfaces(query, results)
                capture("clipboard-search-media-$suffix")
                tap(category(query, ClipboardCategory.All))
                await("Leaving the media group must restore the full history") { adapter.itemCount >= 48 }
                if (suffix == "light") {
                    assertClipboardToolbarSwitchKeepsPanelVisible(ime, query, "clipboard-search")
                    exerciseEncodingAndCursor(ime, query, editor, "clipboard-search")
                    await("Rime's Chinese commit must enter only the local search field") { query.text.toString() == "你" }
                    await("Chinese query must filter the actual visible clipboard cards") {
                        adapter.snapshot().items.map { it.id } == listOf(1) && results.childCount == 1 &&
                            visibleText(results, "你好，剪贴板搜索")
                    }
                    assertEquals("原应用", onMainValue { editor.text.toString() })
                    capture("clipboard-search-query")
                    tapKey("Backspace")
                    await("Backspace must clear the query and restore all history") { query.text.isEmpty() && adapter.itemCount >= 48 }
                    onMain { ime.commitText("Alpha") }
                    await("Typed search must filter to the visible pinned entry") {
                        adapter.snapshot().items.map { it.id } == listOf(2) && visibleText(results, "Alpha 置顶记录")
                    }
                    val box = generateSequence(query.parent) { it.parent }.filterIsInstance<TextInputLayout>().first()
                    tap(box.findViewById(com.google.android.material.R.id.text_input_end_icon))
                    await("Clear control must restore the current group") { query.text.isEmpty() && adapter.itemCount >= 48 }
                    onMain { ime.commitText("Alpha") }
                    await("Pinned result should be visible") {
                        adapter.snapshot().items.map { it.id } == listOf(2) && visibleText(results, "Alpha 置顶记录")
                    }
                    tap(onMainValue { results.getChildAt(0) })
                    await("Selecting a card must paste into the original app and close the local editor") {
                        editor.text.toString() == "原应用Alpha 置顶记录" && ime.jsAppInputConnection == null
                    }
                    tapKey("n"); tapKey("i"); tapKey("Space")
                    await("After closing the panel, real soft keys must resume typing into the app") {
                        editor.text.toString() == "原应用Alpha 置顶记录你"
                    }
                    exerciseOtherEditors(ime, editor, suffix)
                } else {
                    if (suffix == "dark") {
                        onMain { ime.commitText("Alpha") }
                        await("Pinned result must expose its themed icon and menu") {
                            adapter.snapshot().items.map { it.id } == listOf(2) && visibleText(results, "Alpha 置顶记录")
                        }
                        assertColors(query, results)
                        capture("clipboard-search-dark-pinned")
                        longPress(onMainValue { results.getChildAt(0) })
                        var delete: TextView? = null
                        await("The actual card long press must open the themed context menu") {
                            delete = windows().filterIsInstance<TextView>().firstOrNull {
                                it.isShown && it.text.toString() == context.getString(R.string.delete)
                            }; delete != null
                        }
                        assertEquals(query.context.settingsColor(com.google.android.material.R.attr.colorOnSurface), onMainValue { delete!!.currentTextColor })
                        capture("clipboard-search-dark-menu")
                        tap(checkNotNull(delete))
                        var undo: TextView? = null
                        await("Deleting must show the themed undo action") {
                            undo = windows().filterIsInstance<TextView>().firstOrNull {
                                it.id == com.google.android.material.R.id.snackbar_action && it.isShown
                            }; adapter.itemCount == 0 && undo != null
                        }
                        val message = windows().filterIsInstance<TextView>().single {
                            it.id == com.google.android.material.R.id.snackbar_text && it.isShown
                        }
                        assertEquals(query.context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceInverse), onMainValue { message.currentTextColor })
                        assertEquals(query.context.settingsColor(com.google.android.material.R.attr.colorPrimaryInverse), onMainValue { undo!!.currentTextColor })
                        capture("clipboard-search-dark-undo")
                        tap(checkNotNull(undo))
                        await("Undo must restore the deleted pinned record") { adapter.snapshot().items.map { it.id } == listOf(2) }
                    }
                    tap(windows().first { it.tag == "clipboard_search_close" && it.isShown })
                    await("Close must release the local input connection") { ime.jsAppInputConnection == null }
                    assertEquals("原应用", onMainValue { editor.text.toString() })
                    if (suffix == "large") exerciseOtherEditors(ime, editor, suffix)
                }
                onMain { activity.finish() }; instrumentation.waitForIdleSync(); activities.remove(activity)
            }
        } finally {
            onMain {
                ClipboardSearchController.stop()
                activities.forEach { it.finish() }
                prefs.candidates.mode.setValue(previousFloating)
                prefs.keyboard.textKeyboardLayoutProfile.setValue(previousLayout)
                prefs.internal.needNotifications.setValue(previousNotifications)
                prefs.clipboard.clipboardListening.setValue(previousListening)
            }
            preferences.edit().apply {
                if (previousCategory == null) remove("clipboard_last_opened_category")
                else putString("clipboard_last_opened_category", previousCategory)
            }.commit()
            SettingsUiPreferences.save(context, previousUi)
            if (previousToolbar == null) toolbarFile.delete() else toolbarFile.writeBytes(previousToolbar)
            restoreSetting("secure", "show_ime_with_hard_keyboard", previousHardwareIme)
            restoreSetting("system", "font_scale", previousFontScale)
            if (previousIme.contains('/')) shell("ime set $previousIme")
            if (!wasEnabled) shell("ime disable $component")
            daoField.set(null, originalDao)
            database.close(); context.deleteDatabase(databaseName)
            mediaFiles.forEach { check(requireNotNull(it.parentFile).canonicalFile == context.cacheDir.canonicalFile); it.delete() }
            try {
                if (createdProfile) connection.runOnReady {
                    previousProfile?.takeIf { it.isNotBlank() }?.let {
                        setAddonSubConfig("rime", "profile-manager", profileCommand("switch", it))
                    }
                    setAddonSubConfig("rime", "profile-manager", profileCommand("delete", name))
                }
            } finally { FcitxDaemon.disconnect(name) }
        }
    }

    private fun query(): EditText {
        var query: EditText? = null
        await("The attached clipboard search field must become visible") {
            query = windows().filterIsInstance<EditText>().firstOrNull { it.tag == "clipboard_query" && it.isShown }
            query != null && query!!.rootView.alpha > 0f
        }
        return checkNotNull(query)
    }
    private fun results(): androidx.recyclerview.widget.RecyclerView = windows().filterIsInstance<androidx.recyclerview.widget.RecyclerView>()
        .single { it.tag == "clipboard_search_results" && it.isShown }
    private fun category(query: EditText, value: ClipboardCategory): View = descendants(query.rootView).filterIsInstance<TextView>()
        .single { it.text.toString() == context.getString(value.titleRes) }
    private fun visibleText(view: View, text: String): Boolean = descendants(view).filterIsInstance<TextView>().any {
        it.text.toString() == text && it.isShown && it.getGlobalVisibleRect(Rect())
    }
    private fun inputWindows(view: InputView) = InputView::class.java.getDeclaredField("windowManager")
        .apply { isAccessible = true }.get(view) as InputWindowManager

    private fun activeDialog(service: FcitxInputMethodService): JsAppDialog? =
        FcitxInputMethodService::class.java.getDeclaredField("showingDialog").apply { isAccessible = true }
            .get(service) as? JsAppDialog

    private fun panelEditor(service: FcitxInputMethodService): JsAppEditText {
        var editor: JsAppEditText? = null
        await("The floating editor must become ready with its own local input") {
            editor = activeDialog(service)?.let { dialog ->
                descendants(dialog.findViewById(android.R.id.content)).filterIsInstance<JsAppEditText>()
                    .firstOrNull { it.isShown && it.isEnabled && it.ownsLocalInput && it.keyListener != null }
            }
            editor != null && activeDialog(service)?.window?.attributes?.alpha == 1f
        }
        return checkNotNull(editor)
    }

    private fun encoding(service: FcitxInputMethodService) = InputView::class.java.getDeclaredField("preedit")
        .apply { isAccessible = true }.get(service.inputView) as PreeditComponent

    private fun screenBounds(view: View) = Rect().also {
        assertTrue(view.getLocalVisibleRect(it))
        val location = IntArray(2).also(view::getLocationOnScreen)
        it.offset(location[0], location[1])
    }

    private fun exerciseEncodingAndCursor(service: FcitxInputMethodService, local: EditText, application: EditText, suffix: String) {
        val initialPanelBounds = onMainValue { screenBounds(local.rootView) }
        val initialAppText = onMainValue { application.text.toString() }
        val initialLocalText = onMainValue { local.text.toString() }
        val baseline = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            tapKey("n")
            await("Uncommitted encoding n must appear in the real IME preedit") {
                val ui = encoding(service).ui
                ui.root.isShown && descendants(ui.root).filterIsInstance<TextView>().any { it.text.toString().contains("n") }
            }
            assertEquals("Composition must not move the floating panel", initialPanelBounds, onMainValue { screenBounds(local.rootView) })
            assertEncodingDrawnAbovePanel(service, local, baseline)
            capture("$suffix-preedit-n")
            tapKey("i")
            await("The changing encoding must show ni") {
                descendants(encoding(service).ui.root).filterIsInstance<TextView>().any { it.text.toString().contains("ni") }
            }
            assertEquals(initialPanelBounds, onMainValue { screenBounds(local.rootView) })
            capture("$suffix-preedit-ni")
            tapKey("Backspace")
            await("Backspace must shorten the live encoding") {
                val text = descendants(encoding(service).ui.root).filterIsInstance<TextView>().joinToString { it.text.toString() }
                text.contains("n") && !text.contains("ni")
            }
            assertEquals(initialPanelBounds, onMainValue { screenBounds(local.rootView) })
            tapKey("i"); tapKey("Space")
            await("Chinese composition must commit only to the local floating editor") { local.text.toString() == initialLocalText + "你" }
            await("The completed composition must release the encoding area") { !encoding(service).ui.root.isShown }
            assertEquals("Completing composition must keep the panel in place", initialPanelBounds, onMainValue { screenBounds(local.rootView) })
            assertEquals(initialAppText, onMainValue { application.text.toString() })
            assertTrue("The original application must retain actual window focus", onMainValue { application.hasWindowFocus() })
            assertFalse("The floating editor must not become the system focus target", onMainValue { local.hasWindowFocus() })
            assertCursorBlinks(local, suffix)
        } finally { baseline.recycle() }
    }

    private fun assertEncodingDrawnAbovePanel(service: FcitxInputMethodService, local: EditText, baseline: Bitmap) {
        val overlap = onMainValue {
            val ui = encoding(service).ui
            val area = screenBounds(ui.root).apply { right = left + ui.actualContentWidth }
            assertTrue("The live encoding must overlap the panel for this regression check", area.intersect(screenBounds(local.rootView)))
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA, activeDialog(service)!!.window!!.attributes.type)
            area
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(150)
        val current = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            var changed = 0
            for (y in overlap.top until overlap.bottom) for (x in overlap.left until overlap.right) {
                if (baseline.getPixel(x, y) != current.getPixel(x, y)) changed++
            }
            assertTrue("Encoding pixels must be composited above the unchanged panel, changed=$changed overlap=$overlap", changed > 12)
        } finally { current.recycle() }
    }

    private fun assertCursorBlinks(editor: EditText, suffix: String) {
        val region = onMainValue {
            val visible = screenBounds(editor)
            val position = IntArray(2).also(editor::getLocationOnScreen)
            val layout = checkNotNull(editor.layout)
            val cursorX = position[0] + editor.totalPaddingLeft - editor.scrollX +
                layout.getPrimaryHorizontal(editor.selectionStart).toInt()
            // Only inspect the caret column. Sampling the entire field can include a few
            // primary-colored pixels from its rounded focus outline while the cursor is off.
            Rect(cursorX - context.dp(2), visible.top + context.dp(8),
                cursorX + context.dp(4), visible.bottom - context.dp(8)).apply {
                assertTrue(intersect(visible))
            }
        }
        val cursorColor = editor.context.settingsColor(androidx.appcompat.R.attr.colorPrimary)
        val samples = mutableListOf<Int>()
        repeat(7) {
            instrumentation.waitForIdleSync()
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                var count = 0
                for (y in region.top until region.bottom) for (x in region.left until region.right) {
                    if (bitmap.getPixel(x, y) == cursorColor) count++
                }
                samples += count
            } finally { bitmap.recycle() }
            SystemClock.sleep(180)
        }
        assertTrue("The actual caret must show and hide in the unfocused $suffix editor: $samples region=$region color=${Integer.toHexString(cursorColor)}",
            samples.any { it > 0 } && samples.any { it == 0 })
    }

    private fun assertClipboardToolbarSwitchKeepsPanelVisible(service: FcitxInputMethodService, local: EditText, suffix: String) {
        val dialog = onMainValue { checkNotNull(activeDialog(service)) }
        val localInput = service.jsAppInputConnection
        val application = service.applicationInputConnection
        val originalText = onMainValue { local.text.toString() }
        val alphaChanges = mutableListOf<Float>()
        val originalCallback = dialog.window!!.callback
        onMain {
            dialog.window!!.callback = object : Window.Callback by originalCallback {
                override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) {
                    alphaChanges += attrs.alpha
                    originalCallback.onWindowAttributesChanged(attrs)
                }
            }
        }
        try {
            val button = onMainValue {
                descendants(service.inputView!!).first { it.isShown &&
                    it.contentDescription == context.getString(R.string.clipboard) }
            }
            tap(button)
            await("The toolbar must open the ordinary clipboard beneath $suffix") {
                inputWindows(service.inputView!!).currentWindowOrNull() is ClipboardWindow
            }
            instrumentation.waitForIdleSync(); SystemClock.sleep(300)
            onMain {
                assertSame(dialog, activeDialog(service))
                assertEquals("A visible $suffix must not become transparent on a clipboard page switch: $alphaChanges",
                    1f, dialog.window!!.attributes.alpha, 0f)
                assertTrue("No intermediate window update may hide the panel: $alphaChanges", alphaChanges.all { it == 1f })
                assertSame(localInput, service.jsAppInputConnection)
                assertSame(application, service.applicationInputConnection)
                assertEquals(originalText, local.text.toString())
            }
            capture("$suffix-clipboard-switch")
            val back = onMainValue {
                descendants(service.inputView!!).first { it.isShown &&
                    it.contentDescription == context.getString(R.string.back_to_keyboard) }
            }
            tap(back)
            await("The clipboard back button must return to keys without closing $suffix") {
                inputWindows(service.inputView!!).currentWindowOrNull() is KeyboardWindow
            }
            onMain {
                assertSame(dialog, activeDialog(service))
                assertTrue(alphaChanges.all { it == 1f })
            }
            assertCursorBlinks(local, "$suffix-after-clipboard-switch")
        } finally { onMain { dialog.window!!.callback = originalCallback } }
    }

    private fun exerciseOtherEditors(service: FcitxInputMethodService, application: EditText, suffix: String) {
        val assistant = onMainValue { JsVoiceProviders.all().first { spec -> spec.apps.any { it.id == "llm_assistant" } } }
        val permissionPreferences = context.getSharedPreferences("js_app_permissions", Context.MODE_PRIVATE)
        val permissionKey = "${assistant.id}/llm_assistant"
        val previousPermissions = permissionPreferences.getStringSet(permissionKey, null)?.toSet()
        try {
            onMain { ClipboardEditor.open(service, 2) }
            val clipboard = panelEditor(service)
            exerciseEncodingAndCursor(service, clipboard, application, "clipboard-editor-$suffix")
            capture("clipboard-editor-$suffix-cursor")
            tap(windows().first { it.isShown && it.tag == "cancel" })
            await("Cancelling clipboard editing must release the local editor") { service.jsAppInputConnection == null }
            onMain {
                JsAppPermissions.grant(assistant.id, "llm_assistant", assistant.apps.single { it.id == "llm_assistant" }.permissions)
                JsApps.open(service)
            }
            val assistantEditor = panelEditor(service)
            if (suffix == "light") assertClipboardToolbarSwitchKeepsPanelVisible(service, assistantEditor, "ai-assistant")
            exerciseEncodingAndCursor(service, assistantEditor, application, "ai-assistant-$suffix")
            val purpose = onMainValue {
                descendants(assistantEditor.rootView).filterIsInstance<MaterialAutoCompleteTextView>().single { view ->
                    generateSequence(view.parent) { it.parent }.filterIsInstance<View>().any { it.tag == "purpose" }
                }
            }
            tap(purpose)
            var list: ListView? = null
            await("The real assistant dropdown must be visible above the panel") {
                list = WindowInspector.getGlobalWindowViews().firstOrNull {
                    (it.layoutParams as? WindowManager.LayoutParams)?.type == 1004 && it.isShown
                }?.let { descendants(it).filterIsInstance<ListView>().firstOrNull() }
                list?.childCount?.let { it > 1 } == true
            }
            capture("ai-assistant-$suffix-dropdown-compact")
            onMain {
                val actual = checkNotNull(list)
                val minimum = context.dp(36)
                for (index in 0 until actual.childCount) {
                    val row = actual.getChildAt(index).findViewById<TextView>(android.R.id.text1)
                    assertTrue(row.height >= minimum)
                    if (suffix == "light") assertEquals("Normal dropdown rows must be compact", minimum, row.height)
                    assertEquals(context.dp(4), row.paddingTop)
                    assertEquals(context.dp(4), row.paddingBottom)
                }
                if (suffix == "large") assertTrue("Long dropdown labels must wrap at enlarged font size: " +
                    (0 until actual.childCount).joinToString { index ->
                        val row = actual.getChildAt(index).findViewById<TextView>(android.R.id.text1)
                        "${row.text}: lines=${row.lineCount}, maxLines=${row.maxLines}, width=${row.width}, height=${row.height}, " +
                            "layoutWidth=${row.layout.width}, ellipsizedWidth=${row.layout.ellipsizedWidth}, " +
                            "textSize=${row.textSize}, hscroll=${row.isHorizontallyScrollable}, paramsHeight=${row.layoutParams.height}"
                    },
                    (0 until actual.childCount).any { actual.getChildAt(it).findViewById<TextView>(android.R.id.text1).lineCount > 1 })
            }
            tap(onMainValue { checkNotNull(list).getChildAt(1) })
            val refreshed = panelEditor(service)
            assertCursorBlinks(refreshed, "ai-assistant-$suffix-after-selection")
            if (suffix == "light") {
                val assistantButton = onMainValue { descendants(service.inputView!!).first {
                    it.isShown && it.contentDescription == context.getString(R.string.ai_assistant)
                } }
                tap(assistantButton)
                await("Tapping the AI toolbar button again must close the existing session") {
                    service.jsAppInputConnection == null && activeDialog(service) == null
                }
                tap(assistantButton)
                val reopened = panelEditor(service)
                assertNotSame(refreshed, reopened)
                assertCursorBlinks(reopened, "ai-assistant-toolbar-reopened")
                tap(assistantButton)
            } else tap(windows().first { it.isShown && it.tag == "close" })
            await("Closing the assistant must release its local editor") { service.jsAppInputConnection == null }
        } finally {
            onMain { service.jsAppDismiss?.invoke() }
            permissionPreferences.edit().apply {
                if (previousPermissions == null) remove(permissionKey) else putStringSet(permissionKey, previousPermissions)
            }.commit()
        }
    }

    private fun assertPanel(service: FcitxInputMethodService, query: EditText, results: View) = onMain {
        val field = FcitxInputMethodService::class.java.getDeclaredField("showingDialog").apply { isAccessible = true }
        val dialog = field.get(service) as JsAppDialog
        val attrs = dialog.window!!.attributes
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA, attrs.type)
        val keyboard = service.inputView!!.keyboardView
        val location = IntArray(2).also(keyboard::getLocationOnScreen)
        assertTrue("Panel must remain above the keyboard", attrs.y + attrs.height <= location[1])
        assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(0, attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val resultBounds = Rect().also { assertTrue(results.getGlobalVisibleRect(it)) }
        val queryBounds = Rect().also { assertTrue(query.getGlobalVisibleRect(it)) }
        assertTrue("Search field must remain below the result viewport", queryBounds.top >= resultBounds.bottom)
        val panelBounds = Rect().also { assertTrue(query.rootView.getGlobalVisibleRect(it)) }
        ClipboardCategory.values().forEach { value ->
            val chip = category(query, value) as Chip
            val bounds = Rect().also { assertTrue(chip.getGlobalVisibleRect(it)) }
            assertTrue("Complete group must fit within left panel boundary", bounds.left >= panelBounds.left)
            assertTrue("Complete group must fit within right panel boundary", bounds.right <= panelBounds.right)
            assertEquals("Group must be completely visible", chip.width, bounds.width())
            assertEquals("Group must be completely visible", chip.height, bounds.height())
            assertEquals("Group label must be complete", 0, chip.layout.getEllipsisCount(0))
            assertTrue(chip.height >= (48 * context.resources.displayMetrics.density).toInt())
        }
    }
    private fun assertColors(query: EditText, results: androidx.recyclerview.widget.RecyclerView) = onMain {
        val panel = query.context
        fun color(attr: Int) = panel.settingsColor(attr)
        assertEquals(color(com.google.android.material.R.attr.colorOnSurface), query.currentTextColor)
        assertEquals(color(com.google.android.material.R.attr.colorOnSurfaceVariant), query.currentHintTextColor)
        val box = generateSequence(query.parent) { it.parent }.filterIsInstance<TextInputLayout>().first()
        assertEquals(color(com.google.android.material.R.attr.colorSurfaceContainerHigh), box.boxBackgroundColor)
        val close = descendants(query.rootView).filterIsInstance<MaterialButton>().single { it.tag == "clipboard_search_close" }
        assertEquals(color(com.google.android.material.R.attr.colorOnSurfaceVariant), close.iconTint!!.defaultColor)
        ClipboardCategory.values().forEach { value ->
            val chip = category(query, value) as Chip
            assertEquals(color(if (chip.isChecked) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorSurfaceContainerHighest), chip.chipBackgroundColor!!.defaultColor)
            assertEquals(color(if (chip.isChecked) com.google.android.material.R.attr.colorOnPrimary else com.google.android.material.R.attr.colorOnSurface), chip.currentTextColor)
            assertEquals(ColorUtils.setAlphaComponent(color(if (chip.isChecked) com.google.android.material.R.attr.colorOnPrimary else
                androidx.appcompat.R.attr.colorPrimary), 0x30), chip.rippleColor!!.defaultColor)
        }
        for (index in 0 until results.childCount) {
            val holder = results.getChildViewHolder(results.getChildAt(index)) as ClipboardAdapter.ViewHolder
            assertEquals(color(com.google.android.material.R.attr.colorSurfaceBright), (holder.entryUi.root.background as GradientDrawable).color!!.defaultColor)
            assertEquals(color(com.google.android.material.R.attr.colorOnSurface), holder.entryUi.textView.currentTextColor)
            assertEquals(color(com.google.android.material.R.attr.colorOnSurfaceVariant), holder.entryUi.pin.imageTintList!!.defaultColor)
            assertEquals(color(com.google.android.material.R.attr.colorSurfaceBright), (holder.entryUi.preview.background as GradientDrawable).color!!.defaultColor)
            val placeholder = ClipboardEntryUi::class.java.getDeclaredField("imagePlaceholder").apply { isAccessible = true }
                .get(holder.entryUi) as ImageView
            assertEquals(color(com.google.android.material.R.attr.colorSurfaceBright), (placeholder.background as GradientDrawable).color!!.defaultColor)
        }
    }
    private fun assertMediaCardSurfaces(query: EditText, results: androidx.recyclerview.widget.RecyclerView) = onMain {
        val expected = query.context.settingsColor(com.google.android.material.R.attr.colorSurfaceBright)
        for (index in 0 until results.childCount) {
            val card = results.getChildAt(index)
            val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
            try {
                card.draw(Canvas(bitmap))
                assertEquals("Preview margin must use the same surface as the whole card", expected,
                    bitmap.getPixel(context.dp(3), card.height / 2))
                assertEquals("Media card must not have a differently colored top strip", expected,
                    bitmap.getPixel(card.width / 2, context.dp(3)))
            } finally { bitmap.recycle() }
        }
    }
    private fun fixtureImage(files: MutableList<File>, suffix: String, width: Int, height: Int): String {
        val file = File(context.cacheDir, "$name-$suffix.png").also { files += it }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawRect(width * .2f, height * .2f, width * .8f, height * .8f,
                Paint().apply { color = Color.rgb(41, 150, 160) })
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return Uri.fromFile(file).toString()
    }
    private fun longPress(view: View) {
        val position = onMainValue { IntArray(2).also(view::getLocationOnScreen) }
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, position[0] + view.width / 2f, position[1] + view.height / 2f, 0)
        try { instrumentation.sendPointerSync(down) } finally { down.recycle() }
        SystemClock.sleep(700)
        val up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, position[0] + view.width / 2f, position[1] + view.height / 2f, 0)
        try { instrumentation.sendPointerSync(up) } finally { up.recycle() }
        instrumentation.waitForIdleSync()
    }
    private fun tapKey(label: String) {
        var key: KeyView? = null
        await("Missing real soft key $label") {
            key = windows().filterIsInstance<KeyView>().firstOrNull {
                it.statisticsKeyLabel.equals(label, ignoreCase = true) && it.isShown && it.isEnabled
            }; key != null
        }
        tap(checkNotNull(key))
    }
    private fun tap(view: View) {
        onMain { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
        instrumentation.waitForIdleSync()
        val bounds = onMainValue { Rect().also {
            assertTrue(view.getLocalVisibleRect(it)); val position = IntArray(2).also(view::getLocationOnScreen); it.offset(position[0], position[1])
        } }
        val now = SystemClock.uptimeMillis()
        for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
            val event = MotionEvent.obtain(now, now + index * 40L, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
        }
        instrumentation.waitForIdleSync()
    }
    private fun capture(fileName: String) {
        instrumentation.waitForIdleSync()
        // The adapter's snapshot can update before Choreographer and the compositor draw it.
        SystemClock.sleep(150)
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(context.getExternalFilesDir(null), "clipboard-search-diagnostics").apply { mkdirs() }
            File(directory, "$fileName.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private fun profileCommand(action: String, profile: String) = RawConfig(arrayOf(RawConfig("Action", action), RawConfig("Name", profile)))
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMainValue(condition)) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(40)
        }
        capture("clipboard-search-failure")
        val state = onMainValue {
            windows().filter { it.isShown }.joinToString("\n") {
                val bounds = Rect(); it.getGlobalVisibleRect(bounds)
                "${it.javaClass.simpleName} $bounds tag=${it.tag} description=${it.contentDescription} " +
                    (if (it is TextView) "text=${it.text.take(160)}" else "")
            }
        }
        File(context.getExternalFilesDir(null), "clipboard-search-diagnostics/failure-views.txt").writeText(state)
        fail(message)
    }
    private fun windows() = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> onMainValue(block: () -> T): T {
        var result: T? = null; onMain { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
    }
    private fun restoreSetting(table: String, key: String, value: String) {
        if (value.matches(Regex("-?\\d+(?:\\.\\d+)?"))) shell("settings put $table $key $value")
        else shell("settings delete $table $key")
    }
}
