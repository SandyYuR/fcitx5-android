/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.voice

import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceFields
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceSettingsStore
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsVoiceAuthDefaultsTest {
    private class HostActivity : SettingsUiActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = CONTAINER_ID })
        }
    }

    private lateinit var controller: ActivityController<HostActivity>
    private lateinit var fragment: JsVoicePluginSettingsFragment
    private val pluginId = "js:doubao-asr"
    private val seed = "volc.seedasr.sauc.duration"
    private val legacy = "volc.bigasr.sauc.duration"

    @Before fun setUp() {
        RuntimeEnvironment.getApplication().getSharedPreferences("js_voice_plugins", Context.MODE_PRIVATE)
            .edit().clear().commit()
        JsVoiceSettingsStore.setValue(pluginId, "authMode", "legacy")
        JsVoiceSettingsStore.setValue(pluginId, "resourceId", legacy)
        controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        fragment = JsVoicePluginSettingsFragment.newInstance(pluginId)
        // Exercise the real settings listeners without starting a second, asynchronous JS runtime.
        // The declaration produced by the real plugin is covered by JsVoiceBridgeContractTest.
        ReflectionHelpers.setField(fragment, "loaded", true)
        controller.get().supportFragmentManager.beginTransaction().replace(CONTAINER_ID, fragment).commitNow()
        val fields = JsVoiceFields.parse("""[
            {"kind":"select","key":"authMode","default":"apiKey",
             "options":[{"value":"apiKey"},{"value":"legacy"}]},
            {"kind":"select","key":"resourceId","default":"$seed","allowCustom":true,
             "defaultWhen":{"key":"authMode","values":{"apiKey":"$seed","legacy":"$legacy"}}}
        ]""")
        fragment.javaClass.getDeclaredMethod("render", List::class.java).apply { isAccessible = true }
            .invoke(fragment, fields)
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test fun choosingApiKeyUpdatesTheDisplayedAndPersistedResourceAndAllowsManualOverrides() {
        val auth = fragment.findPreference<ListPreference>("authMode")!!
        val resource = fragment.findPreference<EditTextPreference>("resourceId")!!
        assertEquals(legacy, resource.text)
        assertTrue(auth.callChangeListener("apiKey"))
        assertEquals(seed, resource.text)
        assertTrue(resource.summary.toString().contains(seed))
        assertEquals(seed, JsVoiceSettingsStore.value(pluginId, "resourceId"))
        assertEquals("apiKey", JsVoiceSettingsStore.value(pluginId, "authMode"))

        val concurrent = "volc.seedasr.sauc.concurrent"
        assertTrue(resource.callChangeListener(concurrent))
        assertEquals(concurrent, resource.text)
        assertEquals(concurrent, JsVoiceSettingsStore.value(pluginId, "resourceId"))

        assertTrue(auth.callChangeListener("legacy"))
        assertEquals(legacy, resource.text)
        assertEquals(legacy, JsVoiceSettingsStore.value(pluginId, "resourceId"))
        assertTrue(auth.callChangeListener("apiKey"))
        assertEquals(seed, resource.text)
        assertEquals(seed, JsVoiceSettingsStore.value(pluginId, "resourceId"))
    }

    companion object {
        private const val CONTAINER_ID = 0x7f0f0991
    }
}
