/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.common

import android.graphics.drawable.ColorDrawable
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class DynamicListEntryUiTest {
    @Test fun replacingListActionIconsKeepsTheirContrastInLightAndDarkThemes() {
        for (theme in listOf(R.style.Theme_FcitxSettings_Base_Light, R.style.Theme_FcitxSettings_Base_Dark)) {
            val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), theme)
            val entry = DynamicListEntryUi(context)
            val background = (entry.root.background as ColorDrawable).color
            for (button in listOf(entry.settingsButton, entry.editButton)) {
                // Directory management replaces the default settings icon with a white vector.
                for (icon in listOf(R.drawable.ic_baseline_more_horiz_24, R.drawable.ic_baseline_edit_24)) {
                    button.setImageResource(icon)
                    val tint = button.imageTintList
                    assertNotNull("Action tint must survive setImageResource", tint)
                    val color = tint!!.getColorForState(button.drawableState, tint.defaultColor)
                    assertTrue("Action icon must remain visible on the list surface", ColorUtils.calculateContrast(color, background) >= 3.0)
                }
                var clicked = false
                button.setOnClickListener { clicked = true }
                assertTrue(button.performClick())
                assertTrue(clicked)
            }
        }
    }
}
