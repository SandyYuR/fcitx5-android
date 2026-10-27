/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeColorEditorActivity

/** Register before STARTED; buffer results while the layout file is restored asynchronously. */
class KeyEditorResultHost(activity: ComponentActivity) {
    private var form: KeyEditorForm? = null
    private val pending = KeyEditorPendingResults()
    private fun deliver(kind: KeyEditorPendingResults.Kind, result: Parcelable) {
        val current = form
        if (current == null) pending.offer(kind, result) else apply(current, kind, result)
    }
    private fun apply(form: KeyEditorForm, kind: KeyEditorPendingResults.Kind, result: Parcelable) {
        when (kind) {
            KeyEditorPendingResults.Kind.MACRO -> form.acceptMacroResult(result as ActivityResult)
            KeyEditorPendingResults.Kind.COLOR -> form.acceptColorResult(result as ThemeColorEditorActivity.EditorResult)
            KeyEditorPendingResults.Kind.COMPOSE -> form.acceptComposeResult(result as ActivityResult)
        }
    }
    private val macro = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        deliver(KeyEditorPendingResults.Kind.MACRO, it)
    }
    private val color = activity.registerForActivityResult(ThemeColorEditorActivity.Contract()) {
        if (it != null) deliver(KeyEditorPendingResults.Kind.COLOR, it)
    }
    private val compose = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        deliver(KeyEditorPendingResults.Kind.COMPOSE, it)
    }

    fun createForm(context: Context, options: Intent, state: Bundle? = null) = KeyEditorForm(
        context, options, state, { macro.launch(it) }, { color.launch(it) }, { compose.launch(it) }
    )

    fun bind(value: KeyEditorForm?) {
        form = value
        if (value != null) {
            pending.takeAll().forEach { (kind, result) -> apply(value, kind, result) }
        }
    }

    fun saveState(out: Bundle) = pending.save(out)
    fun restoreState(state: Bundle?) = pending.restore(state)
}
