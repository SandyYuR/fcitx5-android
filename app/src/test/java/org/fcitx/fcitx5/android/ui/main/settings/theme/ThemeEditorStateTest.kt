/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class ThemeEditorStateTest {
    @Test fun draftAndOriginalIdentitySurviveFreshActivityRestoration() {
        org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager.init(RuntimeEnvironment.getApplication())
        val original = ThemePreset.PixelLight.deriveCustomNoBackground("Restoration fixture")
        val intent = Intent(RuntimeEnvironment.getApplication(), CustomThemeActivity::class.java)
            .putExtra(CustomThemeActivity.ORIGIN_THEME, original)
        val first = Robolectric.buildActivity(CustomThemeActivity::class.java, intent).setup()
        val draft = original.copy(keyTextColor = 0xff123456.toInt(), name = "Renamed draft")
        ReflectionHelpers.setField(first.get(), "theme", draft)
        val saved = Bundle()
        first.pause().saveInstanceState(saved).stop().destroy()
        val second = Robolectric.buildActivity(CustomThemeActivity::class.java, intent).setup(saved)
        try {
            assertEquals(draft, ReflectionHelpers.getField<Theme.Custom>(second.get(), "theme"))
            assertEquals(original.name, ReflectionHelpers.getField<String>(second.get(), "originalThemeName"))
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(second.get(), "hasUnsavedChanges"))
            second.get().onBackPressedDispatcher.onBackPressed()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            assertEquals(second.get().getString(R.string.theme_discard), dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
            assertFalse(second.get().isFinishing)
            dialog.dismiss()
        } finally { second.pause().stop().destroy() }
    }
}
