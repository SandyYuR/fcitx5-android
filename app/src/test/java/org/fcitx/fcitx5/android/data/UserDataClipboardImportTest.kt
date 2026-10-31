package org.fcitx.fcitx5.android.data

import android.database.sqlite.SQLiteDatabase
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class, org.fcitx.fcitx5.android.testing.ShortCacheContextShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UserDataClipboardImportTest {
    private fun archive(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val metadata = """{"packageName":"org.fcitx.fcitx5.android","versionCode":1,"versionName":"fixture","exportTime":0}"""
            for ((name, bytes) in mapOf("metadata.json" to metadata.toByteArray()) + entries) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun walArchive(futureVersion: Boolean = false): ByteArray {
        val original = File(appContext.cacheDir, "fixture-${UUID.randomUUID()}.db")
        try {
            return SQLiteDatabase.openOrCreateDatabase(original, null).use { db ->
                assertTrue(db.enableWriteAheadLogging())
                db.execSQL("CREATE TABLE clipboard (id INTEGER PRIMARY KEY, text TEXT NOT NULL)")
                db.version = 7
                db.execSQL("INSERT INTO clipboard VALUES (1, 'checkpointed')")
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                db.execSQL("INSERT INTO clipboard VALUES (2, 'newer committed record')")
                if (futureVersion) db.version = 8
                archive(mapOf(
                    "databases/clbdb" to original.readBytes(),
                    "databases/clbdb-wal" to File(original.path + "-wal").readBytes()
                ))
            }
        } finally { SQLiteDatabase.deleteDatabase(original) }
    }

    @Test fun legacyZipRetainsWalRecordsAndRemovesSidecarsBeforeRestore() {
        UserDataManager.prepareImport(walArchive().inputStream()).use { prepared ->
            val file = File(prepared.directory, "databases/clbdb")
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT count(*) FROM clipboard", null).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            }
            assertFalse(File(file.path + "-wal").exists())
            assertFalse(File(file.path + "-shm").exists())
        }
    }

    @Test fun futureVersionInWalIsRejectedBeforeChangingInstalledData() {
        val installed = appContext.getDatabasePath("clbdb").apply { parentFile!!.mkdirs(); writeText("installed data") }
        assertTrue(runCatching { UserDataManager.prepareImport(walArchive(true).inputStream()) }.isFailure)
        assertEquals("installed data", installed.readText())
        assertFalse(appContext.cacheDir.listFiles()!!.any { it.name.startsWith("user-data-import-") })
    }

    @Test fun corruptClipboardDatabaseDoesNotProduceAPreparedImport() {
        assertTrue(runCatching {
            UserDataManager.prepareImport(archive(mapOf("databases/clbdb" to "corrupt".toByteArray())).inputStream())
        }.isFailure)
        assertFalse(appContext.cacheDir.listFiles()!!.any { it.name.startsWith("user-data-import-") })
    }

    @Test fun orphanWalCannotSilentlyReplaceClipboardHistory() {
        assertTrue(runCatching {
            UserDataManager.prepareImport(archive(mapOf("databases/clbdb-wal" to byteArrayOf(1))).inputStream())
        }.isFailure)
    }

    @Test fun restoringAnotherDatabasePreservesWalOfAnUnreplacedDatabase() {
        val retained = appContext.getDatabasePath("retained.db").apply { parentFile!!.mkdirs(); writeText("base") }
        val retainedWal = File(retained.path + "-wal").apply { writeText("committed unreplaced WAL") }
        val replaced = appContext.getDatabasePath("incoming.db").apply { writeText("old") }
        val replacedWal = File(replaced.path + "-wal").apply { writeText("stale WAL") }
        UserDataManager.prepareImport(archive(mapOf("databases/incoming.db" to "incoming".toByteArray())).inputStream()).use {
            UserDataManager.restore(it, RimeProfileRestorePlan(emptyMap()), closeClipboard = false).getOrThrow()
        }
        assertEquals("committed unreplaced WAL", retainedWal.readText())
        assertEquals("incoming", replaced.readText()); assertFalse(replacedWal.exists())
    }

    @Test fun importedPreferencesReplaceTheOldAtomicBackupToo() {
        val directory = File(appContext.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
        val name = "${appContext.packageName}_preferences.xml"
        val main = File(directory, name).apply { writeText("<map><string name=\"v\">old</string></map>") }
        val backup = File(directory, "$name.bak").apply { writeText("<map><string name=\"v\">older</string></map>") }
        val incoming = "<map><string name=\"v\">imported</string></map>"
        val entries = mapOf("shared_prefs/org.fcitx.fcitx5.android_preferences.xml.bak" to incoming.toByteArray())
        UserDataManager.prepareImport(archive(entries).inputStream()).use {
            UserDataManager.restore(it, RimeProfileRestorePlan(emptyMap()), closeClipboard = false).getOrThrow()
        }
        assertEquals(incoming, main.readText()); assertFalse(backup.exists())
    }
}
