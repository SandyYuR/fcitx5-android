/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import com.dokar.quickjs.binding.function
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the bundled translation App through the production QuickJS prelude without Android UI. */
class YoudaoFreeTranslateAppTest {
    private val assetDir = File("src/main/assets/bundled/js-apps")
    private val scriptFile = File(assetDir, "youdao-free-translate.js")
    private val manifestFile = File(assetDir, "youdao-free-translate.json")
    private val script by lazy { scriptFile.readText() }

    private class Harness(
        script: String,
        private val selection: String = "",
        private val rejectPreview: Boolean = false,
    ) : AutoCloseable {
        private val js = JsVoiceRuntime.create()
        val editorCalls = mutableListOf<Pair<String, String>>()
        val httpRequests = mutableListOf<JsonObject>()
        var httpResponse = buildJsonObject {
            put("status", 200)
            put("body", """{"errorCode":"0","translation":["你好"]}""")
        }.toString()

        init {
            JsVoiceStubNatives.install(js, "zh-CN")
            js.function("__n_appEditor") { args ->
                val method = args[0].toString()
                val value = args[1].toString()
                editorCalls += method to value
                if (method == "setComposition" && rejectPreview) error("Editor rejected preview")
                if (method == "getSelection") selection else null
            }
            js.function("__n_appHttp") { args ->
                httpRequests += Json.parseToJsonElement(args[0].toString()).jsonObject
                httpResponse
            }
            runBlocking { JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE + "\n" + script) }
        }

        suspend fun invoke(
            event: String,
            input: String = "",
            target: String = "auto",
            defaultTarget: String = "auto",
        ): JsonObject {
            val context = buildJsonObject {
                put("appId", "youdao_translate")
                put("event", event)
                put("settings", buildJsonObject { put("defaultTarget", defaultTarget) })
                put("values", buildJsonObject {
                    put("input", input)
                    put("target", target)
                })
            }
            val outcome = JsVoiceInvoker.call(
                evaluate = { code -> js.evaluate<String>(code) },
                name = "app",
                argJson = context.toString(),
                timeoutMs = 5_000,
            )
            assertNull(outcome.error)
            return outcome.result!!.jsonObject
        }

