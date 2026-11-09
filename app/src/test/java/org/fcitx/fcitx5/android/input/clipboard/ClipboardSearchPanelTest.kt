/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageButton
import android.widget.PopupWindow
import android.graphics.drawable.GradientDrawable
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.clipboard.ClipboardCategory
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.testing.hostDatabaseContext
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClipboardSearchPanelTest {
    private val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
    private val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
    private val engineJobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val original = EditText(host.get()).apply { setText("原应用"); setSelection(text.length) }
    private val keyboard = View(host.get())
    private lateinit var database: ClipboardDatabase
    private val sessions = mutableListOf<ClipboardSearchController.Session>()

    @Before fun setup() {
        host.get().setContentView(FrameLayout(host.get()).apply {
            addView(original, FrameLayout.LayoutParams(-1, 100))
            addView(keyboard, FrameLayout.LayoutParams(-1, 300, Gravity.BOTTOM))
        })
        val executor = Executor { it.run() }
        database = Room.inMemoryDatabaseBuilder(hostDatabaseContext(appContext), ClipboardDatabase::class.java)
            .setQueryExecutor(executor).setTransactionExecutor(executor)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(ClipboardManager::class.java, "clbDao", database.clipboardDao())
        runBlocking {
            listOf(
                ClipboardEntry(id = 1, text = "Alpha本地", timestamp = 1),
                ClipboardEntry(id = 2, text = "Beta远端", source = ClipboardEntry.SOURCE_REMOTE, timestamp = 2),
                ClipboardEntry(id = 3, text = "Alpha置顶", pinned = true, timestamp = 3),
                ClipboardEntry(id = 4, text = "content://fixture/image.png", type = "image/png", timestamp = 4),
            ).forEach { database.clipboardDao().insert(it) }
        }
        ReflectionHelpers.setField(service, "mStartedInputConnection", original.onCreateInputConnection(EditorInfo()))
        service.editorSession.start(EditorSessionTracker.Editor("fixture.app", 1, 1, 0), false)
        service.fcitx = object : FcitxConnection {
            override val lifecycleScope get() = engineJobs
            override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = error("No native runtime in this panel fixture")
            override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = error("No native runtime in this panel fixture")
            override fun runIfReady(block: suspend FcitxAPI.() -> Unit) = Unit
        }
        idle()
    }
    @After fun cleanup() {
        sessions.forEach { it.dialog.dismiss() }
        idle()
        ReflectionHelpers.getField<Channel<Job>>(service, "jobs").cancel()
        engineJobs.cancel()
        database.close()
        host.pause().stop().destroy()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            // Keep a short-lived Snackbar alive while Room's real IO executor publishes a page.
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        assertTrue("Panel did not reach the expected state", condition())
    }
    private fun open(category: ClipboardCategory? = ClipboardCategory.All) = ClipboardSearchController.Session(
        service, category, keyboardView = { keyboard }, present = { it.show() }).also {
        sessions += it; it.start(); await { it.content.adapter.itemCount > 0 }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun category(session: ClipboardSearchController.Session, value: ClipboardCategory) =
        descendants(session.content.ui.categoryBar).filterIsInstance<TextView>().single {
            it.text.toString() == service.getString(value.titleRes)
        }
    private fun ids(session: ClipboardSearchController.Session) = session.content.adapter.snapshot().items.map { it.id }.toSet()
    private fun screenY(view: View) = IntArray(2).also(view::getLocationOnScreen)[1]

    @Test fun resultsAreVirtualizedAboveTheFixedSearchAndGroupControls() {
        val session = open()
        assertEquals(setOf(1, 2, 3, 4), ids(session))
        val attrs = session.dialog.window!!.attributes
        assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(0, attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertTrue(attrs.y + attrs.height <= screenY(keyboard))
        assertTrue(screenY(session.query) >= screenY(session.content.ui.root) + session.content.ui.root.height)
        assertTrue(screenY(session.content.ui.categoryBar) >= screenY(session.query) + session.query.height)
        val ancestors = generateSequence(session.content.ui.recyclerView.parent) { it.parent }
        assertFalse(ancestors.any { it is ScrollView })
        ClipboardCategory.values().forEach { assertTrue(category(session, it).height >= 48) }
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun storedGroupQueryChangesAndClearingKeepTheOriginalEditorUntouched() {
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
            .putString("clipboard_last_opened_category", ClipboardCategory.Remote.name).commit()
        val session = open(null)
        await { ids(session) == setOf(2) }
        assertTrue(category(session, ClipboardCategory.Remote).isSelected)
        category(session, ClipboardCategory.All).performClick()
        session.query.setText("Beta")
        session.query.setText("Alpha")
        await { ids(session) == setOf(1, 3) }
        category(session, ClipboardCategory.Local).performClick()
        await { ids(session) == setOf(1) }
        session.query.setText("")
        await { ids(session) == setOf(1) }
        service.commitText("😀")
        assertEquals("😀", session.query.text.toString())
        service.currentInputConnection!!.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
        assertEquals("", session.query.text.toString())
        assertEquals("原应用", original.text.toString())
        session.dialog.dismiss()
        assertNull(service.jsAppInputConnection)
        assertEquals(ClipboardCategory.Local.name, PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString("clipboard_last_opened_category", null))
    }

    @Test fun selectingAResultClosesThePanelAndPastesOnlyTheSelectedEntry() {
        val session = open()
        service.commitText("Alpha")
        await { ids(session) == setOf(1, 3) }
        val position = session.content.adapter.snapshot().items.indexOfFirst { it.id == 1 }
        await { session.content.ui.recyclerView.findViewHolderForAdapterPosition(position) != null }
        val card = session.content.ui.recyclerView.findViewHolderForAdapterPosition(position)!!.itemView
        val now = SystemClock.uptimeMillis()
        for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
            val touch = MotionEvent.obtain(now, now + index * 40L, action, card.width / 2f, card.height / 2f, 0)
            try { assertTrue(card.dispatchTouchEvent(touch)) } finally { touch.recycle() }
        }
        await { original.text.toString() == "原应用Alpha本地" }
        assertFalse(session.dialog.isShowing)
        assertNull(service.jsAppInputConnection)
        assertNull(service.jsAppDismiss)
    }

    @Test fun closingDuringAPressCancelsTheCardsPendingLongPress() {
        val session = open()
        await { session.content.ui.recyclerView.childCount > 0 }
        val card = session.content.ui.recyclerView.getChildAt(0)
        var longPresses = 0
        card.setOnLongClickListener { longPresses++; true }
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, card.width / 2f, card.height / 2f, 0)
        try { assertTrue(card.dispatchTouchEvent(down)) } finally { down.recycle() }
        session.dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(0, longPresses)
    }

    @Test fun expiredSessionsCannotPasteAndOldDismissalsCannotClearANewerPanel() {
        val old = open()
        val newer = open()
        val localConnection = service.jsAppInputConnection
        old.dialog.dismiss()
        assertSame(localConnection, service.jsAppInputConnection)
        val entry = newer.content.adapter.snapshot().items.first()
        service.commitText("local")
        assertEquals("local", newer.query.text.toString())
        service.editorSession.end()
        newer.content.adapter.onPaste(entry)
        idle()
        assertFalse(newer.dialog.isShowing)
        assertEquals("原应用", original.text.toString())
    }

    @Test fun shortWindowsKeepSearchAndGroupsReachableWithoutCoveringTheKeyboard() {
        keyboard.layoutParams = (keyboard.layoutParams as FrameLayout.LayoutParams).apply { height = 730 }
        idle()
        val session = open()
        val attrs = session.dialog.window!!.attributes
        assertTrue(attrs.y + attrs.height <= screenY(keyboard))
        val footer = descendants(session.dialog.findViewById(android.R.id.content)).filterIsInstance<ScrollView>().single()
        footer.fullScroll(View.FOCUS_DOWN); idle()
        assertTrue(session.content.ui.categoryBar.getGlobalVisibleRect(android.graphics.Rect()))
        footer.fullScroll(View.FOCUS_UP); idle()
        assertTrue(session.query.getGlobalVisibleRect(android.graphics.Rect()))
    }

    @Test fun darkPanelEditorsIconsAndUndoFollowThePanelThemeAndRestoreDeletedHistory() {
        val previous = SettingsUiPreferences.read(appContext)
        var session: ClipboardSearchController.Session? = null
        try {
            SettingsUiPreferences.save(appContext, previous.copy(mode = SettingsUiPreferences.MODE_DARK,
                palette = SettingsUiPreferences.PALETTE_AMBER, followKeyboardTheme = false))
            val active = open().also { session = it }
            val context = active.dialog.panelContext
            val colors = ClipboardColors.panel(context)
            assertEquals(context.settingsColor(com.google.android.material.R.attr.colorOnSurface), active.query.currentTextColor)
            assertEquals(context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceVariant), active.query.currentHintTextColor)
            val box = generateSequence(active.query.parent) { it.parent }.filterIsInstance<TextInputLayout>().first()
            assertEquals(context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainerHigh), box.boxBackgroundColor)
            active.query.setText("Alpha")
            await { ids(active) == setOf(1, 3) }
            val clear = box.findViewById<ImageButton>(com.google.android.material.R.id.text_input_end_icon)
            val endLayout = ReflectionHelpers.getField<Any>(box, "endLayout")
            assertEquals(colors.muted, ReflectionHelpers.getField<ColorStateList>(endLayout, "endIconTintList").defaultColor)
            val close = descendants(active.dialog.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
                .single { it.tag == "clipboard_search_close" }
            assertEquals(colors.muted, close.iconTint!!.defaultColor)
            assertEquals(colors.ripple, close.rippleColor!!.defaultColor)
            clear.performClick()
            await { ids(active) == setOf(1, 2, 3, 4) }
            active.content.adapter.onDelete(1)
            await { 1 !in ids(active) && ReflectionHelpers.getField<Snackbar?>(active.content, "snackbarInstance") != null }
            val snackbar = ReflectionHelpers.getField<Snackbar>(active.content, "snackbarInstance")
            assertEquals(context.settingsColor(com.google.android.material.R.attr.colorSurfaceInverse),
                ReflectionHelpers.getField<ColorStateList>(snackbar.view, "backgroundTint").defaultColor)
            assertEquals(context.settingsColor(com.google.android.material.R.attr.colorOnSurfaceInverse),
                snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).currentTextColor)
            val undo = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_action)
            assertEquals(context.settingsColor(com.google.android.material.R.attr.colorPrimaryInverse), undo.currentTextColor)
            undo.performClick()
            await { ids(active) == setOf(1, 2, 3, 4) }
            assertEquals("原应用", original.text.toString())
        } finally {
            session?.dialog?.dismiss()
            SettingsUiPreferences.save(appContext, previous)
        }
    }

    @Test fun nativeMenusUseTheMaterialMenuSurfaceInThePanelContext() {
        val previous = SettingsUiPreferences.read(appContext)
        try {
            SettingsUiPreferences.save(appContext, previous.copy(mode = SettingsUiPreferences.MODE_DARK,
                palette = SettingsUiPreferences.PALETTE_AMBER, followKeyboardTheme = false))
            val session = open()
            // Android's PopupMenu forwards this same platform style attribute to its PopupWindow.
            val popup = PopupWindow(session.query.context, null, android.R.attr.popupMenuStyle, 0)
            assertEquals(session.query.context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer),
                (popup.background as GradientDrawable).color!!.defaultColor)
        } finally {
            SettingsUiPreferences.save(appContext, previous)
        }
    }
}
