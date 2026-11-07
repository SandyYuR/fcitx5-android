/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.stats

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** All calls are made by InputStats' IO worker. Nothing opens the database on an input thread. */
internal class StatsDatabase(context: Context, name: String = "input-statistics.db") : SQLiteOpenHelper(StatsStorageContext(context), name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL, words INTEGER NOT NULL)")
        db.execSQL("INSERT INTO settings VALUES (1,0,0)")
        db.execSQL("CREATE TABLE schemas (id TEXT PRIMARY KEY, name TEXT NOT NULL, career INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE buckets (day TEXT NOT NULL, schema_id TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(day,schema_id))")
        db.execSQL("CREATE TABLE layouts (schema_id TEXT PRIMARY KEY, data TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported statistics database version")

    fun settings(): Pair<Boolean, Boolean> = readableDatabase.rawQuery("SELECT enabled,words FROM settings WHERE id=1", null).use {
        check(it.moveToFirst()); (it.getInt(0) != 0) to (it.getInt(1) != 0)
    }
    fun settings(enabled: Boolean, words: Boolean) {
        writableDatabase.execSQL("UPDATE settings SET enabled=?,words=? WHERE id=1", arrayOf(if (enabled) 1 else 0, if (words) 1 else 0))
    }
    fun schemas(): List<StatsSchema> = readableDatabase.rawQuery("SELECT id,name,career FROM schemas ORDER BY name,id", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(StatsSchema(cursor.getString(0), cursor.getString(1), cursor.getLong(2))) }
    }
    fun bucket(day: String, schemaId: String): StatsBucket? = readableDatabase.rawQuery(
        "SELECT data FROM buckets WHERE day=? AND schema_id=?", arrayOf(day, schemaId)).use {
        if (it.moveToFirst()) StatsArchive.json.decodeFromString<StatsBucket>(it.getString(0)) else null
    }
    fun buckets(range: StatsRange, schemaId: String?): List<StatsBucket> {
        val (where, args) = selection(range, schemaId)
        return readableDatabase.rawQuery("SELECT data FROM buckets$where ORDER BY day,schema_id", args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check(size < StatsLimits.MAX_BUCKETS) { "Too many statistics records" }
                    add(StatsArchive.json.decodeFromString<StatsBucket>(cursor.getString(0)))
                }
            }
        }
    }
    fun save(buckets: Collection<StatsBucket>, schemas: Collection<StatsSchema>) = transaction { db ->
        schemas.forEach { schema ->
            db.insertWithOnConflict("schemas", null, ContentValues().apply {
                put("id", schema.id); put("name", schema.name); put("career", schema.careerCharacters)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        }
        buckets.forEach { bucket ->
            db.insertWithOnConflict("buckets", null, ContentValues().apply {
                put("day", bucket.day); put("schema_id", bucket.schemaId); put("data", StatsArchive.json.encodeToString(bucket))
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        }
    }
    fun clear(range: StatsRange, schemaId: String?) {
        val (where, args) = selection(range, schemaId)
        writableDatabase.delete("buckets", where.removePrefix(" WHERE ").takeIf { it.isNotEmpty() }, args)
    }
    fun layouts(): Map<String, StatsLayout> = readableDatabase.rawQuery("SELECT schema_id,data FROM layouts", null).use { cursor ->
        buildMap { while (cursor.moveToNext()) put(cursor.getString(0), StatsArchive.json.decodeFromString<StatsLayout>(cursor.getString(1))) }
    }
    fun layout(schemaId: String, layout: StatsLayout) {
        writableDatabase.insertWithOnConflict("layouts", null, ContentValues().apply {
            put("schema_id", schemaId); put("data", StatsArchive.json.encodeToString(layout))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    fun replace(archive: StatsArchive) = transaction { db ->
        db.delete("buckets", null, null); db.delete("schemas", null, null); db.delete("layouts", null, null)
        save(archive.buckets, archive.schemas)
        archive.layouts.forEach { (id, layout) -> this.layout(id, layout) }
    }
    fun clearAll() = transaction { db ->
        db.delete("buckets", null, null); db.delete("schemas", null, null); db.delete("layouts", null, null)
        settings(enabled = false, words = false)
    }
    private fun selection(range: StatsRange, schemaId: String?): Pair<String, Array<String>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (range.fromDay != null) { clauses += "day>=? AND day<?"; args += range.fromDay; args += range.untilDay!! }
        if (schemaId != null) { clauses += "schema_id=?"; args += schemaId }
        return (if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")) to args.toTypedArray()
    }
    private inline fun transaction(action: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { action(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private class StatsStorageContext(context: Context) : ContextWrapper(context) {
        override fun getDatabasePath(name: String): File {
            require(name.matches(Regex("[A-Za-z0-9_.-]+")))
            val directory = File(noBackupFilesDir, "input-stats")
            check(directory.isDirectory || directory.mkdirs())
            return File(directory, name)
        }
        // Older SQLiteOpenHelper calls Context.openOrCreateDatabase instead of getDatabasePath.
        // Handle both paths so a private location never depends on the default databases folder.
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            openOrCreateDatabase(name, mode, factory, null)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
            val flags = SQLiteDatabase.CREATE_IF_NECESSARY or
                if ((mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0
            return SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, flags, errorHandler)
        }
    }
}
