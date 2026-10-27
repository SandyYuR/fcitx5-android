/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fcitx.fcitx5.android.R

internal data class BackupProtection(val password: CharArray?)

/** Password fields deliberately have no saved state. Cancellation does not retain a password. */
internal suspend fun Context.chooseBackupProtection(export: Boolean): BackupProtection? =
    suspendCancellableCoroutine { continuation ->
        val password = EditText(this).apply {
            hint = getString(R.string.backup_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true; isSaveEnabled = false
        }
        val confirmation = EditText(this).apply {
            hint = getString(R.string.backup_password_confirm)
            inputType = password.inputType
            isSingleLine = true; isSaveEnabled = false
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(password)
            if (export) addView(confirmation)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (export) R.string.backup_protected_export else R.string.backup_protected_import)
            .setMessage(if (export) R.string.backup_protection_explanation else R.string.backup_enter_password)
            .setView(fields)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (export) builder.setNeutralButton(R.string.backup_plaintext_zip) { _, _ ->
            if (continuation.isActive) continuation.resume(BackupProtection(null), onCancellation = { _, _, _ -> })
        }
        val dialog = builder.create()
        dialog.setOnDismissListener {
            password.text.clear(); confirmation.text.clear()
            if (continuation.isActive) continuation.resume(null, onCancellation = { _, _, _ -> })
        }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (password.text.isEmpty()) { password.error = getString(R.string.backup_enter_password); return@setOnClickListener }
                if (export && !android.text.TextUtils.equals(password.text, confirmation.text)) {
                    confirmation.error = getString(R.string.backup_password_mismatch); return@setOnClickListener
                }
                val secret = CharArray(password.text.length) { password.text[it] }
                password.text.clear(); confirmation.text.clear()
                if (continuation.isActive) continuation.resume(BackupProtection(secret),
                    onCancellation = { _, value, _ -> value?.password?.fill('\u0000') })
                else secret.fill('\u0000')
                dialog.dismiss()
            }
        }
        dialog.show()
        continuation.invokeOnCancellation { dialog.dismiss() }
    }
