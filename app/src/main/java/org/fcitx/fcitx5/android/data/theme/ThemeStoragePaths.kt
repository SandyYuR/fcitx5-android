/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import java.io.File

internal object ThemeStoragePaths {
    fun requireName(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." && name.length <= 200 &&
            name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 || it.code == 127 }) {
            "Invalid theme name"
        }
        return name
    }

    fun within(root: File, file: File): File {
        val base = root.canonicalFile
        // File.canonicalFile does not resolve dangling links. An existing directory entry
        // whose target does not exist must not become an out-of-tree write on creation.
        var cursor: File? = file.absoluteFile
        while (cursor != null && cursor != root.absoluteFile) {
            require(cursor.exists() || cursor.parentFile?.list()?.contains(cursor.name) != true) {
                "Theme path contains a dangling symbolic link"
            }
            cursor = cursor.parentFile
        }
        val target = file.canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "Theme path is outside its directory" }
        return target
    }

    fun json(root: File, name: String): File = within(root, File(root, "${requireName(name)}.json"))
}
