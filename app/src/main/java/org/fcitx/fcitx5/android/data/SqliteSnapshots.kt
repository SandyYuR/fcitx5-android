/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException

/** Standalone SQLite snapshots, including committed rows still resident in WAL. */
internal object SqliteSnapshots {
    /** The caller holds a Room transaction, so every query sees the same database state. */
    fun write(source: SupportSQLiteDatabase, destination: File) {
        check(source.inTransaction()) { "A database snapshot requires a transaction" }
        write(destination, source.version, source::query)
    }

    /** Only use on a private extracted backup, never on the live Room database. */
    fun recoverStaged(source: File, destination: File) {
        // Shared-memory indexes are disposable and contain locks from the exporting device.
        // Keep the matching WAL/journal: opening SQLite replays its committed records.
        val shm = File(source.path + "-shm")
        if (shm.exists() && !shm.delete()) throw IOException("Cannot remove staged SQLite shared memory")
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.beginTransactionNonExclusive()
            try {
                write(destination, database.version) { database.rawQuery(it, null) }
            } finally {
                database.endTransaction()
            }
        }
    }

    private data class Schema(val type: String, val name: String, val sql: String)

    private fun write(destination: File, version: Int, query: (String) -> Cursor) {
        require(!destination.exists()) { "Snapshot destination already exists" }
        val schema = query(
            "SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL " +
                "AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY type, name"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Schema(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
            }
        }
        try {
            SQLiteDatabase.openOrCreateDatabase(destination, null).use { target ->
                target.rawQuery("PRAGMA journal_mode=DELETE", null).use {
                    check(it.moveToFirst() && it.getString(0).equals("delete", ignoreCase = true))
                }
                target.beginTransaction()
                try {
                    for (table in schema.filter { it.type == "table" }) {
                        // Room's clipboard schema uses ordinary tables. Fail explicitly if a
                        // future schema adds virtual tables requiring a dedicated backup path.
                        require(!table.sql.contains(Regex("(?i)CREATE\\s+VIRTUAL\\s+TABLE"))) {
                            "Unsupported virtual table in clipboard backup"
                        }
                        target.execSQL(table.sql)
                        copyRows(table.name, target, query)
                    }
                    // Preserve AUTOINCREMENT even when the highest IDs have been deleted.
                    val sequenceExists = query("SELECT name FROM sqlite_master WHERE name='sqlite_sequence'")
                        .use { it.moveToFirst() }
                    if (sequenceExists) {
                        target.execSQL("DELETE FROM sqlite_sequence")
                        copyRows("sqlite_sequence", target, query)
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

    private fun copyRows(table: String, target: SQLiteDatabase, query: (String) -> Cursor) {
        query("SELECT * FROM ${quote(table)}").use { cursor ->
            val columns = cursor.columnNames.joinToString(",", transform = ::quote)
            val parameters = List(cursor.columnCount) { "?" }.joinToString(",")
            target.compileStatement("INSERT INTO ${quote(table)} ($columns) VALUES ($parameters)").use { insert ->
                while (cursor.moveToNext()) {
                    insert.clearBindings()
                    repeat(cursor.columnCount) { index ->
                        val parameter = index + 1
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> insert.bindNull(parameter)
                            Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(parameter, cursor.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(parameter, cursor.getDouble(index))
                            Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(parameter, cursor.getBlob(index))
                            else -> insert.bindString(parameter, cursor.getString(index))
                        }
                    }
                    insert.executeInsert()
                }
            }
        }
    }
}
