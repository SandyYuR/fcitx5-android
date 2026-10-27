/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.provider

import java.io.File
import java.io.FileNotFoundException

/** Preserve existing document IDs (files/...) while confining every operation to one root. */
internal class DocumentPathGuard(root: File) {
    val root = root.canonicalFile

    fun resolve(id: String): File {
        if (id.isEmpty() || id.startsWith('/') || '\\' in id || '\u0000' in id ||
            id.split('/').any { it.isEmpty() || it == "." || it == ".." }) invalid()
        return check(File(root.parentFile, id).absoluteFile)
    }

    fun check(file: File): File {
        val absolute = file.absoluteFile
        // Reject symlink aliases as well as traversal, including links within the root.
        if (!contains(root, absolute) || absolute.canonicalFile != absolute) invalid()
        return absolute
    }

    fun documentId(file: File): String = check(file).relativeTo(root.parentFile!!).invariantSeparatorsPath

    fun isAccessible(file: File): Boolean = runCatching { check(file) }.isSuccess

    fun isChild(parentId: String, childId: String): Boolean = runCatching {
        contains(resolve(parentId), resolve(childId))
    }.getOrDefault(false)

    fun child(parentId: String, name: String): File {
        requireName(name)
        return check(File(resolve(parentId), name))
    }

    fun requireMutable(file: File) {
        if (check(file) == root) throw FileNotFoundException("The document root cannot be moved or deleted")
    }

    fun validateCopy(source: File, target: File) {
        if (contains(source, target)) throw FileNotFoundException("Cannot copy or move a directory into itself")
        source.walkTopDown().forEach { check(it) }
    }

    companion object {
        fun requireName(name: String) {
            if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) invalid()
        }

        private fun contains(parent: File, child: File) =
            parent == child || child.path.startsWith(parent.path + File.separator)

        private fun invalid(): Nothing = throw FileNotFoundException("Invalid document path")
    }
}
