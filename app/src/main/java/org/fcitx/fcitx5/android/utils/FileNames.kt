/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

/**
 * File-name sanitizing helpers shared by theme, icon-theme and share/import code.
 *
 * Deliberately free of Android dependencies so it stays unit-testable on the JVM: these
 * rules guard paths built from untrusted input (DocumentsProvider display names, ZIP entry
 * names, user-entered theme names).
 */
object FileNames {

    /** Characters that are illegal or path-significant on the platforms we care about. */
    private val ILLEGAL_CHARS = Regex("[/\\\\:*?\"<>|]")

    /** Extensions accepted for image/icon resources supplied by the user or by an import. */
    val ALLOWED_ICON_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "svg", "xml")

    /**
     * Replace illegal characters and trim leading/trailing whitespace and dots.
     *
     * Trimming dots matters: a name of "." or ".." would otherwise resolve to a directory,
     * and a leading dot creates a hidden file.
     */
    fun sanitize(name: String): String =
        name.replace(ILLEGAL_CHARS, "_").trim { it <= ' ' || it == '.' }

    /**
     * Reduce [rawName] to its basename, dropping any directory part, whichever separator
     * style it uses.
     */
    fun basename(rawName: String): String =
        rawName.replace('\\', '/').substringAfterLast('/')

    /**
     * Turn a provider-supplied display name into a file name safe to join onto a directory.
     *
     * A DocumentsProvider may return anything — "../../evil.png", an empty string, a name
     * full of separators — so keep only the basename, strip path-significant and illegal
     * characters, and require an extension from [allowedExtensions].
     *
     * @return the safe file name, or null when there is no acceptable extension.
     */
    fun safeImageFileName(
        rawName: String,
        fallbackBaseName: String = "image",
        allowedExtensions: Set<String> = ALLOWED_ICON_EXTENSIONS
    ): String? {
        val basename = basename(rawName)
        val extension = basename.substringAfterLast('.', "").lowercase()
        if (extension !in allowedExtensions) return null
        val base = sanitize(basename.substringBeforeLast('.', basename))
            .takeIf { it.isNotBlank() }
            ?: fallbackBaseName
        return "$base.$extension"
    }
}
