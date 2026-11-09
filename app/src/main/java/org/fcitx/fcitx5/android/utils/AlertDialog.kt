/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.app.Dialog
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog

/** Call after content installation for forms that can create text fields dynamically. */
internal fun Dialog.allowImeInput() {
    window?.apply {
        clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}

/**
 * Change positive button listener **AFTER** [AlertDialog.show] has been called.
 *
 * In the listener: `true` to dismiss the dialog; `false` to keep the dialog open.
 */
fun AlertDialog.onPositiveButtonClick(
    listener: AlertDialog.() -> Boolean?
): AlertDialog {
    getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        if (listener.invoke(this) == true) dismiss()
    }
    return this
}
