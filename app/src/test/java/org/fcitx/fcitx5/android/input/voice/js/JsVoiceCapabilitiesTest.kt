/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import com.dokar.quickjs.binding.function
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 固定住插件用来分叉的能力表，以及它们声明设置时实际摸到的那层界面对象。
 *
 * 为什么要有：插件靠 `foxy.capabilities` 判断「这条路能不能走」。表里报 true 而宿主其实没实现，
 * 插件会走进不存在的分支并抛错；反过来漏报则让插件白白降级。两边都必须由测试钉住。
 */
class JsVoiceCapabilitiesTest {
    private suspend fun capabilities(js: com.dokar.quickjs.QuickJs): JsonObject =
        Json.parseToJsonElement(js.evaluate<String>("JSON.stringify(foxy.capabilities)")).jsonObject

    @Test fun capabilityTableMatchesWhatThisHostActuallyProvides() = runBlocking {
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            val caps = capabilities(js)
            assertTrue(caps["locale"]!!.jsonPrimitive.boolean)
            assertTrue(caps["app"]!!.jsonObject["sleep"]!!.jsonPrimitive.boolean)
            assertTrue(caps["http"]!!.jsonObject["request"]!!.jsonPrimitive.boolean)
            assertTrue(caps["http"]!!.jsonObject["post"]!!.jsonPrimitive.boolean)
            assertTrue(caps["editor"]!!.jsonObject["commit"]!!.jsonPrimitive.boolean)
            // 尚未实现的能力必须报 false，否则插件会走进不存在的分支。
            assertFalse(caps["http"]!!.jsonObject["stream"]!!.jsonPrimitive.boolean)
            assertFalse(caps["http"]!!.jsonObject["cancelStream"]!!.jsonPrimitive.boolean)
            assertTrue(caps["websocket"]!!.jsonObject["authorize"]!!.jsonPrimitive.boolean)
            assertTrue(caps["websocket"]!!.jsonObject["onMessage"]!!.jsonPrimitive.boolean)
            assertTrue(caps["websocket"]!!.jsonObject["onBinary"]!!.jsonPrimitive.boolean)
            assertTrue(caps["websocket"]!!.jsonObject["onError"]!!.jsonPrimitive.boolean)
            assertTrue(caps["websocket"]!!.jsonObject["onClose"]!!.jsonPrimitive.boolean)
            assertTrue(caps["settings"] is JsonNull)
        } finally { js.close() }
    }

    @Test fun capabilityTableNeverExposesHostInternals() = runBlocking {
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            val text = js.evaluate<String>("JSON.stringify(foxy.capabilities)")
            assertFalse(text.contains("__n_"))
            assertFalse(text.contains("wsConnect"))
            assertFalse(text.contains("__native"))
        } finally { js.close() }
    }

    @Test fun settingsStayAbsentWhenTheHostCannotPersist() = runBlocking {
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            // 与 foxy 一致：宿主没有这个能力时属性根本不存在，而不是挂一个必定抛错的函数。
            assertEquals("undefined", js.evaluate<String>("typeof foxy.settings"))
            assertTrue(capabilities(js)["settings"] is JsonNull)
        } finally { js.close() }
    }

    @Test fun settingsForwardStringifiedValuesWhenTheHostCanPersist() = runBlocking {
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            val written = mutableListOf<List<String>>()
            js.function("__n_appSettings") { args ->
                written += args.map { it?.toString().orEmpty() }
                null
            }
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE)
            assertEquals("object", js.evaluate<String>("typeof foxy.settings"))
            assertTrue(capabilities(js)["settings"]!!.jsonObject["set"]!!.jsonPrimitive.boolean)
            js.evaluate<String>("(function () { foxy.settings.set('target', 42); return 'ok'; })()")
            assertEquals(listOf(listOf("target", "42")), written)
        } finally { js.close() }
    }

    @Test fun configureReceivesTheSameUiObjectPluginsCanReachDirectly() = runBlocking {
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            JsVoiceRuntime.initialize(
                js,
                JsVoicePrelude.SOURCE + "\n" + """
                    foxy.plugin.register({id:'ui-probe',version:'1',hosts:[],features:[],
                      configure:function(ui,locale){globalThis.__sameUi = ui === foxy.ui; ui.text('probeKey','Probe','d');}});
                """.trimIndent()
            )
            val fields = js.evaluate<String>("__describe('zh-CN')")
            assertTrue(fields.contains("\"probeKey\""))
            assertTrue(js.evaluate<Boolean>("__sameUi"))
        } finally { js.close() }
    }
}
