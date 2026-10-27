/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.utils.FileUtil
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Disposable-device host protocol: start the private restore Activity after prepare.
 * That intentionally kills this runner. Android may then stop the worker too when
 * it tears down instrumentation. Once that teardown ends, relaunch the same token
 * if its request remains; the final handoff then kills an ordinary main process.
 * Run verify only after the request is consumed and the success dialog is visible.
 */
class UserDataRestoreProcessAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val receipt get() = File(context.filesDir, "restore-process-receipt.json")

    @Test fun isolatedRestoreReplacesCachedPreferencesAndRetainsUnreplacedWal(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Only the explicitly selected disposable emulator",
            args.getString("allowDisposableDevice") == "true" && BuildConfig.DEBUG)
        when (args.getString("restorePhase")) {
            "prepare" -> {
                require(!receipt.exists()) { "Clean up the previous disposable restore fixture first" }
                val id = UUID.randomUUID().toString()
                val prefsName = "restore-process-$id"
                val databaseName = "$prefsName.db"
                val importedName = "restore-imported-$id.db"
                val prefs = context.getSharedPreferences(prefsName, 0)
                assertTrue(prefs.edit().putString("marker", "old cached value").commit())
                val database = context.openOrCreateDatabase(databaseName, 0, null)
                assertTrue(database.enableWriteAheadLogging())
                database.execSQL("CREATE TABLE kept(value TEXT NOT NULL)")
                database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { assertTrue(it.moveToFirst()) }
                database.execSQL("INSERT INTO kept VALUES ('committed WAL row')")
                val imported = File(context.cacheDir, "$importedName.tmp")
                SQLiteDatabase.openOrCreateDatabase(imported, null).use {
                    it.disableWriteAheadLogging()
                    it.execSQL("CREATE TABLE imported(value TEXT NOT NULL)")
                    it.execSQL("INSERT INTO imported VALUES ('new database')")
                }
                val bytes = ByteArrayOutputStream()
                ZipOutputStream(bytes).use { zip ->
                    val entries = mapOf(
                        "metadata.json" to """{"packageName":"${context.packageName}","versionCode":1,"versionName":"disposable","exportTime":0}""".toByteArray(),
                        "shared_prefs/$prefsName.xml" to "<map><string name=\"marker\">imported value</string></map>".toByteArray(),
                        "databases/$importedName" to imported.readBytes())
                    for ((name, value) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() }
                }
                imported.delete()
                val backup = UserDataManager.prepareImport(bytes.toByteArray().inputStream())
                val connectionName = "RestoreProcessProbe-$id"
                val connection = FcitxDaemon.connect(connectionName)
                var stopped = false
                try {
                    connection.runOnReady { save() }
                    val plan = RimeProfileRestorePlan(emptyMap())
                    UserDataManager.validateRestore(backup, plan)
                    withContext(Dispatchers.IO) { FcitxDaemon.beginUserDataRestore() }
                    stopped = true
                    val token = UserDataRestoreRuntime.prepare(context, backup, plan)
                    val wal = File(context.getDatabasePath(databaseName).path + "-wal")
                    assertTrue(wal.length() > 0)
                    FileUtil.writeAtomically(receipt, JSONObject().apply {
                        put("token", token); put("pid", Process.myPid()); put("prefs", prefsName)
                        put("database", databaseName); put("imported_database", importedName)
                        put("wal_bytes_before", wal.length()); put("old_cached_value", prefs.getString("marker", null))
                        put("runtime_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                    }.toString(2))
                    instrumentation.sendStatus(0, Bundle().apply { putString("restore_handoff_ready", "true") })
                    repeat(1200) {
                        database.rawQuery("SELECT value FROM kept", null).use { assertTrue(it.moveToFirst()) }
                        delay(1000) // Keep the old connection alive and its WAL uncheckpointed until SIGKILL.
                    }
                    fail("Host did not start the isolated restore Activity")
                } finally {
                    if (stopped) FcitxDaemon.finishFailedUserDataRestore()
                    FcitxDaemon.disconnect(connectionName)
                    database.close(); backup.close()
                }
            }
            "verify" -> {
                val before = JSONObject(receipt.readText())
                val prefsName = before.getString("prefs")
                require(prefsName.matches(Regex("restore-process-[0-9a-f-]{36}")))
                assertNotEquals(before.getInt("pid"), Process.myPid())
                assertEquals("imported value", context.getSharedPreferences(prefsName, 0).getString("marker", null))
                fun row(name: String, table: String): String = SQLiteDatabase.openDatabase(
                    context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT value FROM $table", null).use { assertTrue(it.moveToFirst()); it.getString(0) }
                }
                assertEquals("committed WAL row", row(before.getString("database"), "kept"))
                assertEquals("new database", row(before.getString("imported_database"), "imported"))
                assertFalse(File(context.noBackupFilesDir, "user-data-restore.json").exists())
                assertFalse(File(context.noBackupFilesDir, "restore-requests/${before.getString("token")}.json").exists())
                FileUtil.writeAtomically(File(context.filesDir, "restore-process-verified.json"), JSONObject().apply {
                    put("passed", true); put("before_pid", before.getInt("pid")); put("after_pid", Process.myPid())
                    put("preferences", "imported value"); put("old_wal_row", "committed WAL row")
                    put("new_database_row", "new database"); put("runtime_version", BuildConfig.BUILD_GIT_HASH)
                }.toString(2))
            }
            else -> error("Specify prepare or verify")
        }
    }
}
