/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JsVoiceHelpersTest {
    private val hosts = setOf("dashscope.aliyuncs.com")

    @Test
    fun wavHeaderDescribesPcm() {
        val pcm = ByteArray(320) { it.toByte() }
        val wav = JsVoiceHelpers.wavBytes(pcm, 16000)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        val le = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, le.getInt(24))
        assertEquals(32000, le.getInt(28))
        assertEquals(pcm.size, le.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun allowsOnlyDeclaredSecureHosts() {
        assertTrue(JsVoiceHelpers.isAllowedUrl("wss://dashscope.aliyuncs.com/api-ws/v1/inference", hosts))
        assertTrue(JsVoiceHelpers.isAllowedUrl("https://dashscope.aliyuncs.com:443/x", hosts))
        assertFalse(JsVoiceHelpers.isAllowedUrl("ws://dashscope.aliyuncs.com/x", hosts))
        assertFalse(JsVoiceHelpers.isAllowedUrl("wss://evil.example.com/x", hosts))
        assertFalse(JsVoiceHelpers.isAllowedUrl("wss://dashscope.aliyuncs.com@evil.example.com/x", hosts))
        assertFalse(JsVoiceHelpers.isAllowedUrl("https://dashscope.aliyuncs.com.evil.example.com/", hosts))
    }

    @Test
    fun gzipFrameHasPrefixLengthAndPayload() {
        val prefix = byteArrayOf(1, 2)
        val frame = JsVoiceHelpers.gzipFrame(prefix, "hello".toByteArray())
        assertEquals(1, frame[0].toInt())
        assertEquals(2, frame[1].toInt())
        val len = JsVoiceHelpers.readInt32BE(frame, 2)
        assertEquals(frame.size, 6 + len)
        assertEquals("hello", String(JsVoiceHelpers.gunzip(frame.copyOfRange(6, 6 + len))))
    }
}
