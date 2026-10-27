/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.webeditor

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

internal class BridgeSessionAccess(host: String, port: Int, private val clock: () -> Long = System::nanoTime) {
    private val random = SecureRandom()
    val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    val pairingCode: String = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))
    private var failedAttempts = 0
    private var retryAfter = 0L
    private val authorities = setOf("$host:$port", "127.0.0.1:$port", "localhost:$port")
    val cookie: String get() = "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict"

    enum class PairingResult { Accepted, Rejected, Limited }

    @Synchronized fun pair(code: String?): PairingResult {
        val now = clock()
        if (failedAttempts >= 5) {
            if (now < retryAfter) return PairingResult.Limited
            failedAttempts = 0
        }
        if (code == pairingCode) {
            failedAttempts = 0
            return PairingResult.Accepted
        }
        failedAttempts++
        if (failedAttempts >= 5) retryAfter = now + 60_000_000_000L
        return PairingResult.Rejected
    }

    fun allowedRequest(headers: Map<String, String>): Boolean =
        headers["host"] in authorities &&
            (headers["origin"] == null || headers["origin"] in authorities.map { "http://$it" })

    fun matches(value: String?): Boolean = value != null && value.length == token.length &&
        MessageDigest.isEqual(token.toByteArray(Charsets.US_ASCII), value.toByteArray(Charsets.US_ASCII))

    fun authenticated(headers: Map<String, String>): Boolean {
        if (!allowedRequest(headers)) return false
        val bearer = headers["authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        val cookie = headers["cookie"]?.split(';')?.firstOrNull { it.trim().startsWith("$COOKIE=") }
            ?.trim()?.substringAfter('=')
        return matches(bearer) || matches(cookie)
    }

    companion object {
        private const val COOKIE = "f5a_bridge_session"
        private const val MAX_PREVIEW_BYTES = 4 * 1024 * 1024

        fun readPreview(file: File, allowedRoots: List<File>): ByteArray {
            val target = file.canonicalFile
            require(allowedRoots.any { target.path.startsWith(it.canonicalPath + File.separator) } && target.isFile) {
                "Icon preview must be inside an icon directory"
            }
            require(target.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "svg", "xml")) {
                "Unsupported icon preview type"
            }
            return target.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() <= MAX_PREVIEW_BYTES - count) { "Icon preview is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }
}
