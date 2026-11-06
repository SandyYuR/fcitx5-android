/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.dokar.quickjs.binding.function
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Real buttons -> serialized JS callback -> mock HTTP -> real app/editor input connections. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsAppWorkflowTest {
    private inner class Harness : AutoCloseable {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val applicationView = EditText(service).apply {
            setText("原有内容"); setSelection(0, 4)
        }
        val application = requireNotNull(applicationView.onCreateInputConnection(EditorInfo()))
        var closed = false
        val appEditor = JsAppEditor(application, { !closed }, {})
        val events = ArrayDeque<Pair<String, JsonObject>>()
        val requests = mutableListOf<JsonObject>()
        val replies = ArrayDeque<Pair<Int, String>>()
        val js = JsVoiceRuntime.create()
        val dialog = JsAppDialog(service, { id, values -> events.add(id to values) }, {
            appEditor.clearPreview(); closed = true; service.jsAppInputConnection = null
        })
        var lastValues = buildJsonObject { put("input", ""); put("provider", "custom"); put("purpose", "polish") }

        suspend fun initialize() {
            JsVoiceStubNatives.install(js, "zh-CN")
            js.function("__n_appEditor") { args -> appEditor.call(args[0].toString(), args[1].toString()) }
            js.function("__n_appHttp") { args ->
                requests.add(Json.parseToJsonElement(args[0].toString()).jsonObject)
                check(replies.isNotEmpty()) { "Unexpected network request" }
                val (code, result) = replies.removeFirst()
                val body = if (code == 200) buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("message", buildJsonObject { put("content", result) })
                    }) })
                }.toString() else result
                buildJsonObject { put("ok", code == 200); put("status", code); put("body", body) }.toString()
            }
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE + "\n" +
                File("src/main/assets/bundled/js-apps/llm-assistant.js").readText())
            invoke("open")
            dialog.show()
        }
        suspend fun invoke(event: String, values: JsonObject = lastValues) {
            lastValues = values
            dialog.setBusy(true)
            try {
                val context = buildJsonObject {
                    put("appId", "llm_assistant"); put("event", event); put("values", values)
                    put("settings", buildJsonObject {
                        put("defaultProvider", "custom"); put("customProtocol", "chat")
                        put("customUrl", "https://relay.example/v1"); put("customModel", "model-x")
                        put("defaultPurpose", "polish")
                    })
                    put("configuredSecrets", buildJsonArray { add("customKey") })
                }
                val outcome = JsVoiceInvoker.call({ js.evaluate<String>(it) }, "app", context.toString(), 5000)
                assertNull(outcome.error)
                val result = outcome.result!!.jsonObject
                if (result["close"]?.jsonPrimitive?.booleanOrNull == true) dialog.dismiss()
                else dialog.render(result)
            } finally { if (!closed) dialog.setBusy(false) }
        }
        suspend fun click(label: String) {
            button(label).performClick()
            val (event, values) = events.removeFirst()
            invoke(event, values)
        }
        fun button(label: String) = views().filterIsInstance<MaterialButton>().single { it.text.toString() == label }
        fun input() = views().filterIsInstance<EditText>().single { it.hint?.toString() == "输入要处理的内容" }
        fun reply() = views().filterIsInstance<EditText>().single { it.keyListener == null }
        fun views() = descendants(dialog.findViewById(android.R.id.content))
        override fun close() { dialog.dismiss(); js.close() }
    }

    @Test fun typingSendingReadingAndInsertingIsOneContinuousWorkflow() = runBlocking {
        Harness().use { h ->
            h.initialize()
            assertFalse(h.views().filterIsInstance<MaterialButton>().any { it.text == "确认上屏" })
            h.input().selectAll(); h.service.commitText("请帮我润色")
            assertEquals("原有内容", h.applicationView.text.toString())
            h.replies.add(200 to "这是 AI 处理后的回复")
            h.click("发送 AI")
            assertFalse(h.closed)
            assertEquals(1, h.requests.size)
            assertTrue(h.requests.single().text("body").contains("请帮我润色"))
            assertEquals("请帮我润色", h.input().text.toString())
            assertEquals("这是 AI 处理后的回复", h.reply().text.toString())
            assertEquals("这是 AI 处理后的回复", h.applicationView.text.toString())
            h.click("确认上屏")
            assertTrue(h.closed)
            assertEquals("这是 AI 处理后的回复", h.applicationView.text.toString())
            assertEquals(1, h.requests.size)
            assertNull(h.service.jsAppInputConnection)
        }
    }

    @Test fun legacyConfirmGeneratesAndReplyCannotBeAccidentallyEditedWithTheKeyboard() = runBlocking {
        Harness().use { h ->
            h.initialize(); h.replies.add(200 to "AI 回复")
            h.invoke("confirm", h.dialog.values())
            assertFalse(h.closed)
            assertEquals("AI 回复", h.reply().text.toString())
            assertEquals(1, h.requests.size)
            h.reply().requestFocus(); h.service.commitText("不应写入")
            assertEquals("AI 回复", h.reply().text.toString())
            assertEquals("原有内容", h.input().text.toString())
            h.input().requestFocus(); h.service.commitText("追加")
            assertEquals("原有内容追加", h.input().text.toString())
        }
    }

    @Test fun failureKeepsTheDraftAndNoInsertButtonIsShown() = runBlocking {
        Harness().use { h ->
            h.initialize(); h.replies.add(401 to """{"error":{"message":"invalid API key"}}""")
            h.click("发送 AI")
            assertFalse(h.closed)
            assertEquals("原有内容", h.input().text.toString())
            assertEquals("原有内容", h.applicationView.text.toString())
            assertFalse(h.views().filterIsInstance<MaterialButton>().any { it.text == "确认上屏" })
            assertTrue(h.views().filterIsInstance<EditText>().all { it.keyListener != null })
            assertNotNull(h.button("发送 AI"))
        }
    }

    @Test fun closeAfterAReplyRestoresTheOriginalSelection() = runBlocking {
        Harness().use { h ->
            h.initialize(); h.replies.add(200 to "临时回复"); h.click("发送 AI")
            h.click("关闭")
            assertTrue(h.closed)
            assertEquals("原有内容", h.applicationView.text.toString())
            assertEquals(0, android.text.Selection.getSelectionStart(h.applicationView.text))
            assertEquals(4, android.text.Selection.getSelectionEnd(h.applicationView.text))
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()
}
