/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.io.IOException
import java.util.UUID

/** Durable decision for all roots, with ownership markers covering each rename window. */
internal class UserDataRestoreJournal(
    private val directory: File,
    private val anchor: (File) -> File = { it.parentFile!! },
    private val afterPersist: (String) -> Unit = {}
) {
    @Serializable private data class Root(val target: String, val token: String, val anchor: String,
        val started: Boolean = false, val hadOriginal: Boolean = false, val resolved: Boolean = false)
    @Serializable private data class Record(val phase: String = "STAGING", val roots: List<Root> = emptyList())
    data class Recovery(val committed: Boolean, val pending: List<File>, val warnings: List<String>,
                        val cleanupPending: Boolean = false)
    private val file get() = File(directory, "user-data-restore.json")
    private var record: Record? = null
    private data class Paths(val target: File, val staged: File, val original: File, val token: String) {
        val newMarker = ".restore-$token.new"
        val oldMarker = ".restore-$token.old"
    }

    private fun load(): Record? {
        if (!file.exists()) return null
        checkPath(file)
        if (file.length() > 4 * 1024 * 1024) throw IOException("Restore journal exceeds limit")
        return Json.decodeFromString<Record>(file.readText()).also {
            require(it.phase in setOf("STAGING", "REPLACING", "COMMITTED") && it.roots.size <= 128)
            it.roots.forEach { root -> paths(root) }
        }
    }
    private fun persist(value: Record) {
        checkPath(file)
        FileUtil.writeAtomically(file, Json.encodeToString(value))
        record = value
        afterPersist(value.phase)
    }
    fun begin() {
        recover()
        check(!file.exists()) { "A previous restore still needs recovery or cleanup" }
        persist(Record())
    }
    fun register(target: File, token: String): Int {
        val current = checkNotNull(record)
        check(current.phase == "STAGING" && current.roots.size < 128)
        val resolved = target.canonicalFile
        require(!RimeProfileRestorePlan.overlap(resolved, directory.canonicalFile))
        require(current.roots.none { RimeProfileRestorePlan.overlap(File(it.target), resolved) })
        val root = Root(resolved.path, token, anchor(resolved).canonicalPath)
        paths(root)
        if (!File(root.anchor).isDirectory) throw IOException("Restore storage volume is unavailable")
        persist(current.copy(roots = current.roots + root))
        return current.roots.size
    }
    fun staged(index: Int) { val p = paths(checkNotNull(record).roots[index]); mark(p.staged, p.newMarker, p.token) }
    fun replacing() = persist(checkNotNull(record).copy(phase = "REPLACING"))
    fun preserving(index: Int) {
        val current = checkNotNull(record)
        val root = current.roots[index]
        val p = paths(root)
        replace(index, root.copy(started = true, hadOriginal = p.target.exists()))
        if (p.target.exists()) mark(p.target, p.oldMarker, p.token)
    }
    fun committed() = persist(checkNotNull(record).copy(phase = "COMMITTED"))
    private fun replace(index: Int, root: Root) {
        val current = checkNotNull(record)
        persist(current.copy(roots = current.roots.toMutableList().also { it[index] = root }))
    }

    fun recover(cleanup: Boolean = true): Recovery = recover(cleanup, resolvePending = true)

    /** Background deletion must not retry a live root whose recovery still needs attention. */
    fun cleanupResolved(): Recovery = recover(cleanup = true, resolvePending = false)

    private fun recover(cleanup: Boolean, resolvePending: Boolean): Recovery {
        record = load()
        val initial = record ?: return Recovery(false, emptyList(), emptyList())
        val committed = initial.phase == "COMMITTED"
        val pending = mutableListOf<File>()
        val warnings = mutableListOf<String>()
        for (index in initial.roots.indices.reversed()) {
            val root = checkNotNull(record).roots[index]
            val p = paths(root)
            if (!root.resolved && !resolvePending) { pending += p.target; continue }
            if (!File(root.anchor).isDirectory) { pending += p.target; continue }
            try {
                if (!committed && !root.resolved) rollback(root, p)
                // Persist resolution before removing any evidence of the completed rollback.
                if (!root.resolved) replace(index, root.copy(resolved = true))
                // Startup needs consistent live roots before opening cached preferences/Room.
                // Recursively deleting potentially large private leftovers belongs on IO.
                if (!cleanup) continue
                if (committed) {
                    removeOwned(p.original, p.oldMarker, p.token)
                    unmark(p.target, p.newMarker, p.token)
                } else unmark(p.target, p.oldMarker, p.token)
                removeOwned(p.staged, p.newMarker, p.token)
            } catch (e: Exception) {
                if (!checkNotNull(record).roots[index].resolved) pending += p.target
                warnings += "${p.target.path}: ${e.message}"
            }
        }
        if (cleanup && pending.isEmpty() && warnings.isEmpty()) {
            if (!file.delete()) throw IOException("Cannot remove completed restore journal")
            record = null
        }
        return Recovery(committed, pending, warnings, cleanupPending = file.exists())
    }

    private fun rollback(root: Root, p: Paths) {
        if (!root.started) {
            if (p.original.exists()) throw IOException("Unexpected original directory; retained")
            return
        }
        if (owned(p.target, p.newMarker, p.token)) {
            if (root.hadOriginal && !owned(p.original, p.oldMarker, p.token))
                throw IOException("Original data unavailable; imported directory retained")
            if (p.staged.exists() || !p.target.renameTo(p.staged))
                throw IOException("Cannot preserve interrupted imported directory")
        }
        if (p.original.exists()) {
            if (!owned(p.original, p.oldMarker, p.token)) throw IOException("Original ownership marker is missing; retained")
            if (p.target.exists() && !(p.target.isDirectory && p.target.list()?.isEmpty() == true && p.target.delete()))
                throw IOException("Restore destination conflicts with original data; both retained")
            if (!p.original.renameTo(p.target)) throw IOException("Cannot put original data back")
        } else if (root.hadOriginal && !p.target.isDirectory) {
            throw IOException("Original data is missing")
        }
    }
    private fun paths(root: Root): Paths {
        require(UUID.fromString(root.token).toString() == root.token)
        val target = File(root.target)
        require(target.isAbsolute && target.name.isNotEmpty() && target.parentFile != null)
        checkPath(target)
        val parent = target.parentFile!!
        val staged = File(parent, ".${target.name}.import-${root.token}")
        val original = File(parent, ".${target.name}.original-${root.token}")
        checkPath(staged); checkPath(original)
        val volume = File(root.anchor)
        require(volume.isAbsolute && target.path.startsWith(volume.path + File.separator))
        require(!RimeProfileRestorePlan.overlap(target, directory.canonicalFile))
        return Paths(target, staged, original, root.token)
    }
    private fun owned(root: File, name: String, token: String): Boolean {
        checkPath(root)
        val marker = File(root, name)
        checkPath(marker)
        return marker.isFile && marker.length() <= 128 && marker.readText() == token
    }
    private fun mark(root: File, name: String, token: String) {
        val marker = File(root, name); checkPath(marker)
        if (marker.exists() && !owned(root, name, token)) throw IOException("Conflicting restore marker")
        FileUtil.writeAtomically(marker, token)
    }
    private fun unmark(root: File, name: String, token: String) {
        if (owned(root, name, token) && !File(root, name).delete()) throw IOException("Cannot clear restore marker")
    }
    private fun removeOwned(root: File, marker: String, token: String) {
        checkPath(root)
        if (!root.exists()) return
        if (!root.isDirectory || (!owned(root, marker, token) && root.list()?.isEmpty() != true))
            throw IOException("Unrecognized restore directory retained at ${root.path}")
        (root.listFiles() ?: throw IOException("Cannot inspect restore cleanup directory"))
            .filter { it.name != marker }.forEach(::removeTree)
        unmark(root, marker, token)
        if (!root.delete()) throw IOException("Cannot remove restore staging directory")
    }
    private fun removeTree(file: File) {
        // Unlink a child symlink itself; never recurse into its destination.
        if (file.canonicalFile == file.absoluteFile && file.isDirectory)
            (file.listFiles() ?: throw IOException("Cannot inspect restore cleanup directory")).forEach(::removeTree)
        if (!file.delete()) throw IOException("Cannot clean restore staging file")
    }
    private fun checkPath(file: File) {
        if (file.canonicalFile != file.absoluteFile) throw IOException("Restore path was redirected: ${file.path}")
    }
}
