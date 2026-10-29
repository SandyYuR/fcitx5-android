/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.voice

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceHostPermissions
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceProviders

internal object JsVoiceHostPermissionDialog {
    fun build(context: Context, spec: JsVoiceProviders.Spec, onChanged: () -> Unit): AlertDialog {
        val themed = SettingsUiPreferences.themedContext(context, SettingsUiPreferences.read(context))
        val builder = MaterialAlertDialogBuilder(themed)
            .setTitle(R.string.js_voice_network_title)
            .setMessage(context.getString(
                R.string.js_voice_network_message, spec.label, spec.hosts.sorted().joinToString("\n"),
            ))
            .setNegativeButton(android.R.string.cancel, null)
        if (JsVoiceHostPermissions.isApproved(spec.id, spec.hosts)) {
            builder.setPositiveButton(R.string.js_voice_network_revoke) { _, _ ->
                JsVoiceHostPermissions.revoke(spec.id)
                onChanged()
            }
        } else {
            builder.setPositiveButton(R.string.js_voice_network_approve) { _, _ ->
                JsVoiceHostPermissions.approve(spec.id, spec.hosts)
                onChanged()
            }
        }
        return builder.create()
    }
}
