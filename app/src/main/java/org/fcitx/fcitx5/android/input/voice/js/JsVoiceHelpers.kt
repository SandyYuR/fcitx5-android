/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Pure helpers for the JS voice host. Free of Android types so JVM tests can exercise them. */
internal object JsVoiceHelpers {
    /** Wraps raw little-endian PCM in a 44-byte WAV header. */
    fun wavBytes(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + pcm.size)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16)
        out.putShort(1)
        out.putShort(channels.toShort())
        out.putInt(sampleRate)
        out.putInt(byteRate)
        out.putShort(blockAlign.toShort())
        out.putShort(bitsPerSample.toShort())
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(pcm.size)
        out.put(pcm)
        return out.array()
    }

    /**
     * Only wss:// or https:// to a hostname that the plugin declared. Userinfo is rejected so a
     * declared host cannot be spoofed with a credential prefix.
     */
    fun isAllowedUrl(url: String, declaredHosts: Collection<String>): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "wss" && scheme != "https") return false
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.contains('@')) return false
        val host = authority.substringBefore(':').lowercase()
        return host.isNotEmpty() && declaredHosts.any { it.equals(host, ignoreCase = true) }
    }

    fun gzip(data: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(data) }
        return bytes.toByteArray()
    }

    fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }

    /** prefix + 4-byte big-endian compressed length + compressed payload (foxy.websocket.sendGzipFrame). */
    fun gzipFrame(prefix: ByteArray, payload: ByteArray): ByteArray {
        val compressed = gzip(payload)
        return ByteBuffer.allocate(prefix.size + 4 + compressed.size)
            .put(prefix).putInt(compressed.size).put(compressed).array()
    }

    fun readInt32BE(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4).int
}
