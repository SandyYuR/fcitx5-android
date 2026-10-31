/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Bundle
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class SettingsUiMotionResetTest {
    private class Host : SettingsUiActivity() {
        var recreations = 0
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = 1234 })
        }
        override fun recreate() { recreations++ }
    }

    @Test fun resettingEnabledPreviewRecreatesTheHost() = resetFrom(true, 1)
    @Test fun resettingDisabledPreviewDoesNotRecreateTheHost() = resetFrom(false, 0)

    private fun resetFrom(enabled: Boolean, expectedRecreations: Int) {
        val context = RuntimeEnvironment.getApplication()
        val original = SettingsUiMotionPreferences.read(context)
        SettingsUiMotionPreferences.save(context, original.copy(predictiveBack = enabled))
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        try {
            val page = SettingsUiMotionFragment()
            controller.get().supportFragmentManager.beginTransaction().replace(1234, page).commitNow()
            controller.get().recreations = 0
            val screen = page.preferenceScreen
            val reset = (0 until screen.preferenceCount).map(screen::getPreference)
                .first { it.title == context.getString(R.string.settings_ui_motion_reset) }
            reset.onPreferenceClickListener!!.onPreferenceClick(reset)
            assertFalse(SettingsUiMotionPreferences.read(context).predictiveBack)
            assertEquals(expectedRecreations, controller.get().recreations)
        } finally {
            controller.pause().stop().destroy()
            SettingsUiMotionPreferences.save(context, original)
        }
    }
}
