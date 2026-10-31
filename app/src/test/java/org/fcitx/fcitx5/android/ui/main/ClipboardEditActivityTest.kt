/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.content.ClipData
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClipboardEditActivityTest {
    private lateinit var database: ClipboardDatabase
    private lateinit var databaseFile: File
    private lateinit var dao: GatedDao
    private val controllers = mutableListOf<ActivityController<ClipboardEditActivity>>()
    private val first = ClipboardEntry(id = 1, text = "first", timestamp = 1)
    private val second = ClipboardEntry(id = 2, text = "second", timestamp = 2)

    /** Gates only scheduling; every accepted write still executes the generated Room DAO. */
    private class GatedDao(private val source: ClipboardDao) : ClipboardDao by source {
        val writes = mutableListOf<Pair<Int, String>>()
        val writeGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val readGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val failWrites = mutableSetOf<Int>()
        val failReads = mutableSetOf<Int>()
        override suspend fun updateText(id: Int, text: String): Int {
            writes += id to text
            writeGates[id]?.await()
            if (failWrites.remove(id)) throw IOException("fixture write failure")
            return source.updateText(id, text)
        }
        override suspend fun get(id: Int): ClipboardEntry? {
            if (failReads.remove(id)) throw IOException("fixture read failure")
            // Model a late result from a source that cannot cancel its underlying read.
            return readGates[id]?.let { gate ->
                withContext(NonCancellable) { gate.await(); source.get(id) }
            } ?: source.get(id)
        }
    }

    @Before fun setUp() {
        databaseFile = File(appContext.cacheDir, "clipboard-edit-${UUID.randomUUID()}.db")
        val direct = Executor { it.run() }
        database = Room.databaseBuilder(org.fcitx.fcitx5.android.testing.hostDatabaseContext(appContext), ClipboardDatabase::class.java, databaseFile.path)
            .setQueryExecutor(direct).setTransactionExecutor(direct)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        runBlocking { database.clipboardDao().insert(first); database.clipboardDao().insert(second) }
        dao = GatedDao(database.clipboardDao())
        ReflectionHelpers.setStaticField(ClipboardManager::class.java, "clbDao", dao)
        ClipboardManager.lastEntry = first
        appContext.clipboardManager.setPrimaryClip(ClipData.newPlainText("", "system original"))
    }

    @After fun tearDown() {
        controllers.toList().forEach(::destroy)
        dao.readGates.values.forEach { it.complete(Unit) }
        dao.writeGates.values.forEach { it.complete(Unit) }
        idle()
        database.close()
        SQLiteDatabase.deleteDatabase(databaseFile)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun intent(id: Int) = Intent().putExtra(ClipboardEditActivity.ENTRY_ID, id)
    private fun open(id: Int = 1): ClipboardEditActivity {
        val controller = Robolectric.buildActivity(ClipboardEditActivity::class.java, intent(id)).setup()
        controllers += controller
        idle()
        return controller.get()
    }
    private fun destroy(controller: ActivityController<ClipboardEditActivity>) {
        controller.pause().stop().destroy()
        controllers.remove(controller)
    }
    private fun newIntent(activity: ClipboardEditActivity, id: Int) {
        controllers.single { it.get() === activity }.newIntent(intent(id))
        idle()
    }
    private fun editor(activity: ClipboardEditActivity) = activity.findViewById<EditText>(R.id.clipboard_edit_text)
    private fun button(activity: ClipboardEditActivity, id: Int = R.id.clipboard_edit_ok) = activity.findViewById<TextView>(id)
    private fun click(activity: ClipboardEditActivity, id: Int = R.id.clipboard_edit_ok) {
        button(activity, id).performClick()
        idle()
    }
    private fun textOnDisk(id: Int): String = SQLiteDatabase.openDatabase(
        databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT text FROM clipboard WHERE id=?", arrayOf(id.toString())).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
    }
    private fun clipboardText() = appContext.clipboardManager.primaryClip!!.getItemAt(0).text.toString()

    @Test fun confirmWaitsForTheRealRoomWriteBeforeFinishing() {
        val gate = CompletableDeferred<Unit>().also { dao.writeGates[1] = it }
        val activity = open()
        editor(activity).setText("edited")
        click(activity)
        assertFalse(activity.isFinishing)
        assertFalse(button(activity).isEnabled)
        assertEquals("first", textOnDisk(1))
        assertEquals("first", ClipboardManager.lastEntry!!.text)
        gate.complete(Unit); idle()
        assertTrue(activity.isFinishing)
        assertEquals("edited", textOnDisk(1))
        assertEquals("edited", ClipboardManager.lastEntry!!.text)
    }

    @Test fun copyPublishesOnlyAfterTheDatabaseWriteSucceeds() {
        val gate = CompletableDeferred<Unit>().also { dao.writeGates[1] = it }
        val activity = open()
        editor(activity).setText("copy edit")
        click(activity, R.id.clipboard_edit_copy)
        assertEquals("system original", clipboardText())
        gate.complete(Unit); idle()
        assertEquals("copy edit", textOnDisk(1))
        assertEquals("copy edit", clipboardText())
        assertTrue(activity.isFinishing)
    }

    @Test fun aConfirmedWriteAndCopySurviveTheFloatingActivityBeingDestroyed() {
        val gate = CompletableDeferred<Unit>().also { dao.writeGates[1] = it }
        val activity = open()
        editor(activity).setText("survives close")
        click(activity, R.id.clipboard_edit_copy)
        destroy(controllers.single())
        gate.complete(Unit); idle()
        assertEquals("survives close", textOnDisk(1))
        assertEquals("survives close", clipboardText())
    }

    @Test fun duplicateConfirmDoesNotQueueTwoWrites() {
        val gate = CompletableDeferred<Unit>().also { dao.writeGates[1] = it }
        val activity = open()
        editor(activity).setText("once")
        click(activity); click(activity); click(activity, R.id.clipboard_edit_copy)
        assertEquals(listOf(1 to "once"), dao.writes)
        gate.complete(Unit); idle()
        assertEquals("once", textOnDisk(1))
        assertEquals("system original", clipboardText())
    }

    @Test fun aWriteFailureKeepsTheDraftCacheAndClipboardAndAllowsRetry() {
        dao.failWrites += 1
        val activity = open()
        editor(activity).setText("retry this")
        click(activity, R.id.clipboard_edit_copy)
        assertFalse(activity.isFinishing)
        assertEquals("retry this", editor(activity).text.toString())
        assertEquals("first", textOnDisk(1))
        assertEquals("first", ClipboardManager.lastEntry!!.text)
        assertEquals("system original", clipboardText())
        assertEquals(activity.getString(R.string.save_failed), ShadowToast.getTextOfLatestToast())
        assertTrue(button(activity).isEnabled)
        click(activity)
        assertTrue(activity.isFinishing)
        assertEquals("retry this", textOnDisk(1))
    }

    @Test fun anEntryDeletedDuringEditingCannotBeSilentlySavedOrCopied() {
        val activity = open()
        database.openHelper.writableDatabase.execSQL("UPDATE clipboard SET deleted=1 WHERE id=1")
        editor(activity).setText("must not resurrect")
        click(activity, R.id.clipboard_edit_copy)
        assertFalse(activity.isFinishing)
        assertEquals("first", textOnDisk(1))
        assertEquals("system original", clipboardText())
        assertEquals("first", ClipboardManager.lastEntry!!.text)
    }

    @Test fun controlsStayDisabledUntilTheRequestedEntryLoads() {
        val gate = CompletableDeferred<Unit>().also { dao.readGates[1] = it }
        val activity = open()
        assertFalse(button(activity).isEnabled)
        click(activity)
        assertTrue(dao.writes.isEmpty())
        gate.complete(Unit); idle()
        assertEquals("first", editor(activity).text.toString())
        assertTrue(button(activity).isEnabled)
    }

    @Test fun aLateReadForAnOldIntentCannotOverwriteTheNewDraft() {
        val gate = CompletableDeferred<Unit>().also { dao.readGates[1] = it }
        val activity = open()
        newIntent(activity, 2)
        editor(activity).setText("new draft")
        gate.complete(Unit); idle()
        assertEquals("new draft", editor(activity).text.toString())
        assertEquals(2, activity.intent.getIntExtra(ClipboardEditActivity.ENTRY_ID, -1))
    }

    @Test fun anOldSaveCannotFinishOrRetargetTheNewIntent() {
        val gate = CompletableDeferred<Unit>().also { dao.writeGates[1] = it }
        val activity = open()
        editor(activity).setText("saved first")
        click(activity)
        newIntent(activity, 2)
        editor(activity).setText("unsaved second")
        gate.complete(Unit); idle()
        assertEquals("saved first", textOnDisk(1))
        assertEquals("second", textOnDisk(2))
        assertEquals("unsaved second", editor(activity).text.toString())
        assertFalse(activity.isFinishing)
        assertTrue(button(activity).isEnabled)
    }

    @Test fun anOldCompletionCannotEnableControlsDuringTheNewSave() {
        val firstGate = CompletableDeferred<Unit>().also { dao.writeGates[1] = it }
        val secondGate = CompletableDeferred<Unit>().also { dao.writeGates[2] = it }
        val activity = open()
        editor(activity).setText("first save"); click(activity)
        newIntent(activity, 2)
        editor(activity).setText("second save"); click(activity)
        firstGate.complete(Unit); idle()
        assertFalse(activity.isFinishing)
        assertFalse(button(activity).isEnabled)
        secondGate.complete(Unit); idle()
        assertEquals("first save", textOnDisk(1))
        assertEquals("second save", textOnDisk(2))
        assertTrue(activity.isFinishing)
    }

    @Test fun aSaveCannotReplaceANewerLastEntry() {
        val gate = CompletableDeferred<Unit>().also { dao.writeGates[1] = it }
        val write = ClipboardManager.saveEditedText(1, "saved first", false)
        idle()
        ClipboardManager.lastEntry = second
        gate.complete(Unit); idle()
        assertTrue(write.isCompleted)
        assertEquals("saved first", textOnDisk(1))
        assertSame(second, ClipboardManager.lastEntry)
    }

    @Test fun anInitiallyMissingEntryClosesWithoutWriting() {
        val activity = open(999)
        assertTrue(activity.isFinishing)
        assertFalse(button(activity).isEnabled)
        assertTrue(dao.writes.isEmpty())
        assertEquals(activity.getString(R.string.clipboard_edit_entry_missing), ShadowToast.getTextOfLatestToast())
    }

    @Test fun aLoadFailureIsReportedWithoutLeavingAnEditableInvalidEntry() {
        dao.failReads += 1
        val activity = open()
        assertTrue(activity.isFinishing)
        assertFalse(button(activity).isEnabled)
        assertTrue(dao.writes.isEmpty())
        assertEquals(activity.getString(R.string.clipboard_edit_load_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test fun cancellingAnUnconfirmedDraftNeverWritesIt() {
        val activity = open()
        editor(activity).setText("discard this")
        click(activity, R.id.clipboard_edit_cancel)
        assertTrue(activity.isFinishing)
        assertTrue(dao.writes.isEmpty())
        assertEquals("first", textOnDisk(1))
    }
}
