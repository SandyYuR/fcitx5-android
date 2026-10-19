/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.modified

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import android.content.Context
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import org.fcitx.fcitx5.android.R

class MySwitchPreference(context: Context) : SwitchPreferenceCompat(context) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(title ?: "Preference")
                .setMessage(R.string.whether_reset_switch_preference)
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .setPositiveButton(R.string.reset) { _, _ -> restore() }
                .show()
            true
        }
    }
}
