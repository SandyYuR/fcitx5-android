/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import com.dokar.quickjs.binding.function
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Runs the supplied assistant through the production QuickJS prelude and callback pump. */
class JsAppContractTest {
    private val script = File("src/main/assets/bundled/js-apps/llm-assistant.js").readText()

    @Test fun importsAppOnlyPluginAndRetainsCapabilitiesAcrossStorage() = runBlocking {
        val result = JsVoiceImporter.inspect(script, "zh-CN", emptySet()) as JsVoiceImportResult.Success
        assertNull(result.meta.voiceAsr)
        assertEquals("llm_assistant", result.meta.apps.single().id)
        assertEquals("js.llm_assistant", result.meta.apps.single().command)
        assertTrue(result.fieldCount > 10)
        val record = JsVoicePluginRecord(result.meta, "assistant.js", 1)
        assertEquals(result.meta, JsVoicePluginRecord.decode(record.encode())?.meta)
        val manifest = JsVoicePluginMeta.parse(File("src/main/assets/bundled/js-apps/llm-assistant.json").readText())!!
        assertEquals(result.meta.hosts, manifest.hosts)
        assertEquals(result.meta.apps, manifest.apps)
    }

    @Test fun version123HasNoBundledHostsOrEndpointAddressesAndRetainsSavedSettings() = runBlocking {
        val result = JsVoiceImporter.inspect(script, "zh-CN", emptySet()) as JsVoiceImportResult.Success
        assertEquals("1.23.2-fx", result.meta.version)
        assertTrue(result.meta.hosts.isEmpty())
        val fields = JsVoiceScript.describe(script, "zh-CN")
        val endpoints = fields.filter { it.key.endsWith("Url") }
        assertEquals(5, endpoints.size)
        assertTrue(endpoints.all { it.defaultValue.isEmpty() })
        assertFalse(script.contains("https://"))
        val saved = mapOf("deepseekUrl" to "https://saved.example/v1", "deepseekModel" to "saved-model")
        val current = JsVoiceFields.currentValues(fields, saved)
        assertEquals("https://saved.example/v1", current["deepseekUrl"])
        assertEquals("saved-model", current["deepseekModel"])
        assertEquals("", current["openaiUrl"])
        assertTrue(fields.single { it.key == "deepseekKey" }.isSecret)
    }

    @Test fun unconfiguredServiceNeverFallsBackToAnOfficialEndpoint() = runBlocking {
        val js = JsVoiceRuntime.create()
        var requests = 0
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            js.function("__n_appEditor") { _ -> null }
            js.function("__n_appHttp") { _ -> requests++; error("Unexpected request") }
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE + "\n" + script)
            val context = buildJsonObject {
                put("appId", "llm_assistant"); put("event", "send")
                put("settings", buildJsonObject { put("defaultProvider", "openai") })
                put("configuredSecrets", buildJsonArray { add("openaiKey") })
                put("values", buildJsonObject { put("provider", "openai"); put("purpose", "short"); put("input", "hello") })
            }
            val outcome = JsVoiceInvoker.call({ js.evaluate<String>(it) }, "app", context.toString(), 5000)
            assertNull(outcome.error)
            assertTrue(outcome.result.toString().contains("服务配置"))
            assertEquals(0, requests)
        } finally { js.close() }
    }

    @Test fun customEndpointNeedsNoJsWhitelistAndConfirmCommitsOnlyTheResult() = runBlocking {
        val js = JsVoiceRuntime.create()
        val editor = mutableListOf<Pair<String, String>>()
        var request: JsonObject? = null
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            js.function("__n_appEditor") { args ->
                val method = args[0].toString()
                editor += method to args[1].toString()
                if (method == "getSelection") "你好" else null
            }
            js.function("__n_appHttp") { args ->
                request = Json.parseToJsonElement(args[0].toString()).jsonObject
                """{"ok":true,"status":200,"body":"{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}"}"""
            }
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE + "\n" + script)
            suspend fun invoke(event: String, input: String = "你好"): JsonObject {
                val context = buildJsonObject {
                    put("event", event); put("appId", "llm_assistant")
                    put("configuredSecrets", buildJsonArray { add("customKey") })
                    put("settings", buildJsonObject {
                        put("defaultProvider", "custom")
                        put("customUrl", "https://my-new-relay.example/v1")
                        put("customModel", "my-model")
                    })
                    put("values", buildJsonObject { put("input", input); put("provider", "custom"); put("purpose", "polish") })
                }
                val outcome = JsVoiceInvoker.call({ js.evaluate<String>(it) }, "app", context.toString(), 5000)
                assertNull(outcome.error)
                return outcome.result!!.jsonObject
            }
            assertTrue(invoke("open").containsKey("layout"))
            assertEquals(listOf("getSelection" to ""), editor)
            invoke("send")
            assertEquals("https://my-new-relay.example/v1/chat/completions", request!!.text("url"))
            assertEquals("customKey", request!!.text("credential"))
            assertEquals("setComposition" to "Hello", editor.last())
            assertFalse(editor.any { it.first == "commit" })
            assertTrue(invoke("confirm")["close"]!!.jsonPrimitive.boolean)
            assertEquals("commit" to "Hello", editor.last())
            invoke("open")
            invoke("send")
            invoke("close")
            assertEquals("clearComposition", editor.last().first)
        } finally { js.close() }
    }

    @Test fun undeclaredOrInvalidAppPermissionsAreRejected() = runBlocking {
        val result = JsVoiceImporter.inspect("""
            foxy.plugin.register({id:'bad', hosts:[], features:[]});
            foxy.app.register({id:'bad_app', permissions:['editor.all'], onOpen:function(){}});
        """, "en", emptySet())
        assertTrue(result is JsVoiceImportResult.Failure)
    }
}
