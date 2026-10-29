/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.voice

import android.widget.TextView
import android.widget.CheckedTextView
import android.graphics.Color
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import com.google.android.material.color.MaterialColors
import org.fcitx.fcitx5.android.input.dialog.VoiceInputPickerDialog
import org.fcitx.fcitx5.android.input.voice.VoiceInputChoices
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceProviders
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class VoiceInputDialogsTest {
    private class HostActivity : SettingsUiActivity()
    private lateinit var controller: ActivityController<HostActivity>

    @Before fun setUp() {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        AppPrefs.getInstance().keyboard.preferredVoiceInput.setValue(JsVoiceProviders.QWEN_ASR_ID)
    }
    @After fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test fun pickerChecksCurrentEnginePersistsSelectionAndCancelKeepsIt() {
        val context = controller.get()
        val choices = VoiceInputChoices.list(context)
        val targetIndex = choices.indexOfFirst { it.id == JsVoiceProviders.TENCENT_TRANSLATE_ID }
        assertTrue(targetIndex >= 0)
        var selected: String? = null
        val dialog = VoiceInputPickerDialog.build(context) { selected = it }.apply { show() }
        assertEquals(choices.indexOfFirst { it.id == JsVoiceProviders.QWEN_ASR_ID }, dialog.listView.checkedItemPosition)
        dialog.listView.performItemClick(null, targetIndex, targetIndex.toLong())
        assertEquals(JsVoiceProviders.TENCENT_TRANSLATE_ID, selected)
        assertEquals(JsVoiceProviders.TENCENT_TRANSLATE_ID, AppPrefs.getInstance().keyboard.preferredVoiceInput.getValue())

        val next = VoiceInputPickerDialog.build(context) { fail("Cancel must not select an engine") }.apply { show() }
        assertEquals(targetIndex, next.listView.checkedItemPosition)
        next.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(JsVoiceProviders.TENCENT_TRANSLATE_ID, AppPrefs.getInstance().keyboard.preferredVoiceInput.getValue())
    }

    @Test fun pickerRadioColorsFollowMaterialRolesInLightAndDarkMode() {
        val context = controller.get()
        val original = SettingsUiPreferences.read(context)
        try {
            for (mode in listOf(SettingsUiPreferences.MODE_LIGHT, SettingsUiPreferences.MODE_DARK)) {
                SettingsUiPreferences.save(context, original.copy(mode = mode,
                    palette = SettingsUiPreferences.PALETTE_BERRY, followKeyboardTheme = false))
                val dialog = VoiceInputPickerDialog.build(context) {}.apply { show() }
                try {
                    val row = dialog.listView.adapter.getView(0, null, dialog.listView) as CheckedTextView
                    val tint = requireNotNull(row.checkMarkTintList)
                    val unchecked = tint.getColorForState(intArrayOf(android.R.attr.state_enabled), Color.MAGENTA)
                    val checked = tint.getColorForState(intArrayOf(android.R.attr.state_enabled,
                        android.R.attr.state_checked), Color.MAGENTA)
                    assertEquals(mode, MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurfaceVariant), unchecked)
                    assertEquals(mode, MaterialColors.getColor(row, androidx.appcompat.R.attr.colorPrimary), checked)
                    assertNotEquals(mode, Color.MAGENTA, unchecked)
                    assertNotEquals(mode, unchecked, checked)
                    // Recycled rows must keep the stateful tint, rather than one flat checked color.
                    val recycled = dialog.listView.adapter.getView(1, row, dialog.listView) as CheckedTextView
                    assertTrue(recycled.checkMarkTintList!!.isStateful)
                } finally { dialog.dismiss() }
            }
        } finally { SettingsUiPreferences.save(context, original) }
    }
}
