/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.voice

import android.content.Context
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi
import org.fcitx.fcitx5.android.input.voice.js.JsVoicePluginMeta
import org.fcitx.fcitx5.android.input.voice.js.JsVoicePluginRecord
import org.fcitx.fcitx5.android.input.voice.js.JsVoicePluginStore
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceProviders
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceSettingsStore
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class PreferredVoiceProvidersRefreshTest {
    private class HostActivity : SettingsUiActivity()
    private lateinit var controller: ActivityController<HostActivity>
    private val meta = JsVoicePluginMeta.parse("""{
        "id":"custom-asr","name":"Imported ASR","version":"1.0",
        "hosts":["asr.example.com"],
        "features":[{"type":"voice.asr","id":"custom-asr","sampleRate":16000}]
    }""")!!

    @Before fun setUp() {
        RuntimeEnvironment.getApplication().getSharedPreferences("js_voice_imported_plugins", Context.MODE_PRIVATE)
            .edit().clear().commit()
        JsVoiceProviders.reload()
        controller = Robolectric.buildActivity(HostActivity::class.java).setup()
    }

    @After fun tearDown() {
        JsVoicePluginStore.delete(meta.id)
        JsVoicePluginStore.delete("tencent-asr")
        JsVoiceProviders.reload()
        controller.pause().stop().destroy()
    }

    @Test fun existingPreferenceShowsImportedAndRemovedPluginsAfterRefreshWithoutLosingSelection() {
        val preference = ManagedPreferenceUi.VoiceInputList(R.string.preferred_voice_input, "preferred_voice_input", "")
            .createUi(controller.get())
        preference.value = JsVoiceProviders.QWEN_ASR_ID
        assertFalse(preference.entryValues.any { it == meta.providerId })

        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(meta, "custom-asr.js", 1), "// test plugin"))
        JsVoiceProviders.reload()
        assertTrue(JsVoiceProviders.all().any { it.id == meta.providerId })
        // This is the stale snapshot the user saw on returning from the plugin manager.
        assertFalse(preference.entryValues.any { it == meta.providerId })
        ManagedPreferenceUi.VoiceInputList.refreshEntries(preference)
        val index = preference.entryValues.indexOfFirst { it == meta.providerId }
        assertTrue(index >= 0)
        assertEquals(meta.displayName, preference.entries[index].toString())
        assertEquals(JsVoiceProviders.QWEN_ASR_ID, preference.value)

        JsVoicePluginStore.delete(meta.id)
        JsVoiceProviders.reload()
        ManagedPreferenceUi.VoiceInputList.refreshEntries(preference)
        assertFalse(preference.entryValues.any { it == meta.providerId })
        assertEquals(JsVoiceProviders.QWEN_ASR_ID, preference.value)
    }

    @Test fun promotingTencentToBuiltInKeepsExistingSettingsAndProducesOneEntry() {
        val tencent = meta.copy(
            id = "tencent-asr", name = "Old imported Tencent", version = "1.0.0",
            hosts = listOf("asr.cloud.tencent.com"),
        )
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(tencent, "tencent-asr.js", 1), "// old imported script"))
        JsVoiceSettingsStore.setValue(tencent.providerId, "appId", "1234567890")
        JsVoiceProviders.reload()
        val specs = JsVoiceProviders.all().filter { it.id == tencent.providerId }
        assertEquals(1, specs.size)
        assertTrue(specs.single().builtIn)
        assertTrue(specs.single().source is JsVoiceProviders.Source.Asset)
        assertEquals("1.0.7", specs.single().version)
        assertEquals("1234567890", JsVoiceSettingsStore.value(tencent.providerId, "appId"))
        assertEquals("// old imported script", JsVoicePluginStore.readScript(tencent.id))
        val preference = ManagedPreferenceUi.VoiceInputList(R.string.preferred_voice_input, "preferred_voice_input", "")
            .createUi(controller.get())
        assertEquals(1, preference.entryValues.count { it == tencent.providerId })
    }
}
