/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.dialog

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.input.voice.VoiceInputChoices

internal object VoiceInputPickerDialog {
    fun build(context: Context, onSelected: (String) -> Unit): AlertDialog {
        val entries = VoiceInputChoices.list(context)
        val current = AppPrefs.getInstance().keyboard.preferredVoiceInput.getValue()
        val themed = SettingsUiPreferences.themedContext(context, SettingsUiPreferences.read(context))
        val builder = MaterialAlertDialogBuilder(themed)
        val choiceTint = AppCompatResources.getColorStateList(builder.context, R.color.voice_input_choice_tint)
        val labels = entries.map { it.label + "\n" + it.source }
        val adapter = object : ArrayAdapter<String>(builder.context, android.R.layout.simple_list_item_single_choice, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as CheckedTextView).apply {
                    // Platform single-choice rows can inherit legacy/OEM control colors.
                    // Bind every state to the dialog's Material 3 color roles explicitly.
                    checkMarkTintList = choiceTint
                    isSingleLine = false
                    maxLines = 4
                    text = SpannableString(labels[position]).apply {
                        val start = entries[position].label.length + 1
                        setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(ForegroundColorSpan(MaterialColors.getColor(builder.context,
                            com.google.android.material.R.attr.colorOnSurfaceVariant, 0)),
                            start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
        }
        return builder.setTitle(R.string.choose_voice_engine)
            .setSingleChoiceItems(adapter, entries.indexOfFirst { it.id == current }) { dialog, index ->
                val id = entries[index].id
                if (id != current) {
                    AppPrefs.getInstance().keyboard.preferredVoiceInput.setValue(id)
                    onSelected(id)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
