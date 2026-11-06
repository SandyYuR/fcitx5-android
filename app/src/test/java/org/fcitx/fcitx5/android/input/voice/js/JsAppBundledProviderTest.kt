/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsAppBundledProviderTest {
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
            assertEquals("1.23.2-fx", spec.version)
            assertTrue(JsVoiceProviders.readScript(spec).contains("lastResultRequest"))
            assertEquals("https://saved.example/v1", JsVoiceSettingsStore.value(spec.id, "customUrl"))
            assertEquals(script, JsVoicePluginStore.readScript("llm-assistant"))
            assertTrue(spec.id in JsVoiceProviders.builtInIds())
            assertEquals(JsVoiceImportResult.Failure.Reason.RESERVED_ID,
                JsVoiceImportPolicy.validate(imported.meta, JsVoiceProviders.builtInIds()))
        } finally { JsVoicePluginStore.delete("llm-assistant"); JsVoiceProviders.reload() }
    }
}
