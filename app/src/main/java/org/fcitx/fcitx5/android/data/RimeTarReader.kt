/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import java.io.EOFException
import java.io.InputStream

/** Small streaming TAR reader: no NIO/FileTime dependency on API 23, no unbounded PAX allocation. */
internal object RimeTarReader {
    private const val RECORD = 512
    private const val MAX_METADATA = 256 * 1024

    fun matches(header: ByteArray): Boolean = runCatching { checksum(header); true }.getOrDefault(false)

    fun extract(input: InputStream, check: () -> Unit, member: (String, Boolean, InputStream) -> Unit) {
        var longName: String? = null
        var local = emptyMap<String, String>()
        val global = mutableMapOf<String, String>()
        var headers = 0
        val buffer = ByteArray(32 * 1024)
        while (true) {
            check()
            val header = ByteArray(RECORD)
            var offset = 0
            while (offset < RECORD) {
                val n = input.read(header, offset, RECORD - offset)
                if (n < 0) {
                    if (offset == 0) return
                    throw EOFException("TAR文件头被截断")
                }
                offset += n
            }
            if (header.all { it == 0.toByte() }) break
            require(++headers <= RimeSchemePayload.MAX_ENTRIES) { "TAR文件头数量过多" }
            checksum(header)
            val type = header[156].toInt().toChar()
            val rawSize = number(header, 124, 12)
            val metadata = type in setOf('x', 'g', 'L')
            require(metadata || type in setOf('\u0000', '0', '5')) { "TAR包包含链接或特殊文件" }
            val size = if (metadata) rawSize else (local["size"] ?: global["size"])?.toLongOrNull() ?: rawSize
            require(size in 0..RimeSchemePayload.MAX_BYTES) { "TAR资源大小无效" }
            val entry = Entry(input, size)
            if (metadata) {
                require(size <= MAX_METADATA) { "TAR元数据过大" }
                val bytes = entry.readBytes()
                when (type) {
                    'L' -> longName = bytes.toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    'x' -> local = pax(bytes)
                    'g' -> global.putAll(pax(bytes))
                }
            } else {
                val prefix = if (text(header, 257, 6) == "ustar" && header[262] == 0.toByte()) text(header, 345, 155) else ""
                val name = local["path"] ?: longName ?: global["path"]
                    ?: listOf(prefix, text(header, 0, 100)).filter(String::isNotEmpty).joinToString("/")
                member(name, type == '5', entry)
                local = emptyMap(); longName = null
            }
            while (entry.read(buffer) >= 0) check()
            val padding = (RECORD - size % RECORD) % RECORD
            var left = padding.toInt()
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(left, buffer.size))
                if (n < 0) throw EOFException("TAR填充被截断")
                left -= n; check()
            }
        }
        // Decode through the compressed trailer so CRC failures cannot be reported as success.
        while (input.read(buffer) >= 0) check()
    }

    private fun pax(bytes: ByteArray): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        var offset = 0
        while (offset < bytes.size) {
            val space = (offset until bytes.size).firstOrNull { bytes[it] == 32.toByte() } ?: error("无效的PAX记录")
            val length = bytes.copyOfRange(offset, space).toString(Charsets.US_ASCII).toIntOrNull()
                ?: error("无效的PAX长度")
            require(length > space - offset + 2 && length <= bytes.size - offset && bytes[offset + length - 1] == 10.toByte()) { "无效的PAX记录" }
            val record = bytes.copyOfRange(space + 1, offset + length - 1).toString(Charsets.UTF_8)
            val split = record.indexOf('=')
            require(split > 0)
            val key = record.substring(0, split)
            require(!key.startsWith("GNU.sparse")) { "不支持稀疏TAR资源" }
            if (key in setOf("path", "size")) {
                val value = record.substring(split + 1)
                if (key == "size") require(value.toLongOrNull()?.let { it in 0..RimeSchemePayload.MAX_BYTES } == true)
                fields[key] = value
            }
            offset += length
        }
        return fields
    }

    private fun checksum(header: ByteArray) {
        require(header.size >= RECORD)
        val expected = number(header, 148, 8)
        val unsigned = (0 until RECORD).sumOf { if (it in 148..155) 32L else (header[it].toInt() and 255).toLong() }
        val signed = (0 until RECORD).sumOf { if (it in 148..155) 32L else header[it].toLong() }
        require(expected == unsigned || expected == signed) { "无效的TAR校验头" }
    }
    private fun number(header: ByteArray, offset: Int, length: Int): Long {
        if ((header[offset].toInt() and 128) != 0) {
            var value = 0L
            for (i in offset until offset + length) {
                val byte = header[i].toInt() and if (i == offset) 127 else 255
                require(value <= RimeSchemePayload.MAX_BYTES / 256) { "TAR数字超出限制" }
                value = value * 256 + byte
            }
            return value
        }
        val value = text(header, offset, length).trim()
        return if (value.isEmpty()) 0 else value.toLongOrNull(8) ?: error("无效的TAR数字")
    }
    private fun text(bytes: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() } ?: offset + length
        return bytes.copyOfRange(offset, end).toString(Charsets.UTF_8)
    }
    private class Entry(private val input: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining == 0L) return -1
            val next = input.read()
            if (next < 0) throw EOFException("TAR资源被截断")
            remaining--; return next
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val n = input.read(bytes, offset, minOf(remaining, length.toLong()).toInt())
            if (n < 0) throw EOFException("TAR资源被截断")
            remaining -= n; return n
        }
    }
}
