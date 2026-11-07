/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** A single PNG, rendered in small tiles. The full image is never allocated as a bitmap. */
internal object StatsImageRenderer {
    const val MAX_WIDTH = 1080
    const val MAX_HEIGHT = 24_000
    const val TILE_HEIGHT = 512

    /** The snapshot guard is invoked on both main and IO dispatchers. */
    suspend fun render(view: View, file: File, checkActive: () -> Unit = {}): File = withContext(Dispatchers.Main.immediate) {
        require(view.width in 1..MAX_WIDTH && view.height in 1..MAX_HEIGHT)
        var writer: StatsPngWriter? = null
        var completed = false
        try {
            currentCoroutineContext().ensureActive()
            checkActive()
            withContext(Dispatchers.IO) {
                checkActive()
                check(file.parentFile?.let { it.isDirectory || it.mkdirs() } != false)
                checkActive()
                // Assignment inside the IO block keeps the handle available to finally even
                // when cancellation wins while the result is returning to the main thread.
                writer = StatsPngWriter(file.outputStream(), view.width, view.height)
                checkActive()
            }
            val output = requireNotNull(writer)
            val row = IntArray(view.width)
            var offset = 0
            while (offset < view.height) {
                currentCoroutineContext().ensureActive()
                checkActive()
                val height = minOf(TILE_HEIGHT, view.height - offset)
                val bitmap = Bitmap.createBitmap(view.width, height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.translate(0f, -offset.toFloat())
                    view.draw(canvas)
                    checkActive()
                    withContext(Dispatchers.IO) {
                        repeat(height) { y ->
                            currentCoroutineContext().ensureActive()
                            bitmap.getPixels(row, 0, view.width, 0, y, view.width, 1)
                            output.writeRow(row)
                        }
                    }
                } finally { bitmap.recycle() }
                offset += height
            }
            withContext(Dispatchers.IO) { checkActive(); output.finish(); checkActive() }
            checkActive()
            completed = true
            file
        } catch (error: Throwable) {
            // A privacy reset can remove the destination between a check and an IO
            // call. Prefer cancellation to reporting the resulting missing file.
            checkActive()
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { writer?.close() }
                finally { if (!completed) file.delete() }
            }
        }
    }

    /** Only our UUID PNGs older than a day are eligible; keep several recent copies too. */
    fun prune(folder: File, now: Long = System.currentTimeMillis()) {
        folder.listFiles().orEmpty().filter {
            it.isFile && it.name.matches(Regex("[0-9a-f-]{36}\\.png")) && now - it.lastModified() > 86_400_000L
        }.sortedByDescending { it.lastModified() }.drop(3).forEach { it.delete() }
    }
}

/** PNG: 8-bit straight RGBA, no interlace, filter type 0, one zlib stream across IDAT chunks. */
internal class StatsPngWriter(stream: OutputStream, private val width: Int, private val height: Int) : Closeable {
    private val output = DataOutputStream(stream.buffered())
    private val compressedBytes = ByteArray(32 * 1024)
    private var compressedCount = 0
    private val deflater = Deflater()
    private val compressed = DeflaterOutputStream(object : OutputStream() {
        override fun write(value: Int) {
            compressedBytes[compressedCount++] = value.toByte()
            if (compressedCount == compressedBytes.size) drain()
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var source = offset
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, compressedBytes.size - compressedCount)
                bytes.copyInto(compressedBytes, compressedCount, source, source + count)
                compressedCount += count; source += count; remaining -= count
                if (compressedCount == compressedBytes.size) drain()
            }
        }
    }, deflater, 16 * 1024)
    private val scanline = ByteArray(width * 4 + 1)
    private var rows = 0
    private var finished = false

    init {
        require(width in 1..StatsImageRenderer.MAX_WIDTH && height in 1..StatsImageRenderer.MAX_HEIGHT)
        try {
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val header = java.io.ByteArrayOutputStream(13)
            DataOutputStream(header).use {
                it.writeInt(width); it.writeInt(height)
                it.write(byteArrayOf(8, 6, 0, 0, 0))
            }
            chunk("IHDR", header.toByteArray(), 13)
        } catch (error: Throwable) { deflater.end(); output.close(); throw error }
    }

    fun writeRow(argb: IntArray) {
        check(!finished && rows < height)
        require(argb.size == width)
        scanline[0] = 0
        argb.forEachIndexed { index, color ->
            val at = 1 + index * 4
            scanline[at] = (color ushr 16).toByte()
            scanline[at + 1] = (color ushr 8).toByte()
            scanline[at + 2] = color.toByte()
            scanline[at + 3] = (color ushr 24).toByte()
        }
        compressed.write(scanline)
        rows++
    }

    fun finish() {
        check(!finished && rows == height) { "Statistics PNG is incomplete" }
        compressed.finish(); drain()
        chunk("IEND", byteArrayOf(), 0)
        output.flush()
        finished = true
    }

    private fun drain() {
        if (compressedCount == 0) return
        chunk("IDAT", compressedBytes, compressedCount)
        compressedCount = 0
    }

    private fun chunk(name: String, bytes: ByteArray, length: Int) {
        val type = name.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(bytes, 0, length) }
        output.writeInt(length); output.write(type); output.write(bytes, 0, length); output.writeInt(crc.value.toInt())
    }

    override fun close() { try { output.close() } finally { deflater.end() } }
}
