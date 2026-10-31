package org.fcitx.fcitx5.android.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteSnapshotsTest {
    private lateinit var context: Context
    private lateinit var room: ClipboardDatabase
    private lateinit var directory: File
    private val source get() = room.openHelper.writableDatabase

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        room = Room.databaseBuilder(org.fcitx.fcitx5.android.testing.hostDatabaseContext(context), ClipboardDatabase::class.java, File(directory, "live.db").path)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { room.close(); directory.deleteRecursively() }

    private fun count(file: File): Int = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT count(*) FROM clipboard", null).use { it.moveToFirst(); it.getInt(0) }
    }

    private fun insert(text: String) = runBlocking { room.clipboardDao().insert(ClipboardEntry(text = text)) }

    private fun pendingWal() {
        insert("checkpointed")
        source.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        insert("committed only in WAL")
        assertTrue(File(source.path + "-wal").length() > 0)
    }

    @Test fun snapshotIncludesCommittedWalRowsAndOpensAsARealRoomDatabase() {
        pendingWal()
        val rawCopy = File(directory, "raw-copy.db")
        File(source.path).copyTo(rawCopy)
        assertEquals(1, count(rawCopy)) // Reproduce why copying the main file loses data.
        val snapshot = File(directory, "snapshot.db")
        SqliteSnapshots.write(source, snapshot)
        assertEquals(2, count(snapshot))
        assertFalse(File(snapshot.path + "-wal").exists())
        val restored = Room.databaseBuilder(org.fcitx.fcitx5.android.testing.hostDatabaseContext(context), ClipboardDatabase::class.java, snapshot.path)
            .allowMainThreadQueries().build()
        try {
                assertEquals("committed only in WAL", runBlocking { restored.clipboardDao().get(2) }!!.text)
        } finally { restored.close() }
        insert("written after snapshot")
        assertEquals(2, count(snapshot))
    }

    @Test fun oldBackupRecoversItsMatchingWalInsteadOfDiscardingIt() {
        pendingWal()
        val staged = File(directory, "staged.db")
        File(source.path).copyTo(staged)
        File(source.path + "-wal").copyTo(File(staged.path + "-wal"))
        File(staged.path + "-shm").writeText("unusable locks from another device")
        val recovered = File(directory, "recovered.db")
        SqliteSnapshots.recoverStaged(staged, recovered)
        assertEquals(2, count(recovered))
        assertEquals(2, source.query("SELECT count(*) FROM clipboard").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun versionCommittedOnlyToWalIsPreservedForDowngradeRejection() {
        pendingWal()
        source.execSQL("PRAGMA user_version=8")
        val staged = File(directory, "future.db")
        File(source.path).copyTo(staged)
        File(source.path + "-wal").copyTo(File(staged.path + "-wal"))
        val recovered = File(directory, "future-snapshot.db")
        SqliteSnapshots.recoverStaged(staged, recovered)
        SQLiteDatabase.openDatabase(recovered.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(8, it.version)
        }
    }

    @Test fun typedValuesAndAutoincrementSequenceSurviveSnapshot() {
        source.execSQL("CREATE TABLE backup_types (id INTEGER PRIMARY KEY AUTOINCREMENT, b BLOB, f REAL, n TEXT)")
        source.execSQL("CREATE INDEX backup_index ON backup_types(f)")
        source.execSQL("INSERT INTO backup_types(id,b,f,n) VALUES(100,?,1.25,NULL)", arrayOf(byteArrayOf(0, 1, -1)))
        source.execSQL("INSERT INTO backup_types(id,f) VALUES(200,2.0)")
        source.execSQL("DELETE FROM backup_types WHERE id=200")
        val snapshot = File(directory, "typed.db")
        SqliteSnapshots.write(source, snapshot)
        SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("SELECT b,f,n FROM backup_types WHERE id=100", null).use {
                assertTrue(it.moveToFirst())
                assertArrayEquals(byteArrayOf(0, 1, -1), it.getBlob(0))
                assertEquals(1.25, it.getDouble(1), 0.0)
                assertTrue(it.isNull(2))
            }
            db.execSQL("INSERT INTO backup_types(f) VALUES(3.0)")
            db.rawQuery("SELECT max(id) FROM backup_types", null).use { it.moveToFirst(); assertEquals(201, it.getInt(0)) }
            db.rawQuery("SELECT name FROM sqlite_master WHERE name='backup_index'", null).use { assertTrue(it.moveToFirst()) }
        }
    }

    @Test fun corruptStagedDatabaseFailsWithoutPublishingASnapshot() {
        val staged = File(directory, "broken.db").apply { writeText("not a SQLite database") }
        val snapshot = File(directory, "unpublished.db")
        assertTrue(runCatching { SqliteSnapshots.recoverStaged(staged, snapshot) }.isFailure)
        assertFalse(snapshot.exists())
    }

    @Test fun snapshotRefusesTheCallersWriteLock() {
        val snapshot = File(directory, "unsafe.db")
        room.runInTransaction {
            assertTrue(runCatching { SqliteSnapshots.write(source, snapshot) }.isFailure)
        }
        assertFalse(snapshot.exists())
    }
    @Test fun largeTextAndBlobSurviveWithoutFillingACursorWindow() {
        val text = "汉🙂".repeat(1024 * 1024)
        insert(text)
        source.execSQL("CREATE TABLE large_blob (b BLOB)")
        source.execSQL("INSERT INTO large_blob VALUES(zeroblob(3145728))")
        val snapshot = File(directory, "large.db")
        SqliteSnapshots.write(source, snapshot)
        SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT length(CAST(text AS BLOB)) FROM clipboard", null).use {
                assertTrue(it.moveToFirst()); assertEquals(text.toByteArray().size, it.getInt(0))
            }
            db.rawQuery("SELECT length(b), hex(substr(b,3145728,1)) FROM large_blob", null).use {
                assertTrue(it.moveToFirst()); assertEquals(3145728, it.getInt(0)); assertEquals("00", it.getString(1))
            }
        }
        source.query("PRAGMA journal_mode").use { assertTrue(it.moveToFirst()); assertEquals("wal", it.getString(0)) }
        insert("source still writable")
    }
}
