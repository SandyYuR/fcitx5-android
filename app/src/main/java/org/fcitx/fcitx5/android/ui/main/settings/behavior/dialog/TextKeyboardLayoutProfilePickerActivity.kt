/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.utils.toast

class TextKeyboardLayoutProfilePickerActivity : AppCompatActivity() {
    private var pickerDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPickerDialog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showPickerDialog()
    }

    override fun onDestroy() {
        pickerDialog?.dismiss()
        pickerDialog = null
        super.onDestroy()
    }

    private fun showPickerDialog() {
        pickerDialog?.dismiss()
        val current = UserConfigFiles.normalizeTextKeyboardLayoutProfile(
            AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
        ) ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE

        val profiles = UserConfigFiles.listTextKeyboardLayoutProfiles().toMutableList().apply {
            if (current !in this) add(current)
        }
        val sortedProfiles = profiles
            .distinct()
            .sortedWith(compareBy({ it != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }, { it }))
        val labels = sortedProfiles.map {
            if (it == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
                getString(R.string.default_)
            } else {
                it
            }
        }.toTypedArray()
        val selected = sortedProfiles.indexOf(current).coerceAtLeast(0)

        pickerDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.text_keyboard_layout_file_select_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val target = sortedProfiles.getOrNull(which) ?: return@setSingleChoiceItems
                AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(target)
                ConfigProviders.provider = ConfigProviders.provider
                toast(
                    getString(
                        R.string.text_keyboard_layout_file_select_summary,
                        if (target == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
                            getString(R.string.default_)
                        } else {
                            target
                        }
                    )
                )
                dialog.dismiss()
                finish()
            }
            .setOnDismissListener { finish() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
