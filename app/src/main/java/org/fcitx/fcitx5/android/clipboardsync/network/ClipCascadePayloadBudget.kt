/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync.network

import okio.utf8Size
import org.fcitx.fcitx5.android.clipboardsync.Base64Payload
import java.io.IOException
import java.util.Locale

internal class ClipCascadeMessageTooLargeException(message: String) : IOException(message)

/** Shared budget for transport, JSON/STOMP and decoded payload layers. */
internal class ClipCascadePayloadBudget(
    val maxPayloadBytes: Long = 32L * 1024 * 1024,
    private val metadataBytes: Long = 16L * 1024,
    val maxHeaderBytes: Int = 16 * 1024,
    private val maxQueuedFrameBytes: Long = 16L * 1024 * 1024
) {
    companion object {
        // Leave half the heap for the IME/UI; allow for UTF-16, two Base64 layers,
        // crypto and JSON copies in the other half. Never exceed the protocol ceiling.
        fun forHeap(maxHeap: Long) = ClipCascadePayloadBudget(
            maxPayloadBytes = (maxHeap / 32).coerceIn(1, 32L * 1024 * 1024))
        val DEFAULT = forHeap(Runtime.getRuntime().maxMemory())
    }

    init {
        require(maxPayloadBytes in 1..(32L * 1024 * 1024))
        require(metadataBytes in 128..(16L * 1024))
        require(maxHeaderBytes in 64..(16 * 1024))
        require(maxQueuedFrameBytes > maxHeaderBytes + metadataBytes + 512)
    }

    val maxPlainBodyBytes = encodedBytes(maxPayloadBytes) + metadataBytes
    val maxWireBodyBytes = encodedBytes(maxPlainBodyBytes) + 512
    val maxReceivedFrameBytes = maxWireBodyBytes + maxHeaderBytes + 1

    // OkHttp 4.x has a 16 MiB outgoing queue. Reserve both Base64 expansions and
    // the metadata before reading a URI or constructing its first Base64 String.
    val maxOutgoingBinaryBytes = minOf(
        maxPayloadBytes,
        (((maxQueuedFrameBytes - maxHeaderBytes - 512) / 4 * 3 - metadataBytes) / 4 * 3)
            .coerceAtLeast(0)
    )

    fun checkFrame(raw: String) = checkText(raw, maxReceivedFrameBytes, "STOMP frame")
    fun checkWireBody(body: String) = checkText(body, maxWireBodyBytes, "wire body")
    fun checkPlainBody(body: String) = checkText(body, maxPlainBodyBytes, "plain body")
    fun checkOutgoingFrame(body: String) =
        checkText(body, maxQueuedFrameBytes - maxHeaderBytes - 1, "outgoing frame")

    fun checkData(data: ClipCascadeClipboardData) {
        checkText(data.type, 64, "payload type")
        data.filename?.let { checkText(it, minOf(4096, metadataBytes), "filename") }
        when (data.type.lowercase(Locale.ROOT)) {
            "image", "file_eager" -> checkBase64(data.payload, maxPayloadBytes, "binary payload")
            else -> checkText(data.payload, maxPayloadBytes, "text payload")
        }
    }

    /** Account for JSON escaping before serialization and for encryption before allocating. */
    fun checkOutgoing(data: ClipCascadeClipboardData, encrypted: Boolean) {
        checkData(data)
        val plainUpperBound = 128L + quotedJsonBytes(data.payload) + quotedJsonBytes(data.type) +
            (data.filename?.let(::quotedJsonBytes) ?: 4)
        checkSize(plainUpperBound, maxPlainBodyBytes, "serialized body")
        val wireUpperBound = if (encrypted) encodedBytes(plainUpperBound) + 256 else plainUpperBound
        checkSize(wireUpperBound + maxHeaderBytes + 1, maxQueuedFrameBytes, "outgoing message")
    }

    fun checkBase64(value: String, maxDecodedBytes: Long, label: String) {
        // Bound whitespace as well as meaningful Base64 characters.
        checkText(value, maxWireBodyBytes, label)
        checkSize(Base64Payload.estimatedDecodedBytes(value), maxDecodedBytes, label)
    }

    private fun checkText(value: String, limit: Long, label: String) {
        checkSize(value.length.toLong(), limit, label)
        checkSize(value.utf8Size(), limit, label)
    }

    private fun checkSize(size: Long, limit: Long, label: String) {
        if (size > limit) throw ClipCascadeMessageTooLargeException("ClipCascade $label exceeds $limit bytes")
    }

    private fun encodedBytes(bytes: Long): Long = (bytes + 2) / 3 * 4

    private fun quotedJsonBytes(value: String): Long {
        var bytes = 2L
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            bytes += when {
                c == '"' || c == '\\' || c == '\b' || c == '\u000c' ||
                    c == '\n' || c == '\r' || c == '\t' -> 2
                c < ' ' -> 6
                c < '\u0080' -> 1
                c < '\u0800' -> 2
                c.isHighSurrogate() && index < value.length && value[index].isLowSurrogate() -> {
                    index++
                    4
                }
                c.isSurrogate() -> 6 // also covers serializers escaping an unpaired surrogate
                else -> 3
            }
        }
        return bytes
    }
}
