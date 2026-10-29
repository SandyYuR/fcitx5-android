/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceProviders
import org.fcitx.fcitx5.android.utils.InputMethodUtil

/** The settings list and toolbar picker must offer the same provider IDs. */
internal object VoiceInputChoices {
    data class Entry(val id: String, val label: String, val source: String)

    fun list(context: Context): List<Entry> {
        val system = InputMethodUtil.listVoiceInputMethods().map { (info, _) ->
            Entry(info.id, info.loadLabel(context.packageManager).toString(), context.getString(R.string.voice_engine_system))
        }
        val plugins = VoiceInputProviderManager.listProviders(context).map {
            Entry(it.id, it.label.toString(), context.getString(
                if (JsVoiceProviders.specOf(it.id) != null) R.string.voice_engine_javascript else R.string.voice_engine_plugin,
            ))
        }
        return listOf(Entry("", context.getString(R.string.system_default), context.getString(R.string.voice_engine_system))) +
            system + plugins
    }
}
