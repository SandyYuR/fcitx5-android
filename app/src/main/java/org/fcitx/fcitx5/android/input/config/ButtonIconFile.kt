/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import android.graphics.drawable.Drawable
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

/**
 * Helpers for custom button icons stored under `<externalFilesDir>/button_icons/`.
 *
 * Historically the icon field stored an absolute path that embedded the app's
 * package-specific external files dir, e.g.
 * `file:/storage/emulated/0/Android/data/org.fcitx.fcitx5.android.fx/files/button_icons/foo.png`.
 * Such paths break when the config is imported into a build with a different
 * applicationId (release vs debug, fx vs mainline), because the external files
 * dir path changes even though the image file is copied over.
 *
 * The canonical form is now a relative path `file:button_icons/foo.png`, resolved
 * against the current external files dir at load time. Both forms are accepted for
 * backward compatibility.
 */
object ButtonIconFile {

    const val PREFIX = "file:"
    const val DIR = "button_icons"

    fun isFileIcon(icon: String?): Boolean = icon != null && icon.startsWith(PREFIX)

    /**
     * Extract the `button_icons`-relative name from a raw path (prefix stripped).
     * Falls back to the plain file name when the marker is absent.
     */
    private fun extractName(rawPath: String): String {
        val marker = "$DIR/"
        val idx = rawPath.lastIndexOf(marker)
        return if (idx >= 0) rawPath.substring(idx + marker.length) else File(rawPath).name
    }

    /**
     * Convert any file icon value to the canonical relative form
     * `file:button_icons/<name>`. Non-file icons are returned unchanged.
     */
    fun toRelative(icon: String): String {
        if (!isFileIcon(icon)) return icon
        val raw = icon.removePrefix(PREFIX)
        if (raw.isEmpty()) return icon
        return "$PREFIX$DIR/${extractName(raw)}"
    }

    /**
     * Resolve a file icon value to an absolute path on the current device, trying:
     * 1. the stored path as-is (same-package, non-exported data);
     * 2. relative to the current external files dir;
     * 3. `<externalFilesDir>/button_icons/<name>` (cross-variant imported data).
     * Returns null when nothing resolves to an existing file.
     */
    fun resolvePath(icon: String): String? {
        if (!isFileIcon(icon)) return null
        val raw = icon.removePrefix(PREFIX)
        if (raw.isEmpty()) return null
        val direct = File(raw)
        if (direct.isAbsolute && direct.exists()) return direct.absolutePath
        val ext = appContext.getExternalFilesDir(null) ?: return null
        if (!direct.isAbsolute) {
            val rel = File(ext, raw)
            if (rel.exists()) return rel.absolutePath
        }
        val byName = File(File(ext, DIR), extractName(raw))
        return if (byName.exists()) byName.absolutePath else null
    }

    /**
     * Load the drawable for a file icon value, or null when it cannot be resolved.
     */
    fun loadDrawable(icon: String): Drawable? {
        val path = resolvePath(icon) ?: return null
        return try {
            Drawable.createFromPath(path)
        } catch (_: Exception) {
            null
        }
    }
}
