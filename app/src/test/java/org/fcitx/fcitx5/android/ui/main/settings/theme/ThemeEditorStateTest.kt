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

    @Test fun dedicatedColorEditorRestoresArgbAndExplicitTransparentAlpha() {
        val context = RuntimeEnvironment.getApplication()
        val intent = ThemeColorEditorActivity.Contract().createIntent(context,
            ThemeColorEditorActivity.EditorInput("Background", R.string.edit_color, 0xffabcdef.toInt()))
        val first = Robolectric.buildActivity(ThemeColorEditorActivity::class.java, intent).setup()
        ReflectionHelpers.getField<android.widget.EditText>(first.get(), "argbField").setText("00123456")
        val saved = Bundle()
        first.pause().saveInstanceState(saved).stop().destroy()
        val second = Robolectric.buildActivity(ThemeColorEditorActivity::class.java, intent).setup(saved)
        try {
            assertEquals(0x00123456, ReflectionHelpers.getField<Int>(second.get(), "currentColor"))
            assertTrue(ReflectionHelpers.getField<ThemeColorEditorAlphaState>(second.get(), "alphaState").explicitlyEdited)
            assertEquals("00123456", ReflectionHelpers.getField<android.widget.EditText>(second.get(), "argbField").text.toString().removePrefix("#").uppercase())
        } finally { second.pause().stop().destroy() }
    }

    @Test fun simultaneousColorEditorsPreserveEachOthersConfirmedChanges() {
        org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager.init(RuntimeEnvironment.getApplication())
        val original = ThemePreset.PixelLight.deriveCustomNoBackground("Two color groups")
        val intent = Intent(RuntimeEnvironment.getApplication(), CustomThemeActivity::class.java)
            .putExtra(CustomThemeActivity.ORIGIN_THEME, original)
        val controller = Robolectric.buildActivity(CustomThemeActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
                if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val root = activity.window.decorView
            fun open(title: Int): android.view.ViewGroup {
                val label = descendants(root).filterIsInstance<android.widget.TextView>()
                    .first { it.text.toString() == activity.getString(title) }
                label.performClick()
                val parent = label.parent as android.view.ViewGroup
                return parent.getChildAt(parent.indexOfChild(label) + 1) as android.view.ViewGroup
            }
            val backgroundEditor = open(R.string.theme_color_background)
            val keyEditor = open(R.string.theme_color_key_text)
            fun confirm(editor: android.view.ViewGroup, argb: String) {
                descendants(editor).filterIsInstance<com.google.android.material.textfield.TextInputEditText>().single().setText(argb)
                descendants(editor).filterIsInstance<com.google.android.material.button.MaterialButton>()
                    .first { it.text.toString() == activity.getString(R.string.custom_theme_confirm) }.performClick()
            }
            confirm(backgroundEditor, "FF123456")
            confirm(keyEditor, "FFABCDEF")
            val actual = ReflectionHelpers.getField<Theme.Custom>(activity, "theme")
            assertEquals(0xff123456.toInt(), actual.backgroundColor)
            assertEquals(0xffabcdef.toInt(), actual.keyTextColor)
        } finally { controller.pause().stop().destroy() }
    }

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
