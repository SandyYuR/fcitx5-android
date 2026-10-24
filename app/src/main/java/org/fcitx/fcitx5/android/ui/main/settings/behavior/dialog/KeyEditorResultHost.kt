/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeColorEditorActivity

/** Register before STARTED; buffer results while the layout file is restored asynchronously. */
class KeyEditorResultHost(activity: ComponentActivity) {
    private var form: KeyEditorForm? = null
    private val pending = mutableListOf<(KeyEditorForm) -> Unit>()
    private fun deliver(action: (KeyEditorForm) -> Unit) {
        form?.let(action) ?: pending.add(action)
    }
    private val macro = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        deliver { form -> form.acceptMacroResult(it) }
    }
    private val color = activity.registerForActivityResult(ThemeColorEditorActivity.Contract()) {
        deliver { form -> form.acceptColorResult(it) }
    }
    private val compose = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        deliver { form -> form.acceptComposeResult(it) }
    }

    fun createForm(context: Context, options: Intent, state: Bundle? = null) = KeyEditorForm(
        context, options, state, { macro.launch(it) }, { color.launch(it) }, { compose.launch(it) }
    )

    fun bind(value: KeyEditorForm?) {
        form = value
        if (value != null) {
            val queued = pending.toList()
            pending.clear()
            queued.forEach { it(value) }
        }
    }
}
