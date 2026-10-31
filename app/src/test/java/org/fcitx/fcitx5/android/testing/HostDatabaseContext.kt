/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.testing

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Android 6 ContextImpl assumes '/' absolute paths; keep real SQLite files on Windows too. */
fun hostDatabaseContext(context: Context): Context = if (File.separatorChar != '\\') context else object : ContextWrapper(context) {
    override fun getApplicationContext(): Context = this
    override fun getDatabasePath(name: String): File = File(name).takeIf { it.isAbsolute } ?: super.getDatabasePath(name)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        openOrCreateDatabase(name, mode, factory, null)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
        val file = getDatabasePath(name)
        file.parentFile?.mkdirs()
        val flags = SQLiteDatabase.CREATE_IF_NECESSARY or
            (if (mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0)
        return SQLiteDatabase.openDatabase(file.path, factory, flags, errorHandler)
    }
}
