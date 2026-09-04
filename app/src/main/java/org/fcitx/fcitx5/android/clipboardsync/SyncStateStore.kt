/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.clipboardsync

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import java.io.File

/**
 * File-backed storage for the clipboard-sync service's own state.
 *
 * Previously these values lived in the app's **default** SharedPreferences, whose XML is parsed
 * into memory in full by every process that touches `PreferenceManager` — including the IME
 * process. The values are serialized clipboard payloads, so a few large copies inflated the
 * settings file and, with it, the IME's resident memory, permanently.
 *
 * Each key is a plain file under `filesDir/clipboardsync_state/`, so a large value costs disk
 * rather than the shared-preferences cache, and [migrateFromPreferences] moves any legacy values
 * out of the default preferences on first use.
 */
class SyncStateStore(context: Context) {

    private val dir = File(context.filesDir, DIR_NAME)

    private fun fileFor(key: String) = File(dir, "$key.json")

    fun read(key: String): String? = runCatching {
        val file = fileFor(key)
        if (!file.isFile) null else file.readText().takeIf { it.isNotBlank() }
    }.onFailure { Timber.w(it, "Failed to read sync state: $key") }.getOrNull()

    /**
     * Persist [value] for [key], or delete the entry when [value] is null or blank.
     *
     * Writes to a temporary file and renames, so a process death mid-write cannot leave a
     * truncated JSON document that fails to parse on the next start.
     */
    fun write(key: String, value: String?) {
        runCatching {
            if (value.isNullOrBlank()) {
                fileFor(key).delete()
                return@runCatching
            }
            dir.mkdirs()
            val target = fileFor(key)
            val tmp = File(dir, "${target.name}.tmp")
            tmp.writeText(value)
            if (!tmp.renameTo(target)) {
                target.writeText(value)
                tmp.delete()
            }
        }.onFailure { Timber.w(it, "Failed to write sync state: $key") }
    }

    /**
     * Move legacy values out of the default SharedPreferences into this store, once.
     *
     * A value already present here wins, so this is safe to call on every service start.
     */
    fun migrateFromPreferences(prefs: SharedPreferences, keys: List<String>) {
        val editor = prefs.edit()
        var migrated = false
        keys.forEach { key ->
            val legacy = runCatching { prefs.getString(key, null) }.getOrNull()
            if (legacy.isNullOrBlank()) return@forEach
            if (read(key) == null) write(key, legacy)
            editor.remove(key)
            migrated = true
        }
        if (migrated) {
            editor.apply()
            Timber.i("Migrated clipboard-sync state out of default preferences")
        }
    }

    companion object {
        private const val DIR_NAME = "clipboardsync_state"

        /**
         * Upper bound on a single persisted clipboard payload, in characters.
         *
         * Anything larger is kept in memory for the current session but not written to disk:
         * restoring a multi-megabyte clipboard entry after a restart is not worth an unbounded
         * state file. Roughly 64 KB of UTF-16, which covers any realistic text clipboard.
         */
        const val MAX_PERSISTED_CONTENT_LENGTH = 32 * 1024

        /** True when [content] is small enough to be worth persisting. */
        fun isPersistable(content: String): Boolean =
            content.length <= MAX_PERSISTED_CONTENT_LENGTH
    }
}
