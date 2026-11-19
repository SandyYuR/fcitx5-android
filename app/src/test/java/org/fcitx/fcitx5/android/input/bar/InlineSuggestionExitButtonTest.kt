/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

import android.view.View
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.bar.ui.IdleUi
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 内嵌自动填充建议（密码管理器）占住工具栏时，最左侧按钮要变成「退出建议」的返回箭头。
 *
 * 点击链路在 [KawaiiBarComponent] 里按窗口判定，这里只核对 [IdleUi] 暴露出来的可见状态：
 * 图标换成了箭头（文字标签不再顶替图标），无障碍描述与剪贴板、状态区都不相同，退出后能恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class InlineSuggestionExitButtonTest {
    private class HostActivity : SettingsUiActivity()

    @Test fun inlineSuggestionsTurnTheStatusButtonIntoAnExitAffordance() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        try {
            val activity = controller.get()
            SplitKeyboardStateManager.init(activity)
            val ui = IdleUi(
                activity,
                ThemePreset.PixelLight,
                PopupComponent(),
                CommonKeyActionListener(),
                buttonsConfig = emptyList()
            )
            activity.setContentView(ui.root)
            ui.root.setViewTreeLifecycleOwner(activity)
            ui.applyToolbarScale()

            val statusDescription = activity.getString(R.string.status_area)
            val clipboardDescription = activity.getString(R.string.return_to_toolbar)
            val exitDescription = activity.getString(R.string.exit_inline_suggestions)

            ui.updateState(IdleUi.State.Toolbar)
            assertEquals(statusDescription, ui.menuButton.contentDescription)

            ui.updateState(IdleUi.State.InlineSuggestion)
            // 密码管理器的条目贴在工具栏中间，没有图标出口就只能靠系统返回键
            assertEquals(View.VISIBLE, ui.menuButton.image.visibility)
            assertEquals(View.GONE, ui.menuButton.textView.visibility)
            assertEquals(exitDescription, ui.menuButton.contentDescription)
            assertNotEquals(statusDescription, ui.menuButton.contentDescription)
            assertNotEquals(clipboardDescription, ui.menuButton.contentDescription)

            // 建议消失后回到原来的状态区图标
            ui.updateState(IdleUi.State.Toolbar)
            assertEquals(statusDescription, ui.menuButton.contentDescription)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
