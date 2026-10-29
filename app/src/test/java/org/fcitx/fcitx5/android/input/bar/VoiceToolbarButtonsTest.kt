/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

import android.view.View
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.bar.ui.IdleUi
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class VoiceToolbarButtonsTest {
    private class HostActivity : SettingsUiActivity()

    @Test fun voiceAndHideRemainSeparateWhenVoiceIsEnabled() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val activity = controller.get()
            SplitKeyboardStateManager.init(activity)
            val ui = IdleUi(activity, ThemePreset.PixelLight, PopupComponent(), CommonKeyActionListener())
            activity.setContentView(ui.root)
            ui.root.setViewTreeLifecycleOwner(activity)
            var hidden = 0
            var started = 0
            ui.hideKeyboardButton.setOnClickListener { hidden++ }
            ui.voiceInputButton.setOnClickListener { started++ }
            fun layout() {
                ui.root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
                ui.root.layout(0, 0, 1080, 120)
            }
            ui.setVoiceInputVisible(true)
            ui.applyToolbarScale()
            layout()
            assertEquals(View.VISIBLE, ui.hideKeyboardButton.visibility)
            assertEquals(View.VISIBLE, ui.voiceInputButton.visibility)
            assertTrue(ui.voiceInputButton.width > 0)
            assertTrue(ui.voiceInputButton.right <= ui.hideKeyboardButton.left)
            ui.hideKeyboardButton.performClick()
            assertEquals(1, hidden)
            assertEquals(0, started)

            ui.setVoiceInputVisible(false)
            layout()
            assertEquals(View.VISIBLE, ui.hideKeyboardButton.visibility)
            assertEquals(View.GONE, ui.voiceInputButton.visibility)
        } finally { controller.pause().stop().destroy() }
    }
}
