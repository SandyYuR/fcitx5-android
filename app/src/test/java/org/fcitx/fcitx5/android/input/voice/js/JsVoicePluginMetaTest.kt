/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件自述信息的纯判定：归一化、id 形状、解析、记录往返。
 *
 * 这些规则决定「一个外来脚本能不能进来」，所以每一条都用正反例钉住，而不是只测顺利路径。
 */
class JsVoicePluginMetaTest {

    @Test
    fun normalizeHostAcceptsPlainHostsAndIps() {
        assertEquals("dashscope.aliyuncs.com", JsVoicePluginMeta.normalizeHost(" DashScope.Aliyuncs.COM "))
        assertEquals("example.com", JsVoicePluginMeta.normalizeHost("example.com."))
        assertEquals("192.168.1.10", JsVoicePluginMeta.normalizeHost("192.168.1.10"))
        assertEquals("localhost", JsVoicePluginMeta.normalizeHost("localhost"))
    }

    @Test
    fun normalizeHostRejectsSchemePortAndWildcards() {
        // 带协议/端口/通配符的声明一律拒绝：运行期 isAllowedUrl 只比对 host，放进来会变成
        // 「白名单里写着 https://a.com 但实际放行 a.com」这种看不见的宽松。
        assertNull(JsVoicePluginMeta.normalizeHost("https://example.com"))
        assertNull(JsVoicePluginMeta.normalizeHost("example.com:443"))
        assertNull(JsVoicePluginMeta.normalizeHost("*.example.com"))
        assertNull(JsVoicePluginMeta.normalizeHost(""))
        assertNull(JsVoicePluginMeta.normalizeHost("exa mple.com"))
        assertNull(JsVoicePluginMeta.normalizeHost("-example.com"))
    }

    @Test
    fun isValidIdAcceptsSlugAndRejectsPathishIds() {
        assertTrue(JsVoicePluginMeta.isValidId("my-asr"))
        assertTrue(JsVoicePluginMeta.isValidId("my_asr.v2"))
        assertFalse(JsVoicePluginMeta.isValidId(""))
        assertFalse(JsVoicePluginMeta.isValidId("../evil"))
        assertFalse(JsVoicePluginMeta.isValidId("-leading"))
        assertFalse(JsVoicePluginMeta.isValidId("has space"))
        assertFalse(JsVoicePluginMeta.isValidId("a".repeat(65)))
    }

    @Test
    fun parseReadsFeaturesAndSplitsRejectedHosts() {
        val meta = JsVoicePluginMeta.parse(
            """
            {"id":"demo","name":"Demo ASR","version":"2.0",
             "hosts":["Example.com","bad host","*.wild.com"],
             "features":[{"type":"voice.asr","id":"asr","label":"ASR","sampleRate":8000}]}
            """.trimIndent()
        )
        assertNotNull(meta)
        meta!!
        assertEquals("demo", meta.id)
        assertEquals("js:demo", meta.providerId)
        assertEquals("Demo ASR", meta.displayName)
        assertEquals(listOf("example.com"), meta.hosts)
        assertEquals(listOf("bad host", "*.wild.com"), meta.rejectedHosts)
        assertEquals(8000, meta.sampleRate)
    }

    @Test
    fun parseFallsBackToDefaultSampleRateAndName() {
        val meta = JsVoicePluginMeta.parse(
            """{"id":"demo","hosts":["example.com"],"features":[{"type":"voice.asr"}]}"""
        )
        assertEquals(16000, meta!!.sampleRate)
        assertEquals("demo", meta.displayName)
    }

    @Test
    fun parseRejectsNonJson() {
        assertNull(JsVoicePluginMeta.parse("not json at all"))
        assertNull(JsVoicePluginMeta.parse("[]"))
    }

    @Test
    fun recordRoundTripsThroughEncode() {
        val meta = JsVoicePluginMeta.parse(
            """
            {"id":"demo","name":"Demo","version":"1.0","hosts":["example.com"],
             "features":[{"type":"voice.asr","id":"asr","label":"ASR","sampleRate":22050}]}
            """.trimIndent()
        )!!
        val record = JsVoicePluginRecord(meta = meta, sourceName = "demo.js", importedAt = 1234L)
        val decoded = JsVoicePluginRecord.decode(record.encode())
        assertNotNull(decoded)
        assertEquals("demo", decoded!!.meta.id)
        assertEquals("demo.js", decoded.sourceName)
        assertEquals(1234L, decoded.importedAt)
        assertEquals(22050, decoded.meta.sampleRate)
        assertEquals(listOf("example.com"), decoded.meta.hosts)
    }

    @Test
    fun policyChecksShapeThenCapabilityThenHosts() {
        fun meta(id: String, features: String, hosts: String) = JsVoicePluginMeta.parse(
            """{"id":"$id","hosts":$hosts,"features":$features}"""
        )!!

        val voice = """[{"type":"voice.asr"}]"""
        val other = """[{"type":"text.transform"}]"""

        assertEquals(
            JsVoiceImportResult.Failure.Reason.INVALID_ID,
            JsVoiceImportPolicy.validate(meta("bad id", voice, """["example.com"]"""), emptySet()),
        )
        assertEquals(
            JsVoiceImportResult.Failure.Reason.RESERVED_ID,
            JsVoiceImportPolicy.validate(meta("qwen-asr", voice, """["example.com"]"""), setOf("js:qwen-asr")),
        )
        assertEquals(
            JsVoiceImportResult.Failure.Reason.NO_VOICE_FEATURE,
            JsVoiceImportPolicy.validate(meta("demo", other, """["example.com"]"""), emptySet()),
        )
        assertEquals(
            JsVoiceImportResult.Failure.Reason.NO_HOSTS,
            JsVoiceImportPolicy.validate(meta("demo", voice, "[]"), emptySet()),
        )
        assertNull(
            JsVoiceImportPolicy.validate(meta("demo", voice, """["example.com"]"""), emptySet()),
        )
    }
}
