/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object LayoutQrBitmapUtil {
    private const val QR_SIZE = 768
    private const val PAGE_PADDING = 24
    private const val TEXT_SIZE = 22f
    private const val TEXT_GAP = 12

    private data class ScaledPreview(val bitmap: Bitmap, val heightWithPadding: Int)

    private fun buildScaledPreviewOrNull(previewBitmap: Bitmap?, targetWidth: Int): ScaledPreview? {
        val source = previewBitmap ?: return null
        if (source.isRecycled || source.width <= 0 || source.height <= 0 || targetWidth <= 0) return null
        val scale = targetWidth.toFloat() / source.width.toFloat()
        val scaledHeight = maxOf(1, (source.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, scaledHeight, true)
        return ScaledPreview(scaled, scaledHeight + PAGE_PADDING)
    }

    fun createQrBitmap(content: String): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.MARGIN to 2
        )
        val matrix = QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
        val pixels = IntArray(QR_SIZE * QR_SIZE)
        for (y in 0 until QR_SIZE) {
            for (x in 0 until QR_SIZE) {
                pixels[y * QR_SIZE + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888)
    }

    fun composeLongImage(qrBitmaps: List<Bitmap>, labels: List<String>): Bitmap {
        require(qrBitmaps.size == labels.size) { "Bitmap count must equal label count" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = Typeface.DEFAULT_BOLD
        }
        val pageHeight = PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE.toInt() + PAGE_PADDING
        val width = QR_SIZE + PAGE_PADDING * 2
        val totalHeight = pageHeight * qrBitmaps.size
        val merged = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.drawColor(Color.WHITE)
        qrBitmaps.forEachIndexed { index, bitmap ->
            val top = index * pageHeight
            canvas.drawBitmap(bitmap, PAGE_PADDING.toFloat(), (top + PAGE_PADDING).toFloat(), null)
            canvas.drawText(labels[index], PAGE_PADDING.toFloat(), (top + PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE), paint)
        }
        return merged
    }

    fun composeLongImageStreaming(contents: List<String>, labels: List<String>): Bitmap {
        require(contents.size == labels.size) { "Content count must equal label count" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = Typeface.DEFAULT_BOLD
        }
        val pageHeight = PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE.toInt() + PAGE_PADDING
        val width = QR_SIZE + PAGE_PADDING * 2
        val totalHeight = pageHeight * contents.size
        val merged = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.drawColor(Color.WHITE)
        contents.forEachIndexed { index, content ->
            val top = index * pageHeight
            val qr = createQrBitmap(content)
            canvas.drawBitmap(qr, PAGE_PADDING.toFloat(), (top + PAGE_PADDING).toFloat(), null)
            canvas.drawText(labels[index], PAGE_PADDING.toFloat(), (top + PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE), paint)
            qr.recycle()
        }
        return merged
    }

    /**
     * Compose a long image with QR codes and a preview image at the top.
     * @param qrBitmaps List of QR code bitmaps
     * @param labels List of labels for each QR code
     * @param previewBitmap Optional preview bitmap to place at the top
     * @return Composed bitmap with preview (if provided) followed by QR codes
     */
    fun composeLongImageWithPreview(
        qrBitmaps: List<Bitmap>,
        labels: List<String>,
        previewBitmap: Bitmap?
    ): Bitmap {
        require(qrBitmaps.size == labels.size) { "Bitmap count must equal label count" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = Typeface.DEFAULT_BOLD
        }
        val pageHeight = PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE.toInt() + PAGE_PADDING
        val width = QR_SIZE + PAGE_PADDING * 2

        val scaledPreview = buildScaledPreviewOrNull(previewBitmap, width)
        val previewSectionHeight = scaledPreview?.heightWithPadding ?: 0
        val totalHeight = previewSectionHeight + pageHeight * qrBitmaps.size
        val merged = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.drawColor(Color.WHITE)

        var currentTop = 0

        // Draw preview at the top if provided
        scaledPreview?.let {
            canvas.drawBitmap(it.bitmap, 0f, currentTop.toFloat(), null)
            it.bitmap.recycle()
            currentTop += it.heightWithPadding
        }

        // Draw QR codes below preview
        qrBitmaps.forEachIndexed { index, bitmap ->
            val top = currentTop + index * pageHeight
            canvas.drawBitmap(bitmap, PAGE_PADDING.toFloat(), (top + PAGE_PADDING).toFloat(), null)
            canvas.drawText(labels[index], PAGE_PADDING.toFloat(), (top + PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE), paint)
        }

        return merged
    }

    /**
     * Compose a long image with QR codes (generated from contents) and a preview image at the top.
     * @param contents List of QR code contents
     * @param labels List of labels for each QR code
     * @param previewBitmap Optional preview bitmap to place at the top
     * @return Composed bitmap with preview (if provided) followed by QR codes
     */
    fun composeLongImageStreamingWithPreview(
        contents: List<String>,
        labels: List<String>,
        previewBitmap: Bitmap?
    ): Bitmap {
        require(contents.size == labels.size) { "Content count must equal label count" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = Typeface.DEFAULT_BOLD
        }
        val pageHeight = PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE.toInt() + PAGE_PADDING
        val width = QR_SIZE + PAGE_PADDING * 2

        val scaledPreview = buildScaledPreviewOrNull(previewBitmap, width)
        val previewSectionHeight = scaledPreview?.heightWithPadding ?: 0
        val totalHeight = previewSectionHeight + pageHeight * contents.size
        val merged = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.drawColor(Color.WHITE)

        var currentTop = 0

        // Draw preview at the top if provided
        scaledPreview?.let {
            canvas.drawBitmap(it.bitmap, 0f, currentTop.toFloat(), null)
            it.bitmap.recycle()
            currentTop += it.heightWithPadding
        }

        // Draw QR codes below preview
        contents.forEachIndexed { index, content ->
            val top = currentTop + index * pageHeight
            val qr = createQrBitmap(content)
            canvas.drawBitmap(qr, PAGE_PADDING.toFloat(), (top + PAGE_PADDING).toFloat(), null)
            canvas.drawText(labels[index], PAGE_PADDING.toFloat(), (top + PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE), paint)
            qr.recycle()
        }

        return merged
    }

    fun decodeAllQrFromImage(bitmap: Bitmap): List<String> {
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)
        )
        val found = linkedSetOf<String>()
        val baseWidth = QR_SIZE + PAGE_PADDING * 2
        val scale = if (baseWidth > 0) bitmap.width.toFloat() / baseWidth else 1f
        val scaledPadding = maxOf(1, (PAGE_PADDING * scale).toInt())
        val scaledQrSize = maxOf(1, (QR_SIZE * scale).toInt())
        val scaledTextGap = maxOf(1, (TEXT_GAP * scale).toInt())
        val scaledTextSize = maxOf(1, (TEXT_SIZE * scale).toInt())
        val pageHeight = scaledPadding + scaledQrSize + scaledTextGap + scaledTextSize + scaledPadding
        val expectedLeft = minOf(scaledPadding, maxOf(0, bitmap.width - 1))
        val cropSize = minOf(scaledQrSize, bitmap.width)
        if (cropSize <= 0 || bitmap.height <= 0) return emptyList()
        val centeredLeft = maxOf(0, (bitmap.width - cropSize) / 2)
        val leftCandidates = buildList {
            add(expectedLeft)
            add(centeredLeft)
        }.distinct()

        fun hasCompleteChunkSet(): Boolean {
            val parsed = found.mapNotNull { runCatching { LayoutQrTransferCodec.parseChunk(it) }.getOrNull() }
            val first = parsed.firstOrNull() ?: return false
            val completeCount = parsed
                .asSequence()
                .filter { it.transferId == first.transferId && it.total == first.total }
                .map { it.index }
                .toSet()
                .size
            return completeCount == first.total
        }

        data class AddResult(val isNew: Boolean, val completed: Boolean)

        fun addDecodedText(text: String?): AddResult {
            if (text == null) return AddResult(isNew = false, completed = false)
            val isNew = found.add(text)
            val completed = isNew && hasCompleteChunkSet()
            return AddResult(isNew = isNew, completed = completed)
        }

        fun normalizeValidChunkText(raw: String?): String? {
            val payload = raw?.let(LayoutQrTransferCodec::parseQrImageText) ?: return null
            return runCatching { LayoutQrTransferCodec.parseChunk(payload) }.getOrNull()?.encode()
        }

        fun decodeChunkCandidates(target: Bitmap): List<String> {
            val candidates = linkedSetOf<String>()
            fun collectFromBitmap(bitmapForDecode: Bitmap) {
                decodeMultiple(bitmapForDecode, hints).forEach { text ->
                    normalizeValidChunkText(text)?.let(candidates::add)
                }
                normalizeValidChunkText(decodeSingle(bitmapForDecode, hints))?.let(candidates::add)
            }

            collectFromBitmap(target)
            val baseW = target.width
            val baseH = target.height
            if (baseW <= 0 || baseH <= 0) return candidates.toList()
            val scales = floatArrayOf(2.0f, 1.5f, 1.25f, 0.9f, 0.75f, 0.6f)
            scales.forEach { factor ->
                val scaledW = maxOf(24, (baseW * factor).toInt())
                val scaledH = maxOf(24, (baseH * factor).toInt())
                if (scaledW == baseW && scaledH == baseH) return@forEach
                val scaled = Bitmap.createScaledBitmap(target, scaledW, scaledH, false)
                try {
                    collectFromBitmap(scaled)
                } finally {
                    scaled.recycle()
                }
            }
            return candidates.toList()
        }

        fun decodeMultipleAndCollect(target: Bitmap): Boolean {
            decodeChunkCandidates(target).forEach { text ->
                if (addDecodedText(text).completed) return true
            }
            return false
        }

        // Phase 0: follow web-editor strategy first: try full-image multi-QR detection,
        // then page-window multi-QR detection before positional scanning.
        if (decodeMultipleAndCollect(bitmap)) return found.toList()
        if (!hasCompleteChunkSet()) {
            val scanWindowHeight = maxOf(pageHeight, scaledQrSize + scaledPadding * 2)
            val scanStep = maxOf(1, pageHeight / 2)
            var top = 0
            while (top < bitmap.height) {
                val height = minOf(scanWindowHeight, bitmap.height - top)
                if (height <= 16) break
                val window = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
                try {
                    if (decodeMultipleAndCollect(window)) return found.toList()
                } finally {
                    window.recycle()
                }
                top += scanStep
            }
        }

        fun decodeAtTop(
            qrTop: Int,
            xAdjust: Int = 0,
            sizeMultiplier: Float = 1f
        ): String? {
            if (qrTop < 0 || qrTop >= bitmap.height) return null
            leftCandidates.forEach { left ->
                val adjustedLeft = (left + xAdjust).coerceIn(0, bitmap.width - 1)
                val preferredCropSize = maxOf(24, (cropSize * sizeMultiplier).toInt())
                val actualCropSize = minOf(preferredCropSize, bitmap.width - adjustedLeft, bitmap.height - qrTop)
                if (actualCropSize <= 0) return@forEach
                val cropped = Bitmap.createBitmap(bitmap, adjustedLeft, qrTop, actualCropSize, actualCropSize)
                try {
                    decodeChunkCandidates(cropped).firstOrNull()?.let { return it }
                } finally {
                    cropped.recycle()
                }
            }
            return null
        }

        fun findFirstQrWithStep(step: Int): Int {
            val actualStep = maxOf(1, step)
            var scanY = 0
            while (scanY + scaledPadding + scaledQrSize <= bitmap.height) {
                val qrTop = scanY + scaledPadding
                val text = decodeAtTop(qrTop)
                if (text != null) {
                    addDecodedText(text)
                    return qrTop
                }
                scanY += actualStep
            }
            return -1
        }

        // Phase 1: Find the first QR code.
        // First do a coarse scan, then a finer fallback scan for variable preview heights.
        var firstQrAbsoluteY = findFirstQrWithStep(scaledPadding)
        if (firstQrAbsoluteY < 0) {
            firstQrAbsoluteY = findFirstQrWithStep(maxOf(2, scaledPadding / 6))
        }
        if (firstQrAbsoluteY < 0) {
            firstQrAbsoluteY = findFirstQrWithStep(1)
        }

        if (firstQrAbsoluteY < 0) {
            // No QR found at regular positions; try full bitmap decode.
            decodeSingle(bitmap, hints)?.let { return listOf(it) }
            return emptyList()
        }

        // Phase 2: Find subsequent QR codes.
        // They should be at intervals of pageHeight from the first QR position.
        // But allow some tolerance for positioning variations.
        val scanTolerance = maxOf(16, (50 * scale).toInt())
        val scanStep = maxOf(4, scaledPadding / 2)
        var index = 1
        while (true) {
            val expectedQrY = firstQrAbsoluteY + index * pageHeight
            if (expectedQrY - scanTolerance >= bitmap.height) break

            // Try scanning around the expected position with tolerance.
            for (offset in -scanTolerance..scanTolerance step scanStep) {
                val qrY = expectedQrY + offset
                val text = decodeAtTop(qrY)
                if (text != null) {
                    val result = addDecodedText(text)
                    if (result.completed) return found.toList()
                    if (result.isNew) break
                }
            }

            index++
        }

        if (!hasCompleteChunkSet()) {
            val legacyPages = maxOf(1, (bitmap.height + pageHeight - 1) / pageHeight)
            var i = 0
            while (i < legacyPages) {
                val qrTop = i * pageHeight + scaledPadding
                if (addDecodedText(decodeAtTop(qrTop)).completed) return found.toList()
                i++
            }
        }

        if (!hasCompleteChunkSet()) {
            val pages = maxOf(1, (bitmap.height + pageHeight - 1) / pageHeight + 1)
            val phaseStep = maxOf(6, scaledPadding / 2)
            val xOffsets = intArrayOf(0, (cropSize * 0.01f).toInt(), -(cropSize * 0.01f).toInt())
            val sizeJitter = floatArrayOf(1f, 0.985f, 1.01f)
            var phase = 0
            while (phase < pageHeight && !hasCompleteChunkSet()) {
                var page = 0
                while (page < pages && !hasCompleteChunkSet()) {
                    val y = phase + scaledPadding + page * pageHeight
                    if (y >= bitmap.height) break
                    xOffsets.forEach { xo ->
                        sizeJitter.forEach { mul ->
                            if (addDecodedText(decodeAtTop(y, xAdjust = xo, sizeMultiplier = mul)).completed) {
                                return found.toList()
                            }
                        }
                    }
                    page++
                }
                phase += phaseStep
            }
        }

        return if (found.isNotEmpty()) found.toList()
        else {
            decodeSingle(bitmap, hints)?.let { listOf(it) } ?: emptyList()
        }
    }

    private fun decodeSingle(bitmap: Bitmap, hints: Map<DecodeHintType, Any>): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val variants = buildList<LuminanceSource> {
            add(source)
            add(source.invert())
        }
        variants.forEach { luminance ->
            val hybrid = BinaryBitmap(HybridBinarizer(luminance))
            runCatching { MultiFormatReader().decode(hybrid, hints).text }.getOrNull()?.let { return it }
            runCatching { QRCodeReader().decode(hybrid, hints).text }.getOrNull()?.let { return it }

            val histogram = BinaryBitmap(GlobalHistogramBinarizer(luminance))
            runCatching { MultiFormatReader().decode(histogram, hints).text }.getOrNull()?.let { return it }
            runCatching { QRCodeReader().decode(histogram, hints).text }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun decodeMultiple(bitmap: Bitmap, hints: Map<DecodeHintType, Any>): List<String> {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return emptyList()
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val variants = buildList<LuminanceSource> {
            add(source)
            add(source.invert())
        }
        val found = linkedSetOf<String>()
        val readers = listOf(
            GenericMultipleBarcodeReader(MultiFormatReader()),
            GenericMultipleBarcodeReader(QRCodeReader())
        )
        variants.forEach { luminance ->
            val bitmaps = listOf(
                BinaryBitmap(HybridBinarizer(luminance)),
                BinaryBitmap(GlobalHistogramBinarizer(luminance))
            )
            bitmaps.forEach { binary ->
                readers.forEach { reader ->
                    runCatching { reader.decodeMultiple(binary, hints) }
                        .getOrNull()
                        ?.forEach { result -> found += result.text }
                }
            }
        }
        return found.toList()
    }
}
