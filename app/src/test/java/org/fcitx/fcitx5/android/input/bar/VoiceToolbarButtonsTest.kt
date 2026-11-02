/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.bar.ui.IdleUi
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class VoiceToolbarButtonsTest {
    private class HostActivity : SettingsUiActivity()

    @Test fun finishRecordingRemainsReachableWhileTheToolbarIsHidden() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val activity = controller.get()
            SplitKeyboardStateManager.init(activity)
            // Space-hold recording also needs a finish control when no microphone is configured.
            for (buttons in listOf(listOf(ConfigurableButton("voice_input")), emptyList())) {
                val ui = IdleUi(activity, ThemePreset.PixelLight, PopupComponent(),
                    CommonKeyActionListener(), buttonsConfig = buttons)
                activity.setContentView(ui.root)
                ui.root.setViewTreeLifecycleOwner(activity)
                ui.applyToolbarScale()
                var finished = 0
                var hidden = 0
                ui.voiceInputFinishButton.setOnClickListener { finished++ }
                ui.hideKeyboardButton.setOnClickListener { hidden++ }
                for (state in IdleUi.State.entries) {
                    ui.updateState(state)
                    assertFalse(ui.voiceInputFinishButton.isShown)
                    ui.showVoiceStatus("Listening")
                    ui.root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
                    ui.root.layout(0, 0, 1080, 120)
                    assertTrue(ui.voiceInputFinishButton.isShown)
                    assertTrue(ui.hideKeyboardButton.isShown)
                    assertFalse(ui.buttonsUi.root.isShown)
                    val finishBounds = Rect()
                    ui.voiceInputFinishButton.getDrawingRect(finishBounds)
                    ui.root.offsetDescendantRectToMyCoords(ui.voiceInputFinishButton, finishBounds)
                    val hideBounds = Rect()
                    ui.hideKeyboardButton.getDrawingRect(hideBounds)
                    ui.root.offsetDescendantRectToMyCoords(ui.hideKeyboardButton, hideBounds)
                    assertTrue(finishBounds.width() > 0 && finishBounds.height() > 0)
                    assertFalse(Rect.intersects(finishBounds, hideBounds))
                    val before = finished
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now, action,
                            finishBounds.exactCenterX(), finishBounds.exactCenterY(), 0)
                        try { ui.root.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                    assertEquals(before + 1, finished)
                    assertEquals(0, hidden)
                    ui.showVoiceStatus("Recognizing")
                    assertTrue(ui.voiceInputFinishButton.isShown)
                    ui.hideVoiceStatus()
                    assertFalse(ui.voiceInputFinishButton.isShown)
                    assertEquals(state == IdleUi.State.Toolbar, ui.buttonsUi.root.isShown)
                    assertEquals(state == IdleUi.State.NumberRow, ui.numberRow.isShown)
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun voiceUsesConfigurableToolbarButtonAndHideRemainsFixed() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val activity = controller.get()
            SplitKeyboardStateManager.init(activity)
            val ui = IdleUi(
                activity,
                ThemePreset.PixelLight,
                PopupComponent(),
                CommonKeyActionListener(),
                buttonsConfig = listOf(ConfigurableButton("voice_input"))
            )
            activity.setContentView(ui.root)
            ui.root.setViewTreeLifecycleOwner(activity)
            val voiceButton = requireNotNull(ui.buttonsUi.getButton("voice_input"))
            var hidden = 0
            var started = 0
            var menus = 0
            ui.hideKeyboardButton.setOnClickListener { hidden++ }
            voiceButton.setOnClickListener { started++ }
            voiceButton.setOnLongClickListener { menus++; true }
            voiceButton.longPressFeedbackEnabled = false
            fun layout() {
                ui.root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
                ui.root.layout(0, 0, 1080, 120)
            }
            ui.applyToolbarScale()
            layout()
            assertEquals(View.VISIBLE, ui.hideKeyboardButton.visibility)
            assertTrue(voiceButton.width > 0)
            ui.hideKeyboardButton.performClick()
            assertEquals(1, hidden)
            assertEquals(0, started)

            val down = SystemClock.uptimeMillis()
            fun touch(action: Int) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 10f, 10f, 0)
                try { voiceButton.onTouchEvent(event) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
                AppPrefs.getInstance().keyboard.longPressDelay.getValue().toLong() + 50))
            touch(MotionEvent.ACTION_UP)
            assertEquals(1, menus)
            assertEquals(0, started)
            ui.buttonsUi.updateConfig(emptyList())
            layout()
            assertEquals(View.VISIBLE, ui.hideKeyboardButton.visibility)
            assertNull(ui.buttonsUi.getButton("voice_input"))
        } finally { controller.pause().stop().destroy() }
    }
}
