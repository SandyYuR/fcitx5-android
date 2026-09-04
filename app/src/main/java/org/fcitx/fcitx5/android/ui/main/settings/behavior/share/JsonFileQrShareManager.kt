/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.utils.FileNames
import java.io.File
import java.io.FileOutputStream

object JsonFileQrShareManager {
    private const val MAX_DECODE_PIXELS = 16_000_000L
    private const val MIN_QR_DECODE_DIMENSION = 512

    fun encodeSavedJsonFileToChunks(
        file: File,
        transferType: Char? = null,
        transferProfile: String? = null
    ): LayoutQrTransferCodec.ChunkBundle {
        val rawJson = file.readText()
        return LayoutQrTransferCodec.encodeJsonToChunks(
            rawJson,
            transferType = transferType,
            transferProfile = transferProfile
        )
    }

    fun buildChunkLabels(
        bundle: LayoutQrTransferCodec.ChunkBundle,
        typeLabel: String,
        nameLabel: String
    ): List<String> = bundle.chunks.map {
        "$typeLabel · $nameLabel · Chunk ${it.index}/${it.total} · ${bundle.transferId}"
    }

    fun encodeSavedJsonFileToLongImage(
        file: File,
        transferType: Char? = null,
        transferProfile: String? = null,
        typeLabel: String? = null,
        nameLabel: String? = null
    ): Pair<Bitmap, LayoutQrTransferCodec.ChunkBundle> {
        val bundle = encodeSavedJsonFileToChunks(
            file,
            transferType = transferType,
            transferProfile = transferProfile
        )
        val contents = bundle.chunks.map { it.encode() }
        val labels = if (typeLabel != null && !nameLabel.isNullOrBlank()) {
            buildChunkLabels(bundle, typeLabel, nameLabel)
        } else {
            bundle.chunks.map { "Chunk ${it.index}/${it.total} · ${bundle.transferId}" }
        }
        return LayoutQrBitmapUtil.composeLongImageStreaming(contents, labels) to bundle
    }

    /**
     * PNG-encode [bitmap] into the share cache and return a FileProvider URI for it.
     *
     * Suspend, on Dispatchers.IO: compressing a QR long image (tens of MB) takes hundreds of
     * milliseconds to seconds, and every caller invoked this straight from a lifecycleScope
     * coroutine on the main thread (see D10).
     *
     * [prefix] is sanitized, because callers pass user-provided theme and layout names.
     */
    suspend fun saveLongImageToShareCache(context: Context, bitmap: Bitmap, prefix: String): Uri =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val safePrefix = FileNames.sanitize(prefix).ifBlank { "share" }
            val file = File(dir, "$safePrefix-${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.share.fileprovider",
                file
            )
        }

    fun decodeQrChunksFromImage(
        context: Context,
        uri: Uri,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): List<String> {
        val bitmap = decodeBitmapForQr(context, uri)
        return try {
            LayoutQrBitmapUtil.decodeAllQrFromImage(bitmap, onProgress)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBitmapForQr(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to read image dimensions" }
        val sampleSize = calculateDecodeSampleSize(bounds.outWidth, bounds.outHeight)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            // QR payloads are monochrome; retaining PNG alpha doubles the long-image footprint.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Unable to decode image")
    }

    private fun calculateDecodeSampleSize(width: Int, height: Int): Int {
        val pixelSample = maxOf(
            1,
            kotlin.math.ceil(
                kotlin.math.sqrt(width.toLong() * height.toDouble() / MAX_DECODE_PIXELS)
            ).toInt()
        )
        val maximumSafeSample = maxOf(1, minOf(width, height) / MIN_QR_DECODE_DIMENSION)
        require(pixelSample <= maximumSafeSample) { "QR image is too large to decode safely" }
        return pixelSample
    }

    fun parseQrPayload(raw: String): LayoutQrTransferCodec.Chunk? {
        val payload = LayoutQrTransferCodec.parseQrImageText(raw) ?: return null
        return runCatching { LayoutQrTransferCodec.parseChunk(payload) }.getOrNull()
    }

    fun decodeChunksToJson(chunks: List<String>): String = LayoutQrTransferCodec.decodeChunksToJson(chunks)
}

class QrChunkCollector {
    private val chunks = linkedMapOf<Int, String>()
    private var transferId: String? = null
    private var total: Int = 0

    data class Progress(
        val current: Int,
        val total: Int,
        val transferId: String?,
        val completedJson: String?,
        val duplicate: Boolean
    )

    fun clear() {
        chunks.clear()
        transferId = null
        total = 0
    }

    fun addAndMaybeAssemble(rawText: String): Progress? {
        val payload = LayoutQrTransferCodec.parseQrImageText(rawText) ?: return null
        val chunk = runCatching { LayoutQrTransferCodec.parseChunk(payload) }.getOrNull() ?: return null
        if (transferId == null || transferId != chunk.transferId) {
            clear()
            transferId = chunk.transferId
            total = chunk.total
        }
        val duplicate = chunks.containsKey(chunk.index)
        chunks[chunk.index] = payload
        if (chunks.size == total) {
            val json = LayoutQrTransferCodec.decodeChunksToJson(chunks.values.toList())
            val completedTransferId = transferId
            val completedTotal = total
            clear()
            return Progress(completedTotal, completedTotal, completedTransferId, json, duplicate)
        }
        return Progress(chunks.size, total, transferId, null, duplicate)
    }
}
