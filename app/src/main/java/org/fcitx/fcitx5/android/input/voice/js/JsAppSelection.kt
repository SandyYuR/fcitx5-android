/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/** The AI shortcut and its settings always resolve the same provider/app identity. */
internal object JsAppSelection {
    const val DEFAULT_PLUGIN_ID = "js:llm-assistant"
    const val DEFAULT_APP_ID = "llm_assistant"

    fun available(): List<Pair<JsVoiceProviders.Spec, JsPluginApp>> =
        JsVoiceProviders.all().flatMap { spec -> spec.apps.map { spec to it } }

    @Synchronized
    fun preferred(): Pair<JsVoiceProviders.Spec, JsPluginApp>? {
        val entries = available()
        val preference = AppPrefs.getInstance().internal.preferredJsApp
        val saved = preference.getValue()
        if (saved.isNotEmpty()) {
            entries.firstOrNull { (spec, app) -> key(spec.id, app.id) == saved }?.let { return it }
            // Clear missing targets now so a later import cannot silently resurrect a selection.
            if (!preference.sharedPreferences.edit().remove(preference.key).commit()) {
                // SharedPreferences mutates its in-memory map even when the durable commit fails.
                // Restore the previous value so this process does not claim a clear that disk did
                // not accept; this call still falls back safely and the next reconciliation retries.
                preference.sharedPreferences.edit().putString(preference.key, saved).commit()
            }
        }
        return entries.firstOrNull { (spec, app) ->
            spec.id == DEFAULT_PLUGIN_ID && app.id == DEFAULT_APP_ID
        }
    }

    /** Re-check after imports, source replacement, removal and restore, not only on the next click. */
    fun reconcile(): Pair<JsVoiceProviders.Spec, JsPluginApp>? = preferred()

    @Synchronized
    fun select(pluginId: String, appId: String): Boolean {
        if (available().none { (spec, app) -> spec.id == pluginId && app.id == appId }) return false
        val preference = AppPrefs.getInstance().internal.preferredJsApp
        val previous = preference.getValue()
        val selected = if (pluginId == DEFAULT_PLUGIN_ID && appId == DEFAULT_APP_ID) "" else key(pluginId, appId)
        if (preference.sharedPreferences.edit().putString(preference.key, selected).commit()) return true
        // SharedPreferences also changes its in-memory value when a disk commit fails.
        preference.sharedPreferences.edit().putString(preference.key, previous).commit()
        return false
    }

    // Both components are validated plugin/app IDs and cannot contain a slash.
    private fun key(pluginId: String, appId: String) = "$pluginId/$appId"
}
