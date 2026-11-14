/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.preference.Preference
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.js.*
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.ui.main.settings.voice.JsVoicePluginSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.voice.JsVoicePluginsActivity
import org.fcitx.fcitx5.android.ui.main.settings.voice.JsVoicePluginsFragment
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class JsPluginManagementUiTest {
    private val controller = Robolectric.buildActivity(UiPolishHostActivity::class.java).setup()
    private val activity = controller.get()
    private val assistantId = "js:llm-assistant"

    @After fun close() {
        JsVoicePluginStore.delete("llm-assistant")
        JsVoiceProviders.reload()
        controller.pause().stop().destroy()
    }

    private fun waitUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done() && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("UI operation did not complete", done())
    }

    private fun showSettings(): JsVoicePluginSettingsFragment {
        val fragment = JsVoicePluginSettingsFragment.newInstance(assistantId)
        activity.supportFragmentManager.beginTransaction().replace(R.id.settings_container, fragment).commitNow()
        waitUntil { fragment.preferenceScreen.findPreference<Preference>("js_plugin_export") != null }
        return fragment
    }

    @Test fun bundledSourceExportsThroughDocumentPickerWithoutStoredSettings() {
        val fragment = showSettings()
        val source = JsVoiceProviders.readScript(JsVoiceProviders.specOf(assistantId)!!)
        JsVoiceSettingsStore.setValue(assistantId, "customUrl", "https://private-user-endpoint.invalid/v1")
        fragment.findPreference<Preference>("js_plugin_export")!!.performClick()
        val launched = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, launched.action)
        assertEquals("text/javascript", launched.type)
        assertEquals("llm-assistant.js", launched.getStringExtra(Intent.EXTRA_TITLE))
        val destination = Uri.parse("content://test/exported-assistant.js")
        val output = ByteArrayOutputStream()
        shadowOf(activity.contentResolver).registerOutputStream(destination, output)
        shadowOf(activity).receiveResult(launched, Activity.RESULT_OK, Intent().setData(destination))
        waitUntil { output.size() > 0 }
        assertEquals(source, output.toString("UTF-8"))
        assertFalse(output.toString("UTF-8").contains("private-user-endpoint.invalid"))
        assertNull(fragment.findPreference<Preference>("js_plugin_restore"))
    }

    @Test fun replaceFromDetailsCarriesExactTargetAndRestoredCustomSourceRemainsStored() = runBlocking {
        val original = JsVoiceProviders.readScript(JsVoiceProviders.specOf(assistantId)!!)
        val custom = original + "\n// user customization"
        val inspected = JsVoiceImporter.inspect(custom, "zh-CN", JsVoiceProviders.builtInIds(),
            targetPluginId = assistantId) as JsVoiceImportResult.Success
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(inspected.meta, "custom.js", 1,
            overridesBuiltIn = true), custom))
        JsVoiceSettingsStore.setValue(assistantId, "customModel", "kept-model")
        JsVoiceProviders.reload()
        val fragment = showSettings()
        assertEquals(activity.getString(R.string.js_plugin_source_custom),
            fragment.findPreference<Preference>("js_plugin_source")!!.title)
        assertNotNull(fragment.findPreference<Preference>("js_plugin_restore"))
        assertNull(fragment.findPreference<Preference>("js_voice_remove_plugin"))
        fragment.findPreference<Preference>("js_plugin_replace")!!.performClick()
        val launched = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(JsVoicePluginsActivity::class.java.name, launched.component!!.className)
        assertEquals(assistantId, launched.getStringExtra(JsVoicePluginsActivity.EXTRA_TARGET_PLUGIN_ID))
        assertTrue(JsVoicePluginStore.restoreBuiltIn("llm-assistant"))
        JsVoiceProviders.reload()
        assertEquals(original, JsVoiceProviders.readScript(JsVoiceProviders.specOf(assistantId)!!))
        assertEquals(custom, JsVoicePluginStore.readScript("llm-assistant"))
        assertEquals("kept-model", JsVoiceSettingsStore.value(assistantId, "customModel"))
    }

    @Test fun pluginListExposesCurrentAssistantSeparatelyFromPluginSettings() {
        val fragment = JsVoicePluginsFragment()
        activity.supportFragmentManager.beginTransaction().replace(R.id.settings_container, fragment).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        val selector = fragment.findPreference<Preference>("js_app_preferred")!!
        assertTrue(selector.isEnabled)
        assertTrue(selector.summary.toString().contains(assistantId))
        assertNotNull(fragment.findPreference<Preference>("js_voice_entry_$assistantId"))
    }
}
