/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class JsVoiceBudgetTest {
    @Test fun audioQueueBoundsDurationAndStopsAcceptingAudioAfterEndStream() = runBlocking {
        val queue = JsVoiceCommandQueue(16000)
        repeat(30) { assertTrue(queue.offer(JsVoiceCommand.Audio(ByteArray(3200)))) }
        assertFalse(queue.offer(JsVoiceCommand.Audio(ByteArray(3200))))
        queue.receive()
        assertTrue(queue.offer(JsVoiceCommand.Audio(ByteArray(3200))))
        assertTrue(queue.offer(JsVoiceCommand.Stop))
        assertTrue(queue.offer(JsVoiceCommand.Audio(ByteArray(3200))))
        repeat(30) { assertTrue(queue.receive() is JsVoiceCommand.Audio) }
        assertSame(JsVoiceCommand.Stop, queue.receive())
        queue.close()
        assertFalse(queue.offer(JsVoiceCommand.Stop))
    }

    @Test fun messagesAreBoundedByRetainedBytes() {
        val queue = JsVoiceCommandQueue(16000)
        assertFalse(queue.offer(JsVoiceCommand.Message("1", "x".repeat(3 * 1024 * 1024))))
        queue.close()
    }

    @Test fun compressedResponseCannotExpandBeyondResponseBudget() {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(ByteArray(JsVoiceRuntime.RESPONSE_BYTES + 1)) }
        assertTrue(runCatching { JsVoiceHelpers.gunzip(output.toByteArray()) }.isFailure)
    }
}
