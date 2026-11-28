/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import com.dokar.quickjs.binding.function
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Fake time, real bundled callback: model discovery cannot reset time or request count. */
class JsAppRetryBudgetTest {
    private data class Reply(val milliseconds: Long, val status: Int, val body: String)
    private val modelError = """{"error":{"message":"model_not_found"}}"""
    private val models = """{"data":[{"id":"Model-X"}]}"""
    private val success = """{"choices":[{"message":{"content":"translated"}}]}"""

    private inner class Harness : AutoCloseable {
        val js = JsVoiceRuntime.create()
        val requests = mutableListOf<JsonObject>()
        val replies = ArrayDeque<Reply>()
        val editor = mutableListOf<String>()
        var now = 0L
        suspend fun initialize() {
            JsVoiceStubNatives.install(js, "en", JsHostSession.APP)
            js.function("__testNow") { now.toDouble() }
            js.function("__n_appSleep") { args -> now += args[0].toString().toDouble().toLong(); null }
            js.function("__n_appEditor") { args -> editor += args[0].toString(); null }
            js.function("__n_appHttp") { args ->
                requests += Json.parseToJsonElement(args[0].toString()).jsonObject
                check(replies.isNotEmpty()) { "Unexpected extra request" }
                val reply = replies.removeFirst()
                assertTrue(reply.milliseconds <= requests.last()["timeoutMs"]!!.jsonPrimitive.long)
                now += reply.milliseconds
                buildJsonObject { put("status", reply.status); put("ok", reply.status in 200..299); put("body", reply.body) }.toString()
            }
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE + "\nDate.now = function(){return Number(__testNow());};\n" +
                File("src/main/assets/bundled/js-apps/llm-assistant.js").readText())
        }
        suspend fun send() {
            val context = buildJsonObject {
                put("appId", "llm_assistant"); put("event", "send")
                put("settings", buildJsonObject {
                    put("customUrl", "https://relay.example/api/v1"); put("customModel", "model-x")
                    put("customProtocol", "chat")
                })
                put("configuredSecrets", buildJsonArray { add("customKey") })
                put("values", buildJsonObject { put("input", "hello"); put("provider", "custom"); put("purpose", "polish") })
            }
            val outcome = JsVoiceInvoker.call({ js.evaluate<String>(it) }, "app", context.toString(), 5_000)
            assertNull(outcome.error)
        }
        fun timeouts() = requests.map { it["timeoutMs"]!!.jsonPrimitive.long }
        override fun close() = js.close()
    }

    @Test fun discoveryAndCorrectedGenerationShareOneDeadlineAndThreeRequests() = runBlocking {
        Harness().use { h ->
            h.initialize()
            h.replies += Reply(10_000, 400, modelError)
            h.replies += Reply(10_000, 200, models)
            h.replies += Reply(10_000, 200, success)
            h.send()
            assertEquals(listOf(60_000L, 50_000L, 40_000L), h.timeouts())
            assertEquals(listOf("POST", "GET", "POST"), h.requests.map { it["method"]!!.jsonPrimitive.content })
            assertTrue(h.editor.contains("setComposition"))
        }
    }

    @Test fun aCorrectedModelFailureCannotStartAnotherRetrySeries() = runBlocking {
        Harness().use { h ->
            h.initialize()
            h.replies += Reply(19_000, 400, modelError)
            h.replies += Reply(20_000, 200, models)
            h.replies += Reply(19_000, 429, "busy")
            h.send()
            assertEquals(listOf(60_000L, 41_000L, 21_000L), h.timeouts())
            assertFalse(h.editor.contains("setComposition"))
            assertEquals(58_000L, h.now)
        }
    }

    @Test fun aSlowFailureDoesNotStartModelDiscovery() = runBlocking {
        Harness().use { h ->
            h.initialize(); h.replies += Reply(20_001, 400, modelError); h.send()
            assertEquals(1, h.requests.size)
            assertFalse(h.editor.contains("setComposition"))
        }
    }

    @Test fun alternativeModelEndpointsAlsoConsumeTheSharedRequestCount() = runBlocking {
        Harness().use { h ->
            h.initialize()
            h.replies += Reply(1_000, 400, modelError)
            h.replies += Reply(1_000, 404, "missing")
            h.replies += Reply(1_000, 200, models)
            h.send()
            assertEquals(3, h.requests.size)
            assertEquals(listOf("POST", "GET", "GET"), h.requests.map { it["method"]!!.jsonPrimitive.content })
            assertFalse(h.editor.contains("setComposition"))
        }
    }
}
