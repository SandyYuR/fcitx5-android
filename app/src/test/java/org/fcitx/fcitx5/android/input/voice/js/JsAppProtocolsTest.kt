/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import com.dokar.quickjs.binding.function
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercise the actual bundled script, including request building, retries and editor actions. */
class JsAppProtocolsTest {
    private val script = File("src/main/assets/bundled/js-apps/llm-assistant.js").readText()
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private val messagesReply = """{"type":"message","stop_reason":"end_turn","content":[
        {"type":"thinking","thinking":"hidden"},{"type":"text","text":"Hello"},
        {"type":"tool_use","name":"ignored","text":"not output"},{"type":"text","text":" world"}]}"""
    private val responsesReply = """{"status":"completed","output":[
        {"type":"reasoning","summary":[{"text":"hidden"}]},
        {"type":"function_call","name":"ignored","text":"not output"},
        {"type":"message","role":"assistant","status":"completed","content":[
            {"type":"output_text","text":"Hello"},{"type":"output_text","text":" world"}]}]}"""

    private inner class Harness(
        protocol: String,
        val provider: String = "custom",
        extra: Map<String, String> = emptyMap(),
    ) : AutoCloseable {
        val js = JsVoiceRuntime.create()
        val requests = mutableListOf<JsonObject>()
        val editor = mutableListOf<Pair<String, String>>()
        val replies = ArrayDeque<Pair<Int, String>>()
        val transportErrors = ArrayDeque<String>()
        val settings = mapOf("defaultProvider" to provider, "${provider}Protocol" to protocol,
            "${provider}Url" to "https://relay.example/api/v1/", "${provider}Model" to "model-x",
            "${provider}MaxTokens" to "2048") + extra
        suspend fun initialize() {
            JsVoiceStubNatives.install(js, "zh-CN")
            installJsAppDelay(js) {}
            js.function("__n_appEditor") { args ->
                editor += args[0].toString() to args[1].toString()
                if (args[0] == "getSelection") "原文" else null
            }
            js.function("__n_appHttp") { args ->
                requests += json(args[0].toString())
                if (transportErrors.isNotEmpty()) error(transportErrors.removeFirst())
                check(replies.isNotEmpty()) { "Unexpected network request" }
                val (code, body) = replies.removeFirst()
                buildJsonObject { put("status", code); put("ok", code in 200..299); put("body", body) }.toString()
            }
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE + "\n" + script)
            invoke("open")
        }
        suspend fun invoke(event: String, purpose: String = "polish", input: String = "你好\n\"quoted\""): JsonObject {
            val context = buildJsonObject {
                put("appId", "llm_assistant"); put("event", event)
                put("settings", buildJsonObject { settings.forEach { (key, value) -> put(key, value) } })
                put("configuredSecrets", buildJsonArray { add("${provider}Key") })
                put("values", buildJsonObject { put("input", input); put("provider", provider); put("purpose", purpose) })
            }
            val outcome = JsVoiceInvoker.call({ js.evaluate<String>(it) }, "app", context.toString(), 5000)
            assertNull(outcome.error)
            return outcome.result!!.jsonObject
        }
        fun body(index: Int = 0) = json(requests[index].text("body"))
        fun assertNoOutput() { assertFalse(editor.any { it.first in setOf("setComposition", "commit") }) }
        override fun close() { js.close() }
    }

    @Test fun messagesUsesNativeHeadersAndBodyAndOnlyPreviewsVisibleText() = runBlocking {
        Harness("anthropic", extra = mapOf("customUrl" to "https://relay.example/prefix/v1/messages?route=a"))
            .use { h ->
                h.initialize(); h.replies += 200 to messagesReply; h.invoke("send")
                val request = h.requests.single()
                assertEquals("https://relay.example/prefix/v1/messages?route=a", request.text("url"))
                assertEquals("x-api-key", request.text("authHeader"))
                assertEquals("", request.text("authPrefix"))
                assertEquals("2023-06-01", request["headers"]!!.jsonObject.text("anthropic-version"))
                val body = h.body()
                assertEquals(2048, body["max_tokens"]!!.jsonPrimitive.int)
                assertTrue(body.text("system").isNotBlank())
                assertEquals("user", body["messages"]!!.jsonArray.single().jsonObject.text("role"))
                assertTrue(body["messages"]!!.toString().contains("quoted"))
                assertFalse(body.containsKey("max_output_tokens"))
                assertEquals("setComposition" to "Hello world", h.editor.last())
                h.invoke("confirm")
                assertEquals("commit" to "Hello world", h.editor.last())
            }
    }

    @Test fun responsesUsesNativeInputAndParsesOutputArrayWithoutSdkConvenienceFields() = runBlocking {
        Harness("responses", "openai", mapOf("openaiUrl" to "https://api.openai.com/v1/chat/completions?route=b"))
            .use { h ->
                h.initialize(); h.replies += 200 to responsesReply; h.invoke("send")
                val request = h.requests.single()
                assertEquals("https://api.openai.com/v1/responses?route=b", request.text("url"))
                assertEquals("Authorization", request.text("authHeader"))
                assertEquals("Bearer ", request.text("authPrefix"))
                assertFalse(request["headers"]!!.jsonObject.containsKey("anthropic-version"))
                val body = h.body()
                assertEquals(2048, body["max_output_tokens"]!!.jsonPrimitive.int)
                assertTrue(body.text("instructions").isNotBlank())
                val input = body["input"]!!.jsonArray.single().jsonObject
                assertEquals("user", input.text("role"))
                assertEquals("input_text", input["content"]!!.jsonArray.single().jsonObject.text("type"))
                assertFalse(body["store"]!!.jsonPrimitive.boolean)
                assertFalse(body["stream"]!!.jsonPrimitive.boolean)
                assertFalse(body.containsKey("messages"))
                assertEquals("setComposition" to "Hello world", h.editor.last())
                h.invoke("close")
                assertEquals("clearComposition", h.editor.last().first)
            }
    }

    @Test fun upstream123MartianTaskWorksAcrossAllThreeProtocols() = runBlocking {
        for (protocol in listOf("chat", "anthropic", "responses")) Harness(protocol).use { h ->
            h.initialize()
            h.replies += 200 to when (protocol) {
                "anthropic" -> messagesReply
                "responses" -> responsesReply
                else -> """{"choices":[{"message":{"content":"Hello world"}}]}"""
            }
            h.invoke("send", "martian")
            val body = h.body()
            val prompt = if (protocol == "responses") {
                body["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject.text("text")
            } else body["messages"]!!.jsonArray.last().jsonObject.text("content")
            assertTrue(protocol, prompt.startsWith("火星文转换："))
            assertTrue(prompt.contains("你好"))
            assertEquals("setComposition" to "Hello world", h.editor.last())
        }
        for (locale in listOf("en", "zh-CN", "zh-TW")) {
            val purposes = JsVoiceScript.describe(script, locale).single { it.key == "defaultPurpose" }.options
            assertEquals(8, purposes.size)
            assertTrue(purposes.single { it.value == "martian" }.label.isNotBlank())
        }
    }

    @Test fun completeAndBaseEndpointsPreserveRelayPrefixesAndQueriesForAllProtocols() = runBlocking {
        Harness("chat").use { h ->
            h.initialize()
            for ((protocol, suffix) in mapOf("chat" to "chat/completions", "anthropic" to "messages", "responses" to "responses")) {
                for (url in listOf("https://relay.example/prefix/v1/", "https://relay.example/prefix/v1/chat/completions",
                    "https://relay.example/prefix/v1/messages", "https://relay.example/prefix/v1/responses")) {
                    val actual = h.js.evaluate<String>("endpointUrl(" + JsonPrimitive("$url?route=1#fragment") + "," + JsonPrimitive(protocol) + ")")
                    assertEquals("https://relay.example/prefix/v1/$suffix?route=1", actual)
                }
            }
        }
    }

    private suspend fun reject(protocol: String, reply: String, expected: String) {
        Harness(protocol).use { h ->
            h.initialize(); h.replies += 200 to reply
            val dialog = h.invoke("send")
            assertTrue(dialog.toString(), dialog.toString().contains(expected))
            h.assertNoOutput()
            h.replies += 200 to reply
            assertTrue(h.invoke("confirm").containsKey("layout"))
            h.assertNoOutput()
            assertEquals(2, h.requests.size)
        }
    }

    @Test fun nativeTruncationAndPendingResponsesNeverBecomeEditorOutput() = runBlocking {
        reject("anthropic", """{"stop_reason":"max_tokens","content":[{"type":"text","text":"partial"}]}""", "未完整")
        for (status in listOf("incomplete", "in_progress", "failed", "cancelled")) {
            reject("responses", """{"status":"$status","output_text":"partial","incomplete_details":{"reason":"max_output_tokens"}}""", "未完整")
        }
    }

    @Test fun chatTruncationRefusalsAndToolCallsNeverBecomeEditorOutput() = runBlocking {
        for (reason in listOf("length", "tool_calls", "function_call")) {
            reject("chat", """{"choices":[{"finish_reason":"$reason","message":{"content":"partial"}}]}""", "未完整")
        }
        reject("chat", """{"choices":[{"finish_reason":"content_filter","message":{"content":"partial"}}]}""", "拒绝")
        reject("chat", """{"choices":[{"finish_reason":"stop","message":{"content":"partial","refusal":"declined"}}]}""", "拒绝")
    }

    @Test fun transientErrorsWaitBeforeRetryingTheRealScript() = runBlocking {
        Harness("chat").use { h ->
            h.initialize()
            h.replies += 429 to """{"error":{"message":"rate limit"}}"""
            h.replies += 200 to """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""
            val elapsed = kotlin.system.measureTimeMillis { h.invoke("send") }
            assertEquals(2, h.requests.size)
            assertEquals("setComposition" to "ok", h.editor.last())
            assertTrue("A retry must actually back off, elapsed=$elapsed", elapsed >= 850)
        }
    }

    @Test fun slowTransportErrorsAreNotRetriedOutsideTheBudget() = runBlocking {
        Harness("chat").use { h ->
            h.initialize()
            h.js.evaluate<Any?>("""
                var testNow = 0; Date.now = function () { return testNow; };
                var realRequest = foxy.http.request;
                foxy.http.request = async function (options) {
                    try { return await realRequest(options); } finally { testNow += 25000; }
                };
            """.trimIndent())
            h.transportErrors += "socket timeout"
            h.replies += 200 to """{"choices":[{"message":{"content":"late"}}]}"""
            h.invoke("send")
            assertEquals(1, h.requests.size)
            h.assertNoOutput()
        }
    }

    @Test fun refusalsAndToolOnlyRepliesAreNotCommitted() = runBlocking {
        reject("anthropic", """{"stop_reason":"refusal","content":[]}""", "拒绝")
        reject("anthropic", """{"stop_reason":"tool_use","content":[{"type":"tool_use","text":"hidden"}]}""", "未完整")
        reject("responses", """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"declined"}]}]}""", "拒绝")
        reject("responses", """{"status":"completed","output":[{"type":"function_call","text":"hidden"}]}""", "空内容")
    }

    @Test fun modelDiscoveryUsesNativeAuthenticationAndTheCorrectModelsPath() = runBlocking {
        for (protocol in listOf("anthropic", "responses")) Harness(protocol).use { h ->
            h.initialize()
            h.replies += 400 to """{"error":{"message":"model_not_found"}}"""
            h.replies += 200 to """{"data":[{"id":"MODEL-X"}]}"""
            h.replies += 200 to if (protocol == "anthropic") messagesReply else responsesReply
            h.invoke("send")
            assertEquals(3, h.requests.size)
            assertEquals("GET", h.requests[1].text("method"))
            assertEquals("https://relay.example/api/v1/models", h.requests[1].text("url"))
            assertEquals(h.requests[0].text("authHeader"), h.requests[1].text("authHeader"))
            assertEquals(h.requests[0]["headers"]!!.jsonObject.text("anthropic-version"), h.requests[1]["headers"]!!.jsonObject.text("anthropic-version"))
            assertEquals("MODEL-X", h.body(2).text("model"))
            assertEquals("setComposition" to "Hello world", h.editor.last())
        }
    }

    @Test fun invalidTokenLimitsNeverReachTheNetwork() = runBlocking {
        for (limit in listOf("0", "-1", "1.5", "NaN", "131073")) Harness("anthropic", extra = mapOf("customMaxTokens" to limit)).use { h ->
            h.initialize(); assertTrue(h.invoke("send").toString().contains("整数"))
            assertTrue(h.requests.isEmpty()); h.assertNoOutput()
        }
    }

    @Test fun protocolSettingsPreserveOldFieldsAndDefaultOldProvidersToChat() = runBlocking {
        for (locale in listOf("en", "zh-CN", "zh-TW")) {
            val fields = JsVoiceScript.describe(script, locale)
            assertEquals("chat", fields.single { it.key == "openaiProtocol" }.defaultValue)
            assertEquals("chat", fields.single { it.key == "customProtocol" }.defaultValue)
            assertEquals("anthropic", fields.single { it.key == "anthropicProtocol" }.defaultValue)
            assertEquals(setOf("chat", "anthropic", "responses"), fields.single { it.key == "customProtocol" }.options.map { it.value }.toSet())
            val values = JsVoiceFields.currentValues(fields, mapOf("customUrl" to "https://saved.example/v1", "customModel" to "saved-model"))
            assertEquals("https://saved.example/v1", values["customUrl"])
            assertEquals("saved-model", values["customModel"])
            assertEquals("chat", values["customProtocol"])
            assertTrue(fields.single { it.key == "customKey" }.isSecret)
        }
    }

    @Test fun failedSecondRequestClearsPreviousPreviewAndCannotConfirmTheOldResult() = runBlocking {
        Harness("responses").use { h ->
            h.initialize(); h.replies += 200 to responsesReply; h.invoke("send")
            val oldOutputs = h.editor.count { it.first in setOf("setComposition", "commit") }
            h.replies += 401 to """{"error":{"message":"invalid API key"}}"""
            assertTrue(h.invoke("send").toString().contains("invalid API key"))
            assertEquals("clearComposition", h.editor.last().first)
            h.replies += 401 to """{"error":{"message":"invalid API key"}}"""
            assertTrue(h.invoke("confirm").containsKey("layout"))
            assertEquals(oldOutputs, h.editor.count { it.first in setOf("setComposition", "commit") })
        }
    }
    @Test fun confirmWithoutAResultGeneratesAcrossAllProtocolsAndKeepsTheDialogOpen() = runBlocking {
        for (protocol in listOf("chat", "anthropic", "responses")) Harness(protocol).use { h ->
            h.initialize()
            h.replies += 200 to when (protocol) {
                "anthropic" -> messagesReply
                "responses" -> responsesReply
                else -> """{"choices":[{"message":{"content":"Hello world"}}]}"""
            }
            val reply = h.invoke("confirm")
            assertTrue(reply.containsKey("layout"))
            assertFalse(reply.containsKey("close"))
            assertEquals(1, h.requests.size)
            assertEquals("setComposition" to "Hello world", h.editor.last())
            assertFalse(h.editor.any { it.first == "commit" })
            assertTrue(h.invoke("confirm")["close"]!!.jsonPrimitive.boolean)
            assertEquals("commit" to "Hello world", h.editor.last())
            assertEquals(1, h.requests.size)
        }
    }

    @Test fun emptyInputNeverClosesOrSendsAnAiRequest() = runBlocking {
        for (event in listOf("send", "confirm")) Harness("chat").use { h ->
            h.initialize()
            val reply = h.invoke(event, input = " \n ")
            assertTrue(reply.containsKey("layout"))
            assertTrue(reply.toString().contains("请先输入"))
            assertTrue(h.requests.isEmpty())
            h.assertNoOutput()
        }
    }

    @Test fun editingThePromptMakesConfirmGenerateAgainInsteadOfInsertingAnOldAnswer() = runBlocking {
        Harness("chat").use { h ->
            h.initialize()
            h.replies += 200 to """{"choices":[{"message":{"content":"旧回复"}}]}"""
            h.invoke("send")
            h.replies += 200 to """{"choices":[{"message":{"content":"新回复"}}]}"""
            val reply = h.invoke("confirm", input = "新问题")
            assertTrue(reply.containsKey("layout"))
            assertFalse(h.editor.any { it.first == "commit" })
            assertEquals("setComposition" to "新回复", h.editor.last())
            assertEquals(2, h.requests.size)
            assertTrue(h.body(1).toString().contains("新问题"))
            h.invoke("confirm", input = "新问题")
            assertEquals("commit" to "新回复", h.editor.last())
        }
    }

    @Test fun changingTheTaskClearsTheOldPreviewAndKeepsTheDraft() = runBlocking {
        Harness("chat").use { h ->
            h.initialize()
            h.replies += 200 to """{"choices":[{"message":{"content":"旧回复"}}]}"""
            h.invoke("send")
            val reply = h.invoke("purpose", "tone")
            assertTrue(reply.containsKey("layout"))
            assertFalse(reply.toString().contains("旧回复"))
            assertTrue(reply.toString().contains("quoted"))
            assertEquals("clearComposition", h.editor.last().first)
            assertEquals(1, h.requests.size)
        }
    }

    @Test fun deniedNetworkPermissionDoesNotAutomaticallyPromptAgainButExplicitRetryWorks() = runBlocking {
        Harness("chat").use { h ->
            h.initialize()
            h.transportErrors += "Network permission denied"
            val reply = h.invoke("confirm")
            assertTrue(reply.containsKey("layout"))
            assertTrue(reply.toString().contains("permission denied"))
            assertEquals(1, h.requests.size)
            h.assertNoOutput()
            h.replies += 200 to """{"choices":[{"message":{"content":"重新允许后的回复"}}]}"""
            assertTrue(h.invoke("send").containsKey("layout"))
            assertEquals(2, h.requests.size)
            assertEquals("setComposition" to "重新允许后的回复", h.editor.last())
        }
    }

}