        override fun close() {
            js.close()
        }
    }

    @Test
    fun scriptMetadataMatchesBundledManifest() = runBlocking {
        val inspected = JsVoiceImporter.inspect(script, "zh-CN", emptySet()) as JsVoiceImportResult.Success
        val manifest = requireNotNull(JsVoicePluginMeta.parse(manifestFile.readText()))

        assertEquals(manifest, inspected.meta)
        assertEquals("youdao-free-translate", inspected.meta.id)
        assertEquals(listOf("aidemo.youdao.com"), inspected.meta.hosts)
        assertNull(inspected.meta.voiceAsr)
        assertEquals(
            setOf("editor.read_selection", "editor.composition", "editor.commit", "network.request"),
            inspected.meta.apps.single().permissions,
        )
        assertEquals("youdao_translate", inspected.meta.apps.single().id)
        assertEquals(1, inspected.fieldCount)
    }

    @Test
    fun openDoesNotSendTextAndSuccessfulTranslationNeedsExplicitConfirmation() = runBlocking {
        Harness(script, selection = "hello").use { harness ->
            val opened = harness.invoke("open")
            assertTrue(opened.containsKey("layout"))
            assertFalse(opened.toString().contains("翻译方向"))
            assertFalse(opened.toString().contains("仅点击"))
            assertTrue(opened.toString().contains("原文"))
            assertTrue(opened.toString().contains("\"maxWidth\":380"))
            assertTrue(opened.toString().contains("\"maxHeight\":440"))
            assertTrue(harness.httpRequests.isEmpty())
            assertEquals(listOf("getSelection" to ""), harness.editorCalls)

            val translated = harness.invoke("translate", input = "hello", target = "zh-CHS")
            assertEquals(1, harness.httpRequests.size)
            assertEquals(
                "https://aidemo.youdao.com/trans?from=auto&to=zh-CHS&q=hello",
                harness.httpRequests.single().text("url"),
            )
            assertEquals("GET", harness.httpRequests.single().text("method"))
            assertEquals("setComposition" to "你好", harness.editorCalls.last())
            assertFalse(harness.editorCalls.any { it.first == "commit" })
            assertTrue(translated.toString().contains("\"id\":\"input\""))
            assertTrue(translated.toString().contains("\"label\":\"原文\""))
            assertTrue(translated.toString().contains("\"value\":\"hello\""))
            assertTrue(translated.toString().contains("\"label\":\"译文\""))
            assertTrue(translated.toString().contains("\"value\":\"你好\""))
            assertTrue(translated.toString().contains("\"readOnly\":true"))
            assertTrue(translated.toString().contains("\"id\":\"use_result\""))
            assertTrue(translated.toString().contains("\"label\":\"作原文\""))
            assertTrue(translated.toString().contains("\"label\":\"再译\""))
            assertTrue(translated.toString().contains("\"label\":\"替换\""))
            assertFalse(translated.toString().contains("\"icon\":\"send\""))
            assertFalse(translated.toString().contains("\"icon\":\"check\""))
            assertFalse(translated.toString().contains("译文已生成"))

            val confirmed = harness.invoke("confirm", input = "hello", target = "zh-CHS")
            assertTrue(confirmed["close"]!!.jsonPrimitive.boolean)
            assertEquals("commit" to "你好", harness.editorCalls.last())
        }
    }

    @Test
    fun rejectedTemporaryPreviewKeepsTranslationVisibleAndConfirmable() = runBlocking {
        Harness(script, rejectPreview = true).use { harness ->
            val translated = harness.invoke("translate", input = "hello", target = "zh-CHS")

            assertEquals("setComposition" to "你好", harness.editorCalls.last())
            assertTrue(translated.toString().contains("\"label\":\"原文\""))
            assertTrue(translated.toString().contains("\"value\":\"hello\""))
            assertTrue(translated.toString().contains("\"label\":\"译文\""))
            assertTrue(translated.toString().contains("\"value\":\"你好\""))
            assertTrue(translated.toString().contains("当前输入框不支持临时预览"))
            assertTrue(translated.toString().contains("\"id\":\"confirm\""))
            assertFalse(translated.toString().contains("翻译失败"))

            val confirmed = harness.invoke("confirm", input = "hello", target = "zh-CHS")
            assertTrue(confirmed["close"]!!.jsonPrimitive.boolean)
            assertEquals("commit" to "你好", harness.editorCalls.last())
        }
    }

    @Test
    fun originalAndTranslationAreBothVisibleAfterTranslation() = runBlocking {
        Harness(script).use { harness ->
            harness.invoke("open")
            val translated = harness.invoke("translate", input = "hello", target = "zh-CHS")
            assertTrue(translated.toString().contains("\"label\":\"插入\""))
            assertEquals(1, harness.httpRequests.size)
            assertEquals("setComposition" to "你好", harness.editorCalls.last())
            assertFalse(harness.editorCalls.any { it.first == "commit" })
            assertTrue(translated.toString().contains("\"value\":\"hello\""))
            assertTrue(translated.toString().contains("\"value\":\"你好\""))
            assertTrue(translated.toString().contains("\"readOnly\":true"))
            assertTrue(translated.toString().contains("use_result"))
        }
    }

    @Test
    fun everyCloseAliasClearsUnconfirmedPreview() = runBlocking {
        for (event in listOf("close", "cancel", "dismiss")) {
            Harness(script).use { harness ->
                harness.invoke("translate", input = "hello", target = "zh-CHS")
                assertEquals("setComposition" to "你好", harness.editorCalls.last())

                val closed = harness.invoke(event, input = "你好", target = "zh-CHS")
                assertTrue(closed["close"]!!.jsonPrimitive.boolean)
                assertEquals("clearComposition" to "", harness.editorCalls.last())
                assertFalse(harness.editorCalls.any { it.first == "commit" })
            }
        }
    }

    @Test
    fun changingInputOrTargetInvalidatesOldResult() = runBlocking {
        suspend fun verifyChanged(input: String, target: String) {
            Harness(script).use { harness ->
                harness.invoke("translate", input = "hello", target = "zh-CHS")
                val rejected = harness.invoke("confirm", input = input, target = target)
                assertEquals("clearComposition" to "", harness.editorCalls.last())
                assertTrue(rejected.toString().contains("请重新翻译"))
                assertFalse(harness.editorCalls.any { it.first == "commit" })
            }
        }

        verifyChanged(input = "changed", target = "zh-CHS")
        verifyChanged(input = "你好", target = "ja")
    }

    @Test
    fun translatedTextCanBeTranslatedAgainToAnotherLanguage() = runBlocking {
        Harness(script).use { harness ->
            harness.invoke("translate", input = "hello", target = "zh-CHS")
            val reused = harness.invoke("use_result", input = "hello", target = "zh-CHS")
            assertTrue(reused.toString().contains("\"value\":\"你好\""))
            assertFalse(reused.toString().contains("\"id\":\"result\""))
            harness.httpResponse = buildJsonObject {
                put("status", 200)
                put("body", """{"errorCode":"0","translation":["こんにちは"]}""")
            }.toString()

            val second = harness.invoke("translate", input = "你好", target = "ja")

            assertEquals(2, harness.httpRequests.size)
            assertEquals(
                "https://aidemo.youdao.com/trans?from=auto&to=ja&q=%E4%BD%A0%E5%A5%BD",
                harness.httpRequests.last().text("url"),
            )
            assertTrue(second.toString().contains("\"value\":\"こんにちは\""))
            assertEquals("setComposition" to "こんにちは", harness.editorCalls.last())

            val confirmed = harness.invoke("confirm", input = "你好", target = "ja")
            assertTrue(confirmed["close"]!!.jsonPrimitive.boolean)
            assertEquals("commit" to "こんにちは", harness.editorCalls.last())
        }
    }

    @Test
    fun languagePrefixOverridesOnlyTheRequestAndResultStillConfirms() = runBlocking {
        Harness(script).use { harness ->
            harness.httpResponse = buildJsonObject {
                put("status", 200)
                put("body", """{"errorCode":"0","translation":["Hello"]}""")
            }.toString()

            val translated = harness.invoke("translate", input = "英 你好", target = "ja")
            assertEquals(
                "https://aidemo.youdao.com/trans?from=auto&to=en&q=%E4%BD%A0%E5%A5%BD",
                harness.httpRequests.single().text("url"),
            )
            assertTrue(translated.toString().contains("\"value\":\"Hello\""))

            val confirmed = harness.invoke("confirm", input = "英 你好", target = "ja")
            assertTrue(confirmed["close"]!!.jsonPrimitive.boolean)
            assertEquals("commit" to "Hello", harness.editorCalls.last())
        }
    }

    @Test
    fun languagePrefixesRequireADelimiterAndRequestTextIsUrlEncoded() = runBlocking {
        val cases = listOf(
            Triple("英 你好", "en", "%E4%BD%A0%E5%A5%BD"),
            Triple("日：你好", "ja", "%E4%BD%A0%E5%A5%BD"),
            Triple("英语很好", "auto", "%E8%8B%B1%E8%AF%AD%E5%BE%88%E5%A5%BD"),
            Triple("a&b=c?#中文", "auto", "a%26b%3Dc%3F%23%E4%B8%AD%E6%96%87"),
        )
        for ((input, expectedTarget, expectedText) in cases) {
            Harness(script).use { harness ->
                harness.invoke("translate", input = input, target = "auto")
                assertEquals(
                    "https://aidemo.youdao.com/trans?from=auto&to=$expectedTarget&q=$expectedText",
                    harness.httpRequests.single().text("url"),
                )
            }
        }
    }

    @Test
    fun unsupportedDemoTargetsAreNotOffered() = runBlocking {
        Harness(script).use { harness ->
            val opened = harness.invoke("open", defaultTarget = "zh-CHT")
            val rendered = opened.toString()

            assertFalse(rendered.contains("繁体中文"))
            assertFalse(rendered.contains("意大利语"))
            assertTrue(rendered.contains("\"id\":\"target\""))
            assertTrue(rendered.contains("\"value\":\"auto\""))
        }
    }

    @Test
    fun errorResponseStaysInPanelWithoutPreviewOrCommit() = runBlocking {
        Harness(script).use { harness ->
            harness.httpResponse = buildJsonObject {
                put("status", 429)
                put("body", "rate limited")
            }.toString()

            val result = harness.invoke("translate", input = "hello", target = "zh-CHS")
            assertEquals(1, harness.httpRequests.size)
            assertTrue(result.toString().contains("频率限制"))
            assertTrue(result.toString().contains("不表示所选语言不受支持"))
            assertFalse(harness.editorCalls.any { it.first == "setComposition" })
            assertFalse(harness.editorCalls.any { it.first == "commit" })
            assertEquals("clearComposition" to "", harness.editorCalls.last())
        }
    }

    @Test
    fun bothHttpAndBodyRateLimitsHaveTheSameClearExplanation() = runBlocking {
        val responses = listOf(
            buildJsonObject { put("status", 411); put("body", "rate limited") }.toString(),
            buildJsonObject {
                put("status", 200)
                put("body", """{"errorCode":411,"msg":"请求频率过快"}""")
            }.toString(),
        )
        for (response in responses) {
            Harness(script).use { harness ->
                harness.httpResponse = response
                val result = harness.invoke("translate", input = "hello", target = "zh-CHS")

                assertTrue(result.toString().contains("频率限制"))
                assertTrue(result.toString().contains("不表示所选语言不受支持"))
                assertFalse(harness.editorCalls.any { it.first == "setComposition" })
                assertFalse(harness.editorCalls.any { it.first == "commit" })
            }
        }
    }
}
