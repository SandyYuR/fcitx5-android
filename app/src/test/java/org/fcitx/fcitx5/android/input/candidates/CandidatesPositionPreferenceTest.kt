/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesPosition
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class CandidatesPositionPreferenceTest {
    private val storage =
        RuntimeEnvironment.getApplication().getSharedPreferences("candidate-positions", Context.MODE_PRIVATE)

    @Test
    fun keepsSavedAlwaysPositionAndDefaultPhysicalPlacement() {
        storage.edit().putString("virtual_keyboard_candidates_position", "BottomRight").apply()
        val prefs = AppPrefs(storage).candidates

        assertEquals(
            FloatingCandidatesPosition.BottomRight,
            prefs.windowPosition(FloatingCandidatesMode.Always, virtualKeyboard = true)
        )
        assertNull(prefs.windowPosition(FloatingCandidatesMode.InputDevice, virtualKeyboard = false))

        prefs.physicalKeyboardPosition.setValue(FloatingCandidatesPosition.FollowCursor)
        val restored = AppPrefs(storage).candidates
        assertEquals(
            FloatingCandidatesPosition.FollowCursor,
            restored.windowPosition(FloatingCandidatesMode.InputDevice, virtualKeyboard = false)
        )
        assertEquals(FloatingCandidatesPosition.BottomRight, restored.virtualKeyboardPosition.getValue())
    }

    @Test
    fun configuresOnlyTheFloatingWindowUsedByTheCurrentInputDevice() {
        val prefs = AppPrefs(storage).candidates
        val positionUi = prefs.managedPreferencesUi.single { it.key == prefs.physicalKeyboardPosition.key }

        prefs.physicalKeyboardPosition.setValue(FloatingCandidatesPosition.TopRight)
        prefs.mode.setValue(FloatingCandidatesMode.InputDevice)
        assertTrue(positionUi.isEnabled())
        assertNull(prefs.windowPosition(FloatingCandidatesMode.InputDevice, virtualKeyboard = true))
        assertEquals(
            FloatingCandidatesPosition.TopRight,
            prefs.windowPosition(FloatingCandidatesMode.InputDevice, virtualKeyboard = false)
        )

        prefs.physicalKeyboardHorizontalCandidateBar.setValue(true)
        assertFalse(positionUi.isEnabled())
        assertNull(prefs.windowPosition(FloatingCandidatesMode.InputDevice, virtualKeyboard = false))
    }
}
