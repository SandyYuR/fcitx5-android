/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Restoring app data must not acquire preference state merely to display its UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class RestoreUiThemeTest {
    @Test fun recoveryDialogsUseMaterialResourcesWithoutOpeningPreferences() {
        val base = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                error("Recovery UI must not open preferences: $name")
            override fun getApplicationContext(): Context = this
        }
        val context = restoreUiContext(base)
        val material = context.theme.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.isMaterialTheme))
        try { assertTrue(material.getBoolean(0, false)) } finally { material.recycle() }
        val dialog = MaterialAlertDialogBuilder(context).setTitle("Restore")
            .setMessage("The original data is preserved.").setPositiveButton(android.R.string.ok, null).create()
        dialog.dismiss()
    }
}
