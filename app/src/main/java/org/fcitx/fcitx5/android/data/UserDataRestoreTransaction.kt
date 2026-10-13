/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import java.io.File
import java.io.IOException
import java.util.UUID

/** Stage each root on its own filesystem and retain originals until all replacements succeed. */
internal class UserDataRestoreTransaction(
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }
) : AutoCloseable {
    private data class Root(val requestedTarget: File, val target: File, val staged: File, val original: File,
                            var originalMoved: Boolean = false, var installed: Boolean = false)
    private val roots = mutableListOf<Root>()
    private var committed = false

    fun stage(target: File, copyExisting: Boolean = true, prepare: (File) -> Unit) {
        // Resolve platform aliases once, before creating siblings on the target filesystem.
        // A difference between an absolute and canonical path is not itself a change.
        val resolvedTarget = target.canonicalFile
        require(roots.none { RimeProfileRestorePlan.overlap(it.target, resolvedTarget) }) {
            "Restore roots must not overlap."
        }
        val parent = resolvedTarget.parentFile ?: throw IOException("Invalid restore destination.")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Unable to prepare restore destination.")
        val suffix = UUID.randomUUID().toString()
        val root = Root(target, resolvedTarget, File(parent, ".${resolvedTarget.name}.import-$suffix"),
            File(parent, ".${resolvedTarget.name}.original-$suffix"))
        roots += root
        if (!root.staged.mkdir()) throw IOException("Unable to stage imported data.")
        if (copyExisting) BackupFileTree.copy(root.target, root.staged)
        prepare(root.staged)
    }

    fun commit() {
        try {
            // Reject a changed destination before replacing any root, then recheck each
            // root immediately before its rename in case another process redirects it.
            roots.forEach { checkDestination(it) }
            for (root in roots) {
                checkDestination(root)
                if (root.target.exists()) {
                    if (!rename(root.target, root.original)) throw IOException("Unable to preserve original data.")
                    root.originalMoved = true
                }
                if (!rename(root.staged, root.target)) throw IOException("Unable to install imported data.")
                root.installed = true
            }
            committed = true
        } catch (e: Exception) {
            rollback()
            throw e
        }
    }

    private fun checkDestination(root: Root) {
        if (root.requestedTarget.canonicalFile != root.target || root.target.canonicalFile != root.target ||
            root.staged.canonicalFile != root.staged || root.original.canonicalFile != root.original) {
            throw IOException("A restore destination changed while data was being staged.")
        }
    }

    private fun rollback() {
        var failed = false
        roots.asReversed().forEach { root ->
            if (root.installed) {
                if (!root.target.deleteRecursively()) failed = true
                else root.installed = false
            }
            if (root.originalMoved && !root.installed) {
                if (!rename(root.original, root.target)) failed = true
                else root.originalMoved = false
            }
        }
        if (failed) throw IOException("Unable to finish restore rollback. Original data has been retained.")
    }

    override fun close() {
        if (!committed) rollback()
        roots.forEach { root ->
            root.staged.deleteRecursively()
            if (committed) root.original.deleteRecursively()
        }
    }
}
