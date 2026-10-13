/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

object ExternalRimeStorage {
    private val volumeId = Regex("[0-9A-Fa-f]+(?:-[0-9A-Fa-f]+)+")
    enum class Access { LegacyPermission, Unsupported, AllFilesPermission }

    fun accessForSdk(sdk: Int): Access = when {
        sdk < 29 -> Access.LegacyPermission
        sdk == 29 -> Access.Unsupported
        else -> Access.AllFilesPermission
    }

    /** SAF document IDs are provider-specific. Only local external-storage IDs map to paths. */
    fun absolutePath(authority: String?, documentId: String, primaryRoot: String): String? {
        if (authority != "com.android.externalstorage.documents") return null
        val sep = documentId.indexOf(':')
        if (sep <= 0) return null
        val volume = documentId.substring(0, sep)
        val relative = documentId.substring(sep + 1).trim('/')
        if (relative.split('/').any { it == "." || it == ".." } || '\u0000' in relative) return null
        val base = when {
            volume.equals("primary", ignoreCase = true) -> primaryRoot.trimEnd('/')
            volume.matches(volumeId) -> "/storage/$volume"
            else -> return null
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }

    /** Return the external-storage document ID for the actual directory, not the profile name. */
    fun documentId(path: String, primaryRoot: String): String? {
        fun relativeTo(root: String): String? = when {
            path == root.trimEnd('/') -> ""
            path.startsWith(root.trimEnd('/') + "/") -> path.substring(root.trimEnd('/').length + 1)
            else -> null
        }
        relativeTo(primaryRoot)?.let { return "primary:$it" }
        if (!path.startsWith("/storage/")) return null
        val rest = path.removePrefix("/storage/")
        val volume = rest.substringBefore('/')
        if (!volume.matches(volumeId)) return null
        return "$volume:${rest.substringAfter('/', "")}"
    }

    /** Full-directory replacement must never target a volume root or a shared parent folder. */
    fun restoreVolumeRoot(path: String, primaryRoot: String): String? {
        val id = documentId(path, primaryRoot) ?: return null
        val relative = id.substringAfter(':').trim('/')
        val parts = relative.split('/')
        if (relative.isEmpty() || parts.any { it == "." || it == ".." || '\u0000' in it } ||
            parts.first().equals("Android", ignoreCase = true)) return null
        val sharedParents = setOf("documents", "download", "downloads", "dcim", "pictures", "movies",
            "music", "alarms", "notifications", "podcasts", "ringtones", "audiobooks")
        if (parts.size == 1 && parts.first().lowercase() in sharedParents) return null
        return absolutePath("com.android.externalstorage.documents", id.substringBefore(':') + ":", primaryRoot)
    }
}
