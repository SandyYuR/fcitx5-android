/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Called only with the native engine stopped, on its owner thread. */
internal object WanxiangUpdateTransaction {
    private const val DIRECTORY = ".wanxiang-update"
    internal const val OWNER = "zhongzhou-wanxiang-update-v1\n"
    @Serializable internal data class Entry(val path: String, val existed: Boolean)
    @Serializable internal data class Journal(val entries: List<Entry>, val committed: Boolean = false, val settings: Boolean = false, val overrides: Boolean = false, val generic: Boolean = false)

    private fun allowed(path: String, settings: Boolean, overrides: Boolean = false, generic: Boolean = false) =
        if (generic) !settings && RimeSchemePayload.resource(path)
        else if (settings) WanxiangAdvancedFiles.writable(path) else WanxiangPayload.resource(path, overrides)

    private fun transaction(root: File) = WanxiangPayload.child(root, DIRECTORY)
    private fun copy(source: File, target: File) {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        FileOutputStream(target).use { out -> source.inputStream().use { it.copyTo(out) }; out.fd.sync() }
    }

    /** Before a durable journal exists, preparing backup/new files has not changed any live file. */
    fun recover(user: File) {
        val root = user.canonicalFile
        val tx = transaction(root)
        if (!tx.exists()) return
        require(tx.isDirectory) { "Invalid Wanxiang recovery directory" }
        val owner = WanxiangPayload.child(tx, "owner")
        if (!owner.exists() && tx.list().isNullOrEmpty()) {
            check(tx.delete())
            return
        }
        require(owner.isFile && owner.length() == OWNER.toByteArray().size.toLong() && owner.readText() == OWNER) {
            "Wanxiang recovery directory is not owned by this updater; original files were preserved: $tx"
        }
        val manifest = WanxiangPayload.child(tx, "journal.json")
        if (manifest.exists()) {
            require(manifest.isFile && manifest.length() <= 4 * 1024 * 1024) { "Invalid Wanxiang recovery journal" }
            val journal = Json.decodeFromString<Journal>(manifest.readText())
            require(journal.entries.size in 1..WanxiangPayload.MAX_ENTRIES &&
                journal.entries.map { it.path }.distinct().size == journal.entries.size)
            // Validate the complete journal and backups before making any recovery changes.
            journal.entries.forEach { entry ->
                require(allowed(entry.path, journal.settings, journal.overrides, journal.generic))
                if (!journal.committed) {
                    val target = WanxiangPayload.child(root, entry.path)
                    require(!target.exists() || target.isFile) { "Recovery destination is not a file" }
                    if (entry.existed) require(WanxiangPayload.child(tx, "old/${entry.path}").isFile) {
                        "Wanxiang recovery backup is missing; keep $tx and retry"
                    }
                }
            }
            if (!journal.committed) {
                journal.entries.asReversed().forEach { entry ->
                    val target = WanxiangPayload.child(root, entry.path)
                    if (entry.existed) {
                        // Retain backups until the complete rollback is durable, so recovery is repeatable.
                        val pending = WanxiangPayload.child(tx, "restore.pending")
                        copy(WanxiangPayload.child(tx, "old/${entry.path}"), pending)
                        FileUtil.replaceAtomically(pending, target)
                    } else if (target.exists() && !target.delete()) throw IOException("Cannot remove incomplete update: ${entry.path}")
                }
                // A crash during cleanup must not leave a journal pointing at already deleted backups.
                FileUtil.writeAtomically(manifest, Json.encodeToString(journal.copy(committed = true)))
            }
        }
        FileUtil.removeFile(tx).getOrThrow()
    }

    fun install(user: File, payload: File, activate: () -> Unit, deactivate: () -> Unit, settings: Boolean = false, overrides: Boolean = false, generic: Boolean = false) {
        val root = user.canonicalFile
        recover(root)
        require(root.isDirectory && payload.isDirectory)
        val files = payload.walkTopDown().filter { it.isFile }.map { file ->
            val path = file.relativeTo(payload).invariantSeparatorsPath
            require(allowed(path, settings, overrides, generic))
            require(WanxiangPayload.child(payload.canonicalFile, path) == file.absoluteFile)
            path to file
        }.toList()
        require(files.size in 1..WanxiangPayload.MAX_ENTRIES) { "No update files" }
        val entries = files.map { (path, _) ->
            val target = WanxiangPayload.child(root, path)
            require(!target.exists() || target.isFile) { "Update destination is not a file: $path" }
            // Reject parent file conflicts before touching the profile.
            var parent = target.parentFile!!
            while (parent != root) { require(!parent.exists() || parent.isDirectory); parent = parent.parentFile!! }
            Entry(path, target.exists())
        }
        val needed = files.sumOf { it.second.length() } + entries.filter { it.existed }.sumOf { File(root, it.path).length() }
        require(root.usableSpace - needed >= 32L * 1024 * 1024) { "Insufficient storage to update and retain recovery files" }
        val tx = transaction(root)
        check(tx.mkdir())
        var committed = false
        try {
            FileUtil.writeAtomically(WanxiangPayload.child(tx, "owner"), OWNER)
            files.zip(entries).forEach { (item, entry) ->
                if (entry.existed) copy(WanxiangPayload.child(root, entry.path), WanxiangPayload.child(tx, "old/${entry.path}"))
                copy(item.second, WanxiangPayload.child(tx, "new/${entry.path}"))
            }
            val manifest = WanxiangPayload.child(tx, "journal.json")
            FileUtil.writeAtomically(manifest, Json.encodeToString(Journal(entries, settings = settings, overrides = overrides, generic = generic)))
            entries.forEach { entry ->
                val target = WanxiangPayload.child(root, entry.path)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                FileUtil.replaceAtomically(WanxiangPayload.child(tx, "new/${entry.path}"), target)
            }
            activate()
            FileUtil.writeAtomically(manifest, Json.encodeToString(Journal(entries, committed = true, settings = settings, overrides = overrides, generic = generic)))
            committed = true
        } catch (error: Throwable) {
            try { deactivate(); recover(root) } catch (recovery: Throwable) { error.addSuppressed(recovery) }
            throw error
        } finally {
            // Cleanup failure after commit does not turn a successful deployment into a failed update.
            if (committed) runCatching { recover(root) }
        }
    }
}
