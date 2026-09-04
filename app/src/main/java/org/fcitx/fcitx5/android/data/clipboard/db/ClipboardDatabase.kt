/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema version of the clipboard database.
 *
 * Declared as a top-level constant (rather than inline in [Database]) so the backup importer
 * can compare it against the `user_version` of an incoming database file and refuse one written
 * by a newer build, instead of letting Room "recover" by wiping the tables.
 */
const val CLIPBOARD_DATABASE_VERSION = 7

/** File name of the clipboard database under the app's databases directory. */
const val CLIPBOARD_DATABASE_NAME = "clbdb"

@Database(
    entities = [ClipboardEntry::class],
    version = CLIPBOARD_DATABASE_VERSION,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7)
    ]
)
abstract class ClipboardDatabase : RoomDatabase() {
    abstract fun clipboardDao(): ClipboardDao
}
