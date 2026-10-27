/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.data

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Apply an ordered plan only inside the application's private asset directory. */
internal class AssetMigration(directory: File, private val openAsset: (String) -> InputStream) {
    private val requestedRoot = directory
    private val root = directory.canonicalFile

    fun apply(actions: List<FileAction>) {
        // Validate the entire plan before deleting anything.
        actions.forEach {
            relative(it.path)
            if (it is FileAction.CreateSymlink) relative(it.src)
        }
        for (action in actions) {
            if (requestedRoot.canonicalFile != root) throw IOException("Asset root changed during migration")
            val target = File(root, action.path)
            when (action) {
                is FileAction.DeleteDir, is FileAction.DeleteFile -> {
                    if (parents(target, create = false)) remove(target)
                }
                is FileAction.CreateDir -> {
                    parents(target, create = true)
                    directory(target)
                }
                is FileAction.CreateFile, is FileAction.UpdateFile -> {
                    parents(target, create = true)
                    val mode = mode(target)
                    if (mode != null && !OsConstants.S_ISREG(mode)) remove(target)
                    openAsset(action.path).use { input ->
                        FileUtil.writeAtomically(target) { output -> input.copyTo(output) }
                    }
                }
                is FileAction.CreateSymlink -> {
                    parents(target, create = true)
                    val source = File(root, action.src)
                    if (!source.canonicalPath.startsWith(root.path + File.separator)) {
                        throw IOException("Asset link source escapes the asset directory")
                    }
                    remove(target)
                    Os.symlink(source.path, target.path)
                }
            }
        }
    }

    private fun relative(path: String) {
        require(path.isNotEmpty() && !File(path).isAbsolute && '\\' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Invalid asset path"
        }
    }

    private fun parents(target: File, create: Boolean): Boolean {
        var parent = root
        val parts = target.relativeTo(root).invariantSeparatorsPath.split('/').dropLast(1)
        for (part in parts) {
            parent = File(parent, part)
            val mode = mode(parent)
            if (mode == null) {
                if (create) directory(parent)
            } else if (!OsConstants.S_ISDIR(mode) || parent.canonicalFile != parent.absoluteFile) {
                // Replaying an interrupted migration may find a new file/link where
                // the old descriptor had a directory. Never traverse it to delete children.
                if (!create) return false
                throw IOException("Asset parent is not a real directory")
            }
        }
        return true
    }

    private fun directory(target: File) {
        val mode = mode(target)
        if (mode != null && !OsConstants.S_ISDIR(mode)) remove(target)
        if (!target.isDirectory && !target.mkdir()) throw IOException("Cannot create asset directory")
    }

    private fun mode(file: File): Int? = try {
        Os.lstat(file.path).st_mode
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT || failure.errno == OsConstants.ENOTDIR) null else throw failure
    }

    private fun remove(file: File) {
        val mode = mode(file) ?: return
        if (OsConstants.S_ISDIR(mode)) {
            val children = file.listFiles() ?: throw IOException("Cannot list obsolete asset directory")
            children.forEach(::remove)
        }
        if (!file.delete()) throw IOException("Cannot remove obsolete asset path")
    }

}
