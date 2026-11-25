/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 插件间文字服务：发现、调用隔离与「用户选中才能调」的授权收口。
 *
 * 授权通过后 [JsVoiceServiceBinding.call] 会查一次插件目录（[JsVoiceProviders.specOf]），
 * 那条路径要读 assets 与偏好，所以整个类跑 Robolectric；脚本本身仍按文件读，不依赖打包顺序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class)
class JsVoiceServicesTest {

    private val bilibiliScript = File("src/main/assets/bundled/js-apps/bilibili-translate.js")

    private val echoScript = """
        foxy.plugin.register({id:'svc-echo',name:'Echo',version:'1.0',hosts:[],features:[]});
        foxy.service.register({
          id:'text.translate', version:1, label:'Echo', description:'',
          handle:function(context){ return {translatedText: String(context.input.text).toUpperCase()}; }
        });
    """.trimIndent()

    private val nestedScript = """
        foxy.plugin.register({id:'svc-nested',name:'Nested',version:'1.0',hosts:[],features:[]});
        foxy.service.register({
          id:'text.translate', version:1, label:'Nested', description:'',
          handle:async function(context){
            return await foxy.services.call('other','text.translate',1,{text:context.input.text});
          }
        });
    """.trimIndent()

    @Test
    fun bundledBilibiliScriptDeclaresTheTranslateService() = runBlocking {
        assumeTrue("bundled bilibili script missing at " + bilibiliScript.absolutePath, bilibiliScript.isFile)
        // 1.5 多了 inputModes：宿主不认识这些输入模式时也必须照常发现服务，不能让整张目录塌掉。
        val services = JsVoiceServices.discover(
            script = bilibiliScript.readText(),
            pluginId = "js:bilibili-translate",
            pluginLabel = "哔哩哔哩翻译",
            locale = "zh-CN",
        )
        val translate = services.single { it.serviceId == "text.translate" }
        assertEquals(1, translate.version)
        assertEquals("js:bilibili-translate", translate.pluginId)
        assertTrue(translate.label.isNotBlank())
    }

    @Test
    fun invokeRunsTheDeclaredHandleInItsOwnRuntime() = runBlocking {
        val result = JsVoiceServices.invoke(
            script = echoScript,
            locale = "zh-CN",
            serviceId = "text.translate",
            version = 1,
            input = buildJsonObject { put("text", "hello") },
            settings = emptyMap(),
            catalogJson = "[]",
        )
        val translated = Json.parseToJsonElement(result).jsonObject["translatedText"]!!.jsonPrimitive.contentOrNull
        assertEquals("HELLO", translated)
    }

    @Test
    fun invokeRejectsVersionsThePluginDidNotDeclare() = runBlocking {
        val failure = runCatching {
            JsVoiceServices.invoke(
                script = echoScript,
                locale = "zh-CN",
                serviceId = "text.translate",
                version = 2,
                input = buildJsonObject { put("text", "hello") },
                settings = emptyMap(),
                catalogJson = "[]",
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("not provided"))
    }

    @Test
    fun serviceProviderCannotCallAnotherService() = runBlocking {
        // 只有一层调用预算：服务方再发起服务调用会让「谁在等谁」无法收敛，宿主直接拒绝。
        val failure = runCatching {
            JsVoiceServices.invoke(
                script = nestedScript,
                locale = "zh-CN",
                serviceId = "text.translate",
                version = 1,
                input = buildJsonObject { put("text", "hello") },
                settings = emptyMap(),
                catalogJson = "[]",
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(
            "应报告嵌套调用被拒，实际：" + failure!!.message,
            failure.message.orEmpty().contains("Nested service calls"),
        )
    }

    @Test
    fun singleProviderIsSelectedAutomaticallyButTwoNeedExplicitChoice() = runBlocking {
        val fields = listOf(providerField())
        val one = JsServiceDescriptor("plugin-a", "A", "text.translate", 1, "A", "")
        val two = JsServiceDescriptor("plugin-b", "B", "text.translate", 1, "B", "")

        val single = JsVoiceServiceBinding("caller", "zh-CN", OkHttpClient(), { _, _ -> null })
        single.update(fields, emptyMap(), listOf(one))
        // 自动选中后不再报「没选提供方」，而是走到「插件没装」——说明授权已通过。
        assertTrue(callMessage(single, "plugin-a").contains("not installed"))

        val ambiguous = JsVoiceServiceBinding("caller", "zh-CN", OkHttpClient(), { _, _ -> null })
        ambiguous.update(fields, emptyMap(), listOf(one, two))
        assertTrue(callMessage(ambiguous, "plugin-a").contains("not selected"))

        val chosen = JsVoiceServiceBinding("caller", "zh-CN", OkHttpClient(), { _, _ -> null })
        chosen.update(fields, mapOf("translationProvider" to "plugin-b"), listOf(one, two))
        assertTrue(callMessage(chosen, "plugin-b").contains("not installed"))
        assertTrue(callMessage(chosen, "plugin-a").contains("not selected"))
    }

    @Test
    fun pluginCannotCallItsOwnService() = runBlocking {
        val own = JsServiceDescriptor("caller", "Me", "text.translate", 1, "Me", "")
        val binding = JsVoiceServiceBinding("caller", "zh-CN", OkHttpClient(), { _, _ -> null })
        binding.update(listOf(providerField()), emptyMap(), listOf(own))
        // 自调用在查目录之前就拒绝：调用方的脚本已经占到唯一的运行时，再进来必然死锁。
        assertTrue(callMessage(binding, "caller").contains("own service"))
    }

    private suspend fun callMessage(binding: JsVoiceServiceBinding, provider: String): String {
        val failure = runCatching {
            binding.call(provider, "text.translate", "1", """{"text":"hi"}""")
        }.exceptionOrNull()
        assertTrue("expected a rejection for " + provider, failure != null)
        return failure!!.message.orEmpty()
    }

    private fun providerField() = JsVoiceField(
        kind = JsVoiceField.KIND_SERVICE_SELECT,
        key = "translationProvider",
        label = "provider",
        description = "",
        defaultValue = "",
        serviceId = "text.translate",
        serviceVersion = "1",
    )
}
