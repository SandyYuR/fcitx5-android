/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 导入识别的分支覆盖：**故意写坏**的样例脚本 + 真实的内置脚本。
 *
 * 用真实 QuickJS 跑而不是 mock：导入的价值就在于「脚本真的能在这个宿主里跑起来」，
 * 换成 mock 之后测的是 mock 的假设。
 */
class JsVoiceImporterTest {

    private val scriptDir = File("src/main/assets/bundled/js-voice")

    private fun inspect(script: String, builtInIds: Set<String> = setOf("js:qwen-asr", "js:doubao-asr", "js:tencent-asr"), imported: Set<String> = emptySet()) =
        runBlocking { JsVoiceImporter.inspect(script, "zh-CN", builtInIds, imported) }

    private val validScript = """
        foxy.plugin.register({
          id: "demo-asr",
          name: "Demo ASR",
          version: "1.0",
          hosts: ["example.com"],
          features: [{ type: "voice.asr", id: "asr", label: "ASR", sampleRate: 16000 }],
          configure: function (ui, locale) {
            ui.text("endpoint", "Endpoint", "wss://example.com/asr");
            ui.secret("apiKey", "API key");
          },
          start: function (ctx) { return "handle"; },
          audio: function (ctx) {},
          stop: function (ctx) {}
        });
    """.trimIndent()

    @Test(timeout = 10000)
    fun anUnregisteredTopLevelPromiseDoesNotHangInspection() {
        // quickjs-kt returns this Promise as a value; it still has no registered plugin.
        assertEquals(JsVoiceImportResult.Failure.Reason.NO_PLUGIN,
            failureReason(inspect("new Promise(function () {});")))
    }

    @Test(timeout = 10000)
    fun rejectsAllocationBeyondTheEngineHeapBudget() {
        assertEquals(JsVoiceImportResult.Failure.Reason.LOAD_FAILED,
            failureReason(inspect("globalThis.buffer = new ArrayBuffer(64 * 1024 * 1024);")))
    }

    @Test(timeout = 10000)
    fun rejectsAnInfiniteTopLevelLoop() {
        assertEquals(JsVoiceImportResult.Failure.Reason.LOAD_FAILED, failureReason(inspect("while (true) {}")))
    }

    @Test
    fun acceptsWellFormedPlugin() {
        val result = inspect(validScript)
        assertTrue(result is JsVoiceImportResult.Success)
        result as JsVoiceImportResult.Success
        assertEquals("js:demo-asr", result.meta.providerId)
        assertEquals("Demo ASR", result.meta.displayName)
        assertEquals(listOf("example.com"), result.meta.hosts)
        assertEquals(16000, result.meta.sampleRate)
        assertEquals(2, result.fieldCount)
        assertTrue(!result.replacesExisting)
    }

    @Test
    fun flagsReplacementWhenSameIdAlreadyImported() {
        val result = inspect(validScript, imported = setOf("demo-asr"))
        assertTrue((result as JsVoiceImportResult.Success).replacesExisting)
    }

    @Test
    fun rejectsScriptWithoutRegistration() {
        val result = inspect("var x = 1;")
        assertEquals(JsVoiceImportResult.Failure.Reason.NO_PLUGIN, failureReason(result))
    }

    @Test
    fun rejectsScriptThatIsNotVoiceAsr() {
        val result = inspect(
            """
            foxy.plugin.register({
              id: "demo-text",
              hosts: ["example.com"],
              features: [{ type: "text.transform", id: "x", label: "X" }],
              configure: function (ui) {}
            });
            """.trimIndent()
        )
        assertEquals(JsVoiceImportResult.Failure.Reason.NO_VOICE_FEATURE, failureReason(result))
    }

    @Test
    fun rejectsSyntaxError() {
        val result = inspect("foxy.plugin.register({ id: ")
        assertEquals(JsVoiceImportResult.Failure.Reason.LOAD_FAILED, failureReason(result))
    }

    @Test
    fun rejectsOversizedScriptBeforeEvaluating() {
        val oversized = "// " + "x".repeat(JsVoicePluginMeta.MAX_SCRIPT_BYTES + 16)
        val result = inspect(oversized)
        assertEquals(JsVoiceImportResult.Failure.Reason.TOO_LARGE, failureReason(result))
    }

    @Test
    fun rejectsIdReservedByBuiltIn() {
        val result = inspect(validScript.replace("demo-asr", "qwen-asr"))
        assertEquals(JsVoiceImportResult.Failure.Reason.RESERVED_ID, failureReason(result))
    }

    @Test
    fun rejectsMalformedId() {
        val result = inspect(validScript.replace("demo-asr", "demo asr"))
        assertEquals(JsVoiceImportResult.Failure.Reason.INVALID_ID, failureReason(result))
    }

    @Test
    fun rejectsScriptWithoutHosts() {
        val result = inspect(validScript.replace("hosts: [\"example.com\"],", "hosts: [],"))
        assertEquals(JsVoiceImportResult.Failure.Reason.NO_HOSTS, failureReason(result))
    }

    @Test
    fun builtInQwenScriptIsImportable() {
        val script = File(scriptDir, "qwen-asr.js").readText()
        val result = inspect(script, builtInIds = emptySet())
        assertTrue(result is JsVoiceImportResult.Success)
        result as JsVoiceImportResult.Success
        assertEquals("js:qwen-asr", result.meta.providerId)
        assertEquals(3, result.meta.hosts.size)
        assertTrue(result.fieldCount > 0)
    }

    @Test
    fun builtInDoubaoScriptIsImportable() {
        val script = File(scriptDir, "doubao-asr.js").readText()
        val result = inspect(script, builtInIds = emptySet())
        assertTrue(result is JsVoiceImportResult.Success)
        result as JsVoiceImportResult.Success
        assertEquals("js:doubao-asr", result.meta.providerId)
        assertEquals(listOf("openspeech.bytedance.com"), result.meta.hosts)
    }

    @Test
    fun builtInTencentScriptIsDescribedWithoutAttemptingToSignOrConnect() {
        val script = File(scriptDir, "tencent-asr.js").readText()
        val result = inspect(script, builtInIds = emptySet())
        assertTrue(result is JsVoiceImportResult.Success)
        result as JsVoiceImportResult.Success
        assertEquals("js:tencent-asr", result.meta.providerId)
        assertEquals(listOf("asr.cloud.tencent.com"), result.meta.hosts)
        assertEquals(12, result.fieldCount)
        assertEquals(JsVoiceImportResult.Failure.Reason.RESERVED_ID, failureReason(inspect(script)))
    }

    private fun failureReason(result: JsVoiceImportResult): JsVoiceImportResult.Failure.Reason {
        assertTrue("expected failure, got " + result, result is JsVoiceImportResult.Failure)
        return (result as JsVoiceImportResult.Failure).reason
    }
}
