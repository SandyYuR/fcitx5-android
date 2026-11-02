/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.MacroEditorDialogFragment
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/** Hosts the macro editor dialog in Robolectric tests the same way as the app's settings pages. */
internal class MacroEditorTestActivity : SettingsUiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
        if (supportFragmentManager.findFragmentByTag(MacroEditorDialogFragment.TAG) == null) {
            MacroEditorDialogFragment.newInstance(intent)
                .show(supportFragmentManager, MacroEditorDialogFragment.TAG)
            supportFragmentManager.executePendingTransactions()
        }
    }
}

internal fun launchMacroEditorTestHost(
    intent: Intent = Intent(),
    savedInstanceState: Bundle? = null
): ActivityController<MacroEditorTestActivity> {
    val controller = Robolectric.buildActivity(MacroEditorTestActivity::class.java, intent)
    return if (savedInstanceState == null) controller.setup() else controller.setup(savedInstanceState)
}

internal fun MacroEditorTestActivity.macroEditorFragment() =
    supportFragmentManager.findFragmentByTag(MacroEditorDialogFragment.TAG) as MacroEditorDialogFragment
