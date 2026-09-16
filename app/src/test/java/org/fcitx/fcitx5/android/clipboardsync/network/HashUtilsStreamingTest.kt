/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.clipboardsync.network

import org.junit.Assert
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Random

/**
 * 覆盖上传后记录 `size + hash` 用的流式摘要。
 *
 * 关键约束是**与旧的整块实现逐字节一致**：`RecentUploadedFile` 的 hash 会持久化，
 * 跨重启的去重比较依赖它，所以这里既对已知向量，也对"分块边界"逐长度比对两种实现。
 */
class HashUtilsStreamingTest {

    /** 故意每次只吐很少字节的流，模拟真实 provider 的短读。 */
    private class TrickleInputStream(private val data: ByteArray, private val step: Int) : InputStream() {
        private var offset = 0
        override fun read(): Int =
            if (offset < data.size) data[offset++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (offset >= data.size) return -1
            val count = minOf(step, len, data.size - offset)
            System.arraycopy(data, offset, b, off, count)
            offset += count
            return count
        }
    }

    @Test
    fun matchesKnownVectors() {
        Assert.assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HashUtils.sha256AndSize(ByteArrayInputStream(ByteArray(0))).second
        )
        Assert.assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashUtils.sha256AndSize(ByteArrayInputStream("abc".toByteArray())).second
        )
    }

    @Test
    fun streamingMatchesWholeArrayForEveryBufferBoundary() {
        // 8KB 是内部缓冲区大小，逐个跨过边界（含空、恰好一块、一块多一字节、多块）取样本。
        val sizes = intArrayOf(0, 1, 2, 1023, 8191, 8192, 8193, 16383, 16384, 16385, 100_000)
        val random = Random(20260917L)
        sizes.forEach { size ->
            val data = ByteArray(size).also { random.nextBytes(it) }
            val (streamedSize, streamedHash) = HashUtils.sha256AndSize(ByteArrayInputStream(data))
            Assert.assertEquals("size mismatch at $size", size.toLong(), streamedSize)
            Assert.assertEquals("hash mismatch at $size", HashUtils.sha256(data), streamedHash)
        }
    }

    @Test
    fun shortReadsDoNotChangeResult() {
        val data = ByteArray(50_000).also { Random(7L).nextBytes(it) }
        val expected = HashUtils.sha256(data)
        listOf(1, 3, 4096, 8192).forEach { step ->
            val (size, hash) = HashUtils.sha256AndSize(TrickleInputStream(data, step))
            Assert.assertEquals("size mismatch for step $step", data.size.toLong(), size)
            Assert.assertEquals("hash mismatch for step $step", expected, hash)
        }
    }

    @Test
    fun hexFormatIsLowercaseHexPairsOf64Chars() {
        val hash = HashUtils.sha256AndSize(ByteArrayInputStream(ByteArray(1024))).second
        Assert.assertEquals(64, hash.length)
        Assert.assertTrue("must be lowercase hex", hash.all { it in '0'..'9' || it in 'a'..'f' })
        Assert.assertEquals(hash, hash.lowercase())
    }

    @Test
    fun calculateFileHashStillUsesTheSameSha256() {
        // 文件哈希由 sha256(ByteArray) 派生，共用同一条编码路径，这里锁住它的输入关系。
        val bytes = "hello".toByteArray()
        val expected = HashUtils.sha256("hello|${HashUtils.sha256(bytes).uppercase()}").lowercase()
        Assert.assertEquals(expected, HashUtils.calculateFileHash("hello", bytes).lowercase())
    }
}