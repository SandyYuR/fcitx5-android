/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import java.io.File

/** No-follow validation shared by Android API 23 and host-JVM filesystem operations. */
internal object FilePathGuard {
    // Android/Linux canonicalFile resolves links. Windows Java can leave them lexical.
    // Reflect instead of linking API 26 java.nio.file classes on older Android.
    private val linkProbe: ((File) -> Boolean)? = try {
        val path = Class.forName("java.nio.file.Path")
        val toPath = File::class.java.getMethod("toPath")
        val isLink = Class.forName("java.nio.file.Files").getMethod("isSymbolicLink", path)
        val probe: (File) -> Boolean = { isLink.invoke(null, toPath.invoke(it)) as Boolean }
        probe
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: NoSuchMethodException) {
        null
    }

    /** Only for a leaf whose parent has already been checked against a trusted directory. */
    fun isLeafRedirected(file: File): Boolean {
        val absolute = file.absoluteFile
        val normalized = File(absolute.parentFile?.canonicalFile, absolute.name).absoluteFile
        return absolute.canonicalFile != normalized || linkProbe?.invoke(absolute) == true
    }

    /** Normalize only a Context-owned root alias; every component below it must stay unredirected. */
    fun resolveWritePath(file: File, trustedRoot: File?): File {
        val absolute = file.absoluteFile
        if (trustedRoot != null) {
            val root = trustedRoot.absoluteFile
            val prefix = root.path + File.separator
            if (absolute == root || absolute.path.startsWith(prefix)) {
                val relative = if (absolute == root) "" else absolute.path.substring(prefix.length)
                val expected = if (relative.isEmpty()) root.canonicalFile
                    else File(root.canonicalFile, relative).absoluteFile
                var current: File? = absolute
                while (current != root) {
                    require(current != null && !isLeafRedirected(current)) { "Write path below the trusted root is redirected" }
                    current = current.parentFile
                }
                require(absolute.canonicalFile == expected) { "Write path escapes the trusted root" }
                return expected
            }
        }
        require(!isRedirected(absolute)) { "Write path is redirected" }
        return absolute
    }

    /** Reject a redirected leaf or ancestor, including an ancestor of a missing leaf. */
    fun isRedirected(file: File): Boolean {
        val absolute = file.absoluteFile
        if (absolute.canonicalFile != absolute) return true
        var current: File? = absolute
        while (current != null) {
            // Probe failures propagate: callers must not continue into an unchecked path.
            if (linkProbe?.invoke(current) == true) return true
            current = current.parentFile
        }
        return false
    }
}
