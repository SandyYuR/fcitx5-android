/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * 分片对齐：插件把每片 base64 直接拼接后整体解码，所以宿主必须保证每片都是 base64 量子（3 字节）的整数倍
 * （除最后一片外不带填充），否则拼接串中间会出现 `=`，解码直接失败。
 *
 * 同时每片必须是 16bit 采样（2 字节）的整数倍：火山引擎豆包流式识别收到奇数长度的音频包会回
 * 55000000 内部错误。两约束取最小公倍数 6。
 */
class JsVoicePcmAlignerTest {

    @Test
    fun alignedPacketsArePassedThroughWithoutCopying() {
        val aligner = JsVoicePcmAligner()
        val pcm = ByteArray(3204) { it.toByte() }
        assertSame(pcm, aligner.take(pcm))
        assertNull(aligner.flush())
    }

    @Test
    fun capturePacketsLeaveTheRemainderForTheNextSlice() {
        val aligner = JsVoicePcmAligner()
        val first = ByteArray(3200) { 1 }
        val second = ByteArray(3200) { 2 }
        val tail = byteArrayOf(3, 4)

        assertEquals(3198, aligner.take(first)!!.size)
        // 前一片留下 2 字节，与这一片凑成 3202，切走 3198 再留 4。
        assertEquals(3198, aligner.take(second)!!.size)
        // 4 + 2 = 6，正好补齐，不再挂尾巴。
        assertEquals(6, aligner.take(tail)!!.size)
        assertNull(aligner.flush())
    }

    @Test
    fun flushEmitsTheRemainderExactlyOnce() {
        val aligner = JsVoicePcmAligner()
        assertNull(aligner.take(byteArrayOf(1)))
        assertNull(aligner.take(byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(1, 2), aligner.flush())
        assertNull(aligner.flush())
    }

    @Test
    fun flushKeepsAnOddTailEvenByAppendingOneSilentByte() {
        // 尾巴也是音频包：豆包对奇数长度一律回 55000000，所以最后一段补一个 0 采样字节。
        val aligner = JsVoicePcmAligner()
        assertEquals(6, aligner.take(ByteArray(7) { 1 })!!.size)
        assertArrayEquals(byteArrayOf(1, 0), aligner.flush())
        assertNull(aligner.flush())
    }

    @Test
    fun everyDispatchedPacketKeepsAnEvenLength() {
        // 回归：3 字节对齐会切出 3198/3201/3201 这类奇数包，豆包流式识别直接回 55000000。
        val aligner = JsVoicePcmAligner()
        val random = Random(11)
        repeat(500) {
            val packet = ByteArray(random.nextInt(0, 6402)) { random.nextInt(0, 256).toByte() }
            aligner.take(packet)?.let { assertEquals(0, it.size % 2) }
        }
    }

    @Test
    fun noByteIsLostOrReorderedForArbitraryPacketSizes() {
        val random = Random(7)
        val aligner = JsVoicePcmAligner()
        val expected = ByteArrayOutputStream()
        val actual = ByteArrayOutputStream()
        repeat(200) {
            val packet = ByteArray(random.nextInt(0, 5000)) { random.nextInt(0, 256).toByte() }
            expected.write(packet)
            // 空包不该让缓存出问题，也不该产生一次投递。
            aligner.take(packet)?.let { actual.write(it) }
        }
        aligner.flush()?.let { actual.write(it) }
        // flush 可能给奇数尾巴补一个 0 字节，除此之外不得增删或乱序。
        val expectedBytes = expected.toByteArray()
        val actualBytes = actual.toByteArray()
        val padding = actualBytes.size - expectedBytes.size
        assertTrue("unexpected padding $padding", padding == 0 || padding == 1)
        if (padding == 1) assertEquals(0, actualBytes.last().toInt())
        assertArrayEquals(expectedBytes, actualBytes.copyOfRange(0, expectedBytes.size))
    }
}
