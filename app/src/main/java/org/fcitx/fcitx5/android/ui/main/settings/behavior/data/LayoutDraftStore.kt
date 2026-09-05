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
     */
    fun adopt(name: String?) {
        snapshotName = name
        // The adopted file's content is unknown until it is read, so nothing may be skipped.
        lastWrittenHash = null
    }

    /**
     * Persist [json] and return the snapshot file name, or null when it could not be written.
     *
     * Reuses the existing file name, so repeated stops do not accumulate snapshots.
     */
    fun write(json: String): String? {
        val existing = snapshotName
        if (existing != null && lastWrittenHash == json.hashCode() && File(dir, existing).isFile) {
            return existing
        }
        return runCatching {
            dir.mkdirs()
            val name = existing ?: "draft-${UUID.randomUUID()}.json"
            File(dir, name).writeText(json)
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
        return runCatching { File(dir, name).takeIf { it.isFile }?.readText() }
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
        runCatching { File(dir, name).delete() }
            .onFailure { android.util.Log.w(TAG, "Failed to delete draft snapshot", it) }
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

        fun fitsInBundle(json: String): Boolean = json.length <= INLINE_MAX_CHARS
    }
}
