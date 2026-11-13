/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

/** Stable button identity for one exact JS plugin/App pair. */
internal object JsAppButtonBinding {
    private const val PREFIX = "js_app:"

    data class Target(val pluginId: String, val appId: String)

    fun encode(pluginId: String, appId: String): String? {
        val rawPluginId = pluginId.removePrefix(JsVoicePluginMeta.PROVIDER_ID_PREFIX)
        if (pluginId != JsVoicePluginMeta.PROVIDER_ID_PREFIX + rawPluginId ||
            !JsVoicePluginMeta.isValidId(rawPluginId) || !JsVoicePluginMeta.isValidId(appId)) return null
        return "$PREFIX$pluginId/$appId"
    }

    fun decode(buttonId: String): Target? {
        if (!buttonId.startsWith(PREFIX)) return null
        val encoded = buttonId.removePrefix(PREFIX)
        val separator = encoded.indexOf('/')
        if (separator <= 0 || separator != encoded.lastIndexOf('/')) return null
        val pluginId = encoded.substring(0, separator)
        val appId = encoded.substring(separator + 1)
        return encode(pluginId, appId)?.takeIf { it == buttonId }?.let { Target(pluginId, appId) }
    }
}
