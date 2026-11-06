/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.clipboard

import android.app.Activity
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.voice.js.JsAppDialog
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class ClipboardEditorPanelTest {
    private val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
    private val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
    private val engineJobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val originalEditor = EditText(host.get()).apply { setText("原应用内容"); setSelection(text.length) }
    private val keyboard = View(host.get())
    private lateinit var database: ClipboardDatabase
    private lateinit var dao: GatedDao
    private var session: ClipboardEditor.Session? = null
    private class GatedDao(private val delegate: ClipboardDao) : ClipboardDao by delegate {
        var writeGate: CompletableDeferred<Unit>? = null
        var failWrite = false
        override suspend fun updateText(id: Int, text: String): Int {
            writeGate?.await()
            if (failWrite) throw IOException("Injected write failure")
            return delegate.updateText(id, text)
        }
    }
    @Before fun setup() {
        host.get().setContentView(FrameLayout(host.get()).apply {
            addView(originalEditor, FrameLayout.LayoutParams(-1, 100))
            addView(keyboard, FrameLayout.LayoutParams(-1, 300, Gravity.BOTTOM))
        })
        val direct = Executor { it.run() }
        database = Room.inMemoryDatabaseBuilder(org.fcitx.fcitx5.android.testing.hostDatabaseContext(appContext), ClipboardDatabase::class.java)
            .setQueryExecutor(direct).setTransactionExecutor(direct).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        dao = GatedDao(database.clipboardDao())
        ReflectionHelpers.setStaticField(ClipboardManager::class.java, "clbDao", dao)
        runBlocking { dao.insert(ClipboardEntry(id = 1, text = "待编辑文本", timestamp = 1)) }
        appContext.clipboardManager.setPrimaryClip(ClipData.newPlainText("", "系统剪贴板"))
        ReflectionHelpers.setField(service, "mStartedInputConnection", originalEditor.onCreateInputConnection(EditorInfo()))
        service.editorSession.start(EditorSessionTracker.Editor("fixture.app", 1, 1, 0), false)
        service.fcitx = object : FcitxConnection {
            override val lifecycleScope get() = engineJobs
            override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = error("Native runtime is not part of this editor test")
            override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = error("Native runtime is not part of this editor test")
            override fun runIfReady(block: suspend FcitxAPI.() -> Unit) = Unit
        }
        idle()
    }
    @After fun cleanup() {
        session?.dialog?.dismiss()
        dao.writeGate?.complete(Unit)
        idle()
        ReflectionHelpers.getField<Channel<Job>>(service, "jobs").cancel()
        engineJobs.cancel()
        database.close()
        host.pause().stop().destroy()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun open(): JsAppDialog = ClipboardEditor.Session(service, 1, { keyboard }, { it.show() }).also {
        session = it; it.start(); idle()
    }.dialog
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun button(dialog: JsAppDialog, id: String) = descendants(dialog.findViewById(android.R.id.content))
        .filterIsInstance<MaterialButton>().single { it.tag == id }
    private fun stored() = runBlocking { dao.get(1)!!.text }

    @Test fun editAndCancelKeepTheOriginalAppAndKeyboardTarget() {
        val connection = service.applicationInputConnection
        val dialog = open()
        assertTrue(dialog.isShowing)
        assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        service.commitText("，继续编辑")
        assertEquals("待编辑文本，继续编辑", dialog.values()["clipboard"]?.toString()?.trim('"'))
        assertEquals("原应用内容", originalEditor.text.toString())
        assertSame(connection, service.applicationInputConnection)
        assertNull(shadowOf(service).nextStartedActivity)
        val root = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(330, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        System.getenv("UI_POLISH_PREVIEW_DIR")?.let { dir ->
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(dir, "clipboard-floating.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        button(dialog, "cancel").performClick(); idle()
        assertNull(service.jsAppInputConnection)
        assertEquals("待编辑文本", stored())
        assertEquals("原应用内容", originalEditor.text.toString())
    }

    @Test fun confirmedCopySurvivesPanelCloseAndPublishesOnlyAfterTheRoomWrite() {
        val dialog = open()
        service.commitText("已修改")
        val gate = CompletableDeferred<Unit>().also { dao.writeGate = it }
        button(dialog, "copy").performClick(); idle()
        assertEquals("待编辑文本", stored())
        assertEquals("系统剪贴板", appContext.clipboardManager.primaryClip!!.getItemAt(0).text.toString())
        dialog.dismiss()
        gate.complete(Unit); idle()
        assertEquals("待编辑文本已修改", stored())
        assertEquals("待编辑文本已修改", appContext.clipboardManager.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun failedWriteKeepsDraftAndAnExpiredEditorCannotSave() {
        val dialog = open()
        service.commitText("，保留草稿")
        dao.failWrite = true
        button(dialog, "confirm").performClick(); idle()
        assertTrue(dialog.isShowing)
        assertEquals("待编辑文本", stored())
        assertTrue(dialog.values()["clipboard"].toString().contains("保留草稿"))
        service.editorSession.end()
        dao.failWrite = false
        button(dialog, "confirm").performClick(); idle()
        assertFalse(dialog.isShowing)
        assertEquals("待编辑文本", stored())
    }
}
