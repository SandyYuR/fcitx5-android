package org.fcitx.fcitx5.android.clipboardsync.network

import java.security.MessageDigest
import java.util.Locale

object HashUtils {

    fun sha256(text: String): String =
        sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun calculateFileHash(fileName: String, bytes: ByteArray): String {
        val normalizedFileName = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { fileName }
        val contentHash = sha256(bytes).uppercase(Locale.ROOT)
        return sha256("$normalizedFileName|$contentHash")
    }
}
