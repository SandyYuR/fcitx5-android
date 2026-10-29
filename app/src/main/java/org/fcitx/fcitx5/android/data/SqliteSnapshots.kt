/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException

/** Standalone SQLite snapshots, including committed rows still resident in WAL. */
internal object SqliteSnapshots {
    /** Copy through a separate connection; never change the live Room connection's WAL mode. */
    fun write(source: SupportSQLiteDatabase, destination: File) {
        check(!source.inTransaction()) { "Snapshot must own its transaction, not wait on the caller's write lock" }
        write(File(requireNotNull(source.path)), destination)
    }

    /** Only use on a private extracted backup, never on the live Room database. */
    fun recoverStaged(source: File, destination: File) {
        val shm = File(source.path + "-shm")
        if (shm.exists() && !shm.delete()) throw IOException("Cannot remove staged SQLite shared memory")
        write(source, destination)
    }

    private data class Schema(val type: String, val name: String, val sql: String)

    private fun write(source: File, destination: File) {
        require(source.isFile) { "Snapshot source is not a database file" }
        require(!destination.exists()) { "Snapshot destination already exists" }
        try {
            SQLiteDatabase.openOrCreateDatabase(destination, null).use { target ->
                target.rawQuery("PRAGMA journal_mode=DELETE", null).use {
                    check(it.moveToFirst() && it.getString(0).equals("delete", ignoreCase = true))
                }
                // ATTACH is applied only to this disposable connection. Its transaction locks
                // the source and reads one WAL-consistent state; SQL copies large cells internally
                // instead of moving each row through Android's bounded CursorWindow.
                target.execSQL("ATTACH DATABASE ? AS snapshot_source", arrayOf(source.path))
                target.beginTransactionNonExclusive()
                try {
                    val version = target.rawQuery("PRAGMA snapshot_source.user_version", null).use {
                        check(it.moveToFirst()); it.getInt(0)
                    }
                    val schema = target.rawQuery(
                        "SELECT type, name, sql FROM snapshot_source.sqlite_master WHERE sql IS NOT NULL " +
                            "AND name NOT GLOB 'sqlite_*' AND name <> 'android_metadata' ORDER BY type, name", null
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(Schema(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                        }
                    }
                    for (table in schema.filter { it.type == "table" }) {
                        // Room's clipboard schema uses ordinary tables. Fail explicitly if a
                        // future schema adds virtual tables requiring a dedicated backup path.
                        require(!table.sql.contains(Regex("(?i)CREATE\\s+VIRTUAL\\s+TABLE"))) {
                            "Unsupported virtual table in clipboard backup"
                        }
                        target.execSQL(table.sql)
                        copyRows(table.name, target)
                    }
                    // Preserve AUTOINCREMENT even when the highest IDs have been deleted.
                    val sequenceExists = target.rawQuery("SELECT name FROM snapshot_source.sqlite_master WHERE name='sqlite_sequence'", null)
                        .use { it.moveToFirst() }
                    if (sequenceExists) {
                        target.execSQL("DELETE FROM sqlite_sequence")
                        copyRows("sqlite_sequence", target)
                    }
                    schema.filter { it.type != "table" }.forEach { target.execSQL(it.sql) }
                    target.version = version
                    target.rawQuery("PRAGMA quick_check(1)", null).use {
                        if (!it.moveToFirst() || it.getString(0) != "ok") {
                            throw IOException("SQLite snapshot failed integrity validation")
                        }
                    }
                    target.setTransactionSuccessful()
                } finally {
                    target.endTransaction()
                }
            }
        } catch (failure: Exception) {
            SQLiteDatabase.deleteDatabase(destination)
            throw failure
        }
    }

    private fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    private fun copyRows(table: String, target: SQLiteDatabase) {
        target.execSQL("INSERT INTO main.${quote(table)} SELECT * FROM snapshot_source.${quote(table)}")
    }
}
