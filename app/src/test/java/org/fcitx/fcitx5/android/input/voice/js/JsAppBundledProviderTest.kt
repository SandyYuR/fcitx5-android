/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.voice.VoiceInputProviderRegistry
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsAppBundledProviderTest {
    private val assistantId = "js:llm-assistant"
    private val customScript = """
        foxy.plugin.register({id:'llm-assistant',name:'My assistant',version:'0.1',hosts:[],features:[]});
        foxy.app.register({id:'llm_assistant',permissions:[],onOpen:function(){return {close:true};}});
    """.trimIndent()

    @Test fun bundledTranslationAppHasNoVoiceProviderAndUsesItsOwnHost() {
        val spec = JsVoiceProviders.builtInSpecOf("js:youdao-free-translate")!!
        assertTrue(spec.builtIn)
        assertFalse(spec.hasVoice)
        assertEquals("1.0.0", spec.version)
        assertEquals(setOf("aidemo.youdao.com"), spec.hosts)
        assertEquals("youdao_translate", spec.apps.single().id)
        assertEquals("youdao-free-translate.js", spec.originName)
        assertEquals(
            "bundled/js-apps/youdao-free-translate.js",
            (spec.source as JsVoiceProviders.Source.Asset).path,
        )
        assertTrue(spec.id in JsVoiceProviders.builtInIds())
    }

    @Test fun oldImportedAssistantCannotShadowTheFixedBundledVersionOrEraseItsSettings() = runBlocking {
        val script = """
            foxy.plugin.register({id:'llm-assistant',version:'1.1',hosts:[],features:[]});
            foxy.app.register({id:'llm_assistant',permissions:[],onOpen:function(){return {close:true};}});
        """.trimIndent()
        val imported = JsVoiceImporter.inspect(script, "zh-CN", emptySet()) as JsVoiceImportResult.Success
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(imported.meta, "old.js", 1), script))
        JsVoiceSettingsStore.setValue("js:llm-assistant", "customUrl", "https://saved.example/v1")
        try {
            JsVoiceProviders.reload()
            val spec = JsVoiceProviders.specOf("js:llm-assistant")!!
            assertTrue(spec.builtIn)
            assertEquals("1.23.3-fx", spec.version)
            assertTrue(JsVoiceProviders.readScript(spec).contains("lastResultRequest"))
            assertEquals("https://saved.example/v1", JsVoiceSettingsStore.value(spec.id, "customUrl"))
            assertEquals(script, JsVoicePluginStore.readScript("llm-assistant"))
            assertTrue(spec.id in JsVoiceProviders.builtInIds())
            assertEquals(JsVoiceImportResult.Failure.Reason.RESERVED_ID,
                JsVoiceImportPolicy.validate(imported.meta, JsVoiceProviders.builtInIds()))
        } finally { JsVoicePluginStore.delete("llm-assistant"); JsVoiceProviders.reload() }
    }

    @Test fun explicitReplacementRunsEvenAtLowerVersionAndRestoreRetainsSettingsAndSource() = runBlocking {
        val original = JsVoiceProviders.builtInSpecOf(assistantId)!!
        val imported = JsVoiceImporter.inspect(customScript, "zh-CN", JsVoiceProviders.builtInIds(),
            targetPluginId = assistantId) as JsVoiceImportResult.Success
        val record = JsVoicePluginRecord(imported.meta, "my-assistant.js", 1, overridesBuiltIn = true)
        val settings = appContext.getSharedPreferences("js_voice_plugins", 0)
        val secretKey = JsVoiceSettingsStore.secretKey(assistantId, "apiKey")
        settings.edit().putString(secretKey, "fixture-encrypted-secret").commit()
        JsVoiceSettingsStore.setValue(assistantId, "customUrl", "https://saved.example/v1")
        JsVoiceHostPermissions.approve(assistantId, setOf("saved.example"))
        JsAppPermissions.grant(assistantId, "llm_assistant", setOf("network.request"))
        try {
            assertTrue(JsVoicePluginStore.save(record, customScript))
            JsVoiceProviders.reload()
            val spec = JsVoiceProviders.specOf(assistantId)!!
            assertFalse(spec.builtIn)
            assertTrue(spec.overridesBuiltIn)
            assertEquals("0.1", spec.version)
            assertEquals(customScript, JsVoiceProviders.readScript(spec))
            assertEquals(original.version, JsVoiceProviders.builtInSpecOf(assistantId)!!.version)
            assertTrue(JsVoiceHostPermissions.approvedHosts(assistantId).isEmpty())
            assertTrue(JsAppPermissions.granted(assistantId, "llm_assistant").isEmpty())
            assertEquals("https://saved.example/v1", JsVoiceSettingsStore.value(assistantId, "customUrl"))
            assertEquals("fixture-encrypted-secret", settings.getString(secretKey, null))

            // Same body can be reimported on Windows. A regular upgrade record keeps the explicit choice.
            assertTrue(JsVoicePluginStore.save(record.copy(overridesBuiltIn = false), customScript))
            assertTrue(JsVoicePluginStore.recordOf("llm-assistant")!!.overridesBuiltIn)
            JsVoiceHostPermissions.approve(assistantId, setOf("saved.example"))
            JsAppPermissions.grant(assistantId, "llm_assistant", setOf("network.request"))
            assertTrue(JsVoicePluginStore.restoreBuiltIn("llm-assistant"))
            JsVoiceProviders.reload()
            assertTrue(JsVoiceProviders.specOf(assistantId)!!.builtIn)
            assertFalse(JsVoicePluginStore.recordOf("llm-assistant")!!.overridesBuiltIn)
            assertEquals(customScript, JsVoicePluginStore.readScript("llm-assistant"))
            assertEquals("https://saved.example/v1", JsVoiceSettingsStore.value(assistantId, "customUrl"))
            assertEquals("fixture-encrypted-secret", settings.getString(secretKey, null))
            assertTrue(JsVoiceHostPermissions.approvedHosts(assistantId).isEmpty())
            assertTrue(JsAppPermissions.granted(assistantId, "llm_assistant").isEmpty())
        } finally { JsVoicePluginStore.delete("llm-assistant"); JsVoiceProviders.reload() }
    }

    @Test fun laterReplacementMayRemoveAppsAddedOnlyByAnEarlierCustomSource() = runBlocking {
        val customWithExtra = customScript +
            "\nfoxy.app.register({id:'custom_extra',permissions:[],onOpen:function(){return {close:true};}});"
        val first = JsVoiceImporter.inspect(
            customWithExtra, "en", JsVoiceProviders.builtInIds(), targetPluginId = assistantId,
        ) as JsVoiceImportResult.Success
        assertTrue(JsVoicePluginStore.save(
            JsVoicePluginRecord(first.meta, "custom-v1.js", 1, overridesBuiltIn = true),
            customWithExtra,
        ))
        JsVoiceProviders.reload()
        try {
            assertEquals(2, JsVoiceProviders.specOf(assistantId)!!.apps.size)
            val second = JsVoiceImporter.inspect(
                customScript, "en", JsVoiceProviders.builtInIds(), targetPluginId = assistantId,
            )
            assertTrue(second is JsVoiceImportResult.Success)
        } finally { JsVoicePluginStore.delete("llm-assistant"); JsVoiceProviders.reload() }
    }

    @Test fun restoringAnAppOnlyBundledSourceClearsAJsVoiceSelectionAddedByItsOverride() = runBlocking {
        val hybrid = customScript.replace(
            "features:[]",
            "features:[{type:'voice.asr',id:'recognizer'}]",
        ).replace("hosts:[]", "hosts:['example.com']")
        val imported = JsVoiceImporter.inspect(
            hybrid, "en", JsVoiceProviders.builtInIds(), targetPluginId = assistantId,
        ) as JsVoiceImportResult.Success
        val preference = AppPrefs.getInstance().keyboard.preferredVoiceInput
        try {
            assertTrue(JsVoicePluginStore.save(
                JsVoicePluginRecord(imported.meta, "hybrid.js", 1, overridesBuiltIn = true),
                hybrid,
            ))
            JsVoiceProviders.reload()
            assertNotNull(VoiceInputProviderRegistry.get(assistantId))
            preference.setValue(assistantId)

            assertTrue(JsVoicePluginStore.restoreBuiltIn("llm-assistant"))
            JsVoiceProviders.reload()
            assertNull(VoiceInputProviderRegistry.get(assistantId))
            assertEquals("", preference.getValue())
        } finally {
            preference.setValue("")
            JsVoicePluginStore.delete("llm-assistant")
            JsVoiceProviders.reload()
        }
    }

    @Test fun restoringABundledVoiceSourceKeepsItsValidJsVoiceSelection() = runBlocking {
        val pluginId = JsVoiceProviders.QWEN_ASR_ID
        val rawId = pluginId.removePrefix("js:")
        val customVoice = """
            foxy.plugin.register({id:'$rawId',name:'Custom voice',version:'0.1',
              hosts:['example.com'],features:[{type:'voice.asr',id:'recognizer'}],
              start:function(){return 'session';},audio:function(){},stop:function(){}});
        """.trimIndent()
        val imported = JsVoiceImporter.inspect(
            customVoice, "en", JsVoiceProviders.builtInIds(), targetPluginId = pluginId,
        ) as JsVoiceImportResult.Success
        val preference = AppPrefs.getInstance().keyboard.preferredVoiceInput
        try {
            assertTrue(JsVoicePluginStore.save(
                JsVoicePluginRecord(imported.meta, "custom-voice.js", 1, overridesBuiltIn = true),
                customVoice,
            ))
            JsVoiceProviders.reload()
            preference.setValue(pluginId)

            assertTrue(JsVoicePluginStore.restoreBuiltIn(rawId))
            JsVoiceProviders.reload()
            assertNotNull(VoiceInputProviderRegistry.get(pluginId))
            assertEquals(pluginId, preference.getValue())
        } finally {
            preference.setValue("")
            JsVoicePluginStore.delete(rawId)
            JsVoiceProviders.reload()
        }
    }
}
