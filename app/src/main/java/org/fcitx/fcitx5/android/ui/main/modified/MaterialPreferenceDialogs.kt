/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.modified

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceDialogFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Keep AndroidX's value/validation callbacks while supplying a Material dialog container. */
internal fun PreferenceDialogFragmentCompat.materialDialog(
    createView: (Context) -> View?,
    bindView: (View) -> Unit,
    prepare: (AlertDialog.Builder) -> Unit
): AlertDialog {
    val pref = preference
    val builder = MaterialAlertDialogBuilder(requireContext())
        .setTitle(pref.dialogTitle)
        .setIcon(pref.dialogIcon)
        .setPositiveButton(pref.positiveButtonText, this)
        .setNegativeButton(pref.negativeButtonText, this)
    val content = createView(builder.context)
    if (content == null) builder.setMessage(pref.dialogMessage) else {
        bindView(content)
        builder.setView(content)
    }
    prepare(builder)
    return builder.create()
}
