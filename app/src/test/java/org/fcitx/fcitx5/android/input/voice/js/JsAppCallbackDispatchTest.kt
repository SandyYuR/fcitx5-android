/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsAppCallbackDispatchTest {
    @Test fun webSocketAndStreamCallbacksContinueAfterAnInvocationTimeout() = runBlocking {
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN", JsHostSession.APP)
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE + "\n" + """
                var delivered = [];
                foxy.websocket.onMessage(function(socket, text) {
                  delivered.push(text);
                  return text === 'stall' ? new Promise(function() {}) : text;
                });
                foxy.http.onChunk(function(id, event, data) {
                  delivered.push(data);
                  return data === 'stall' ? new Promise(function() {}) : data;
                });
            """.trimIndent())
            for (name in listOf("ws", "stream")) {
                for (text in listOf("stall", "next")) {
                    val context = if (name == "ws") {
                        """{"kind":"message","socket":"1","payload":${JsonPrimitive(text)}}"""
                    } else {
                        """{"kind":"chunk","id":"1","event":"message","data":${JsonPrimitive(text)}}"""
                    }
                    var outcome: JsVoiceInvoker.Outcome? = null
                    JsAppCallbackDispatch.run {
                        outcome = JsVoiceInvoker.call({ js.evaluate<String>(it) }, name, context, 1_000)
                    }
                    if (text == "next") assertEquals(text, outcome!!.result!!.jsonPrimitive.content)
                    else assertEquals(null, outcome)
                }
            }
            assertEquals("[\"stall\",\"next\",\"stall\",\"next\"]", js.evaluate<String>("JSON.stringify(delivered)"))
            assertEquals("0", js.evaluate<String>("String(Object.keys(__pending).length)"))
        } finally {
            js.close()
        }
    }

    @Test fun explicitCallbackCancellationStillStopsDelivery() = runBlocking {
        val cancellation = CancellationException("Stream is no longer active")
        val failure = runCatching {
            JsAppCallbackDispatch.run { throw cancellation }
        }.exceptionOrNull()
        assertTrue(failure === cancellation)
    }

    @Test fun cancellingTheConsumerStillStopsDelivery() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var deliveredAfterCancellation = false
        val consumer = async {
            JsAppCallbackDispatch.run {
                entered.complete(Unit)
                awaitCancellation()
            }
            deliveredAfterCancellation = true
        }
        entered.await()
        consumer.cancelAndJoin()
        assertTrue(consumer.isCancelled)
        assertFalse(deliveredAfterCancellation)
    }

    @Test fun anOuterTimeoutStillStopsDelivery() = runBlocking {
        var deliveredAfterTimeout = false
        val failure = runCatching {
            withTimeout(50) {
                JsAppCallbackDispatch.run { awaitCancellation() }
                deliveredAfterTimeout = true
            }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertFalse(deliveredAfterTimeout)
    }
}
