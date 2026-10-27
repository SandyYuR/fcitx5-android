/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.popup

import android.widget.Button
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class ThemedPopupMenuFocusTest {
    class Host : SettingsUiActivity()

    private fun verify(preserve: Boolean) {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val host = controller.get()
        val anchor = Button(host).apply { text = "你" }
        host.setContentView(anchor)
        var dismissed = false
        val menu = ThemedPopupMenu(host, ThemePreset.PixelLight, preserve) { dismissed = true }
        try {
            menu.show(anchor, listOf(ThemedPopupMenu.Item.Action("忘记此词", {})))
            val list = ReflectionHelpers.getField<ListPopupWindow>(menu, "popup")
            val popup = ReflectionHelpers.getField<PopupWindow>(list, "mPopup")
            assertEquals(!preserve, list.isModal)
            assertEquals(!preserve, popup.isFocusable)
            assertEquals(PopupWindow.INPUT_METHOD_NOT_NEEDED, popup.inputMethodMode)
            assertTrue(popup.isTouchable)
            menu.dismiss()
            assertTrue(dismissed)
        } finally {
            menu.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun candidateMenuUsesATouchableWindowWithoutTakingEditorFocus() = verify(true)
    @Test fun existingModalMenuCallersKeepTheirDefaultMode() = verify(false)
}
