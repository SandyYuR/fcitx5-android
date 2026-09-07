/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import java.io.File
import java.util.UUID

/**
 * File-backed store for an editor's unsaved draft.
 *
 * A draft must not travel inside the saved-instance Bundle. That Bundle is parcelled to
 * system_server when the Activity stops, the whole process shares a Binder budget of about 1MB,
 * and a heavily customized keyboard layout serializes to hundreds of kilobytes — a 537KB
 * `draft_layout_json` produced `TransactionTooLargeException: data parcel size 540248 bytes` on
 * `activityStopped`, i.e. a hard crash. Opening the key editor stops the layout editor, so that
 * fired on an ordinary key tap.
 *
 * The draft therefore lives in app-private storage and only its file name goes into the Bundle.
 *
 * Every method swallows IO failures and reports them through the return value: losing a draft is
 * bad, but crashing while the Activity is being stopped is worse.
 */
class LayoutDraftStore(private val dir: File) {

    /** Name of the snapshot this instance owns, or null when it has none. */
    var snapshotName: String? = null
        private set

    /** Hash of the JSON last written, so an unchanged draft is not rewritten. */
    private var lastWrittenHash: Int? = null

    /**
     * Take ownership of a snapshot named in an incoming Bundle.
     *
     * Called before the (asynchronous) initial load, so a stop that happens in between neither
     * loses the snapshot nor writes a second one alongside it.
     *
     * The name arrives from saved-instance state, which is untrusted input: a corrupted or
     * hostile value (path traversal, absolute path, foreign file) must never become a file
     * path, so anything this store could not have written is rejected and there is simply
     * no draft to restore. See [isValidSnapshotName].
     */
    fun adopt(name: String?) {
        snapshotName = name?.takeIf { isValidSnapshotName(it) }
        // The adopted file's content is unknown until it is read, so nothing may be skipped.
        lastWrittenHash = null
    }

    /**
     * Persist [json] and return the snapshot file name, or null when it could not be written.
     *
     * Reuses the existing file name, so repeated stops do not accumulate snapshots.
     */
    fun write(json: String): String? {
        val existing = snapshotName?.takeIf { isValidSnapshotName(it) }
        if (existing != null && lastWrittenHash == json.hashCode() && resolve(existing)?.isFile == true) {
            return existing
        }
        return runCatching {
            dir.mkdirs()
            val name = existing ?: "draft-${UUID.randomUUID()}.json"
            resolve(name)?.writeText(json) ?: error("invalid snapshot name: $name")
            snapshotName = name
            lastWrittenHash = json.hashCode()
            name
        }.onFailure {
            android.util.Log.w(TAG, "Failed to write draft snapshot", it)
        }.getOrNull()
    }

    /** Content of the owned snapshot, or null when there is none or it cannot be read. */
    fun read(): String? {
        val name = snapshotName ?: return null
        return runCatching { resolve(name)?.takeIf { it.isFile }?.readText() }
            .onFailure { android.util.Log.w(TAG, "Failed to read draft snapshot", it) }
            .getOrNull()
            // Now that the file's content is known, an identical draft needs no rewrite.
            ?.also { lastWrittenHash = it.hashCode() }
    }

    /** Drop the owned snapshot, both the file and the reference. */
    fun delete() {
        val name = snapshotName ?: return
        snapshotName = null
        lastWrittenHash = null
        runCatching { resolve(name)?.delete() }
            .onFailure { android.util.Log.w(TAG, "Failed to delete draft snapshot", it) }
    }

    /**
     * Resolve [name] to a file inside [dir], or null when the name is not one this store
     * could have written.
     *
     * `File(dir, name)` alone is not enough: a name containing separators or an absolute
     * path would address files outside the snapshot directory, so the name is validated
     * first and the resolved canonical path must stay within the directory.
     */
    private fun resolve(name: String): File? {
        if (!isValidSnapshotName(name)) return null
        return runCatching {
            val base = dir.canonicalFile
            val file = File(base, name).canonicalFile
            if (file.parentFile?.canonicalPath == base.canonicalPath) file else null
        }.getOrNull()
    }

    /**
     * Delete snapshots that no Bundle can still refer to.
     *
     * A process killed outright never gets to clean up, and the Bundle naming its snapshot dies
     * with the task, so anything older than [maxAgeMs] is unreachable. The owned snapshot is
     * always kept, however old it is.
     *
     * @return how many files were deleted
     */
    fun pruneStale(maxAgeMs: Long, now: Long = System.currentTimeMillis()): Int {
        val keep = snapshotName
        return runCatching {
            var deleted = 0
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                // Only snapshots this store could have written are ever reclaimed; anything
                // else in the directory (logs, other features' files) is left alone.
                if (!isValidSnapshotName(file.name)) return@forEach
                if (file.name == keep) return@forEach
                if (now - file.lastModified() > maxAgeMs && file.delete()) deleted++
            }
            deleted
        }.onFailure { android.util.Log.w(TAG, "Failed to prune draft snapshots", it) }
            .getOrDefault(0)
    }

    companion object {
        private const val TAG = "LayoutDraftStore"

        /**
         * Largest draft still allowed inside the saved-instance Bundle, used only as a fallback
         * for when no snapshot file could be written at all.
         *
         * The Binder transaction budget is about 1MB for the whole process and a Kotlin char
         * parcels as two bytes, so 32K chars is roughly 64KB — small enough to be safe alongside
         * everything else in flight, and still enough for an ordinary layout.
         */
        const val INLINE_MAX_CHARS = 32 * 1024

        /** Snapshots older than this belong to a task that is gone; see [pruneStale]. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** Snapshot files this store writes: `draft-<uuid>.json` (see [LayoutDraftStore.write]). */
        private val SNAPSHOT_NAME = Regex("^draft-[A-Za-z0-9-]{1,64}\\.json$")

        /**
         * Whether [name] is a snapshot file name this store could have written.
         *
         * Saved-instance state is untrusted input — a corrupted Bundle value or a draft id
         * from another store/version must never resolve to a path outside the snapshot
         * directory. The pattern below matches only `draft-<uuid>.json` names with no
         * separators, so hostile values like `../../evil` or `/abs/path` are rejected before
         * any file is touched.
         */
        fun isValidSnapshotName(name: String): Boolean = SNAPSHOT_NAME.matches(name)

        fun fitsInBundle(json: String): Boolean = json.length <= INLINE_MAX_CHARS
    }
}
