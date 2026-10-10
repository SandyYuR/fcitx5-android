/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.view.View
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.PreeditShapeDrawable
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import splitties.dimensions.dp
import splitties.views.horizontalPadding

class PreeditComponent : UniqueComponent<PreeditComponent>(), Dependent, InputBroadcastReceiver,
    ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val theme by manager.theme()

    /**
     * Control whether this PreeditComponent should display preedit text.
     * Set to false when preedit is displayed elsewhere (e.g., in floating CandidatesView).
     */
    var shouldDisplay = true

    /**
     * 键盘卡片上方圆角半径（px）的实际取值。
     *
     * 默认读「工具栏上方圆角」设置；浮动形态下卡片本来就带 10dp 圆角，由 [InputView]
     * 覆盖为两者较大值，否则编码区胶囊的反向弧会收进卡片被裁掉的那块圆角里。
     */
    var cardTopRadiusProvider: () -> Float = {
        context.dp(ThemeManager.prefs.toolbarRadius.getValue()).toFloat()
    }

    val ui by lazy {
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()
        val bkgColor =
            if (!keyBorder && theme is Theme.Builtin) theme.barColor else theme.backgroundColor
        PreeditUi(
            ctx = context,
            theme = theme,
            setupTextView = { horizontalPadding = dp(8) },
            cardTopRadiusProvider = { cardTopRadiusProvider() },
            preeditRadiusProvider = {
                context.dp(ThemeManager.prefs.preeditRadius.getValue()).toFloat()
            }
        ).apply {
            // 编码区胶囊：底部两侧的反向圆角与键盘卡片上方圆角在卡片顶边上相切接续，
            // 见 ContinuousCornerGeometry。
            configureDecoration(bkgColor)
            // TODO make it customizable
            root.alpha = 0.8f
            root.visibility = View.INVISIBLE
        }
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        ui.update(data)
        // Only show if shouldDisplay is true
        ui.root.visibility = if (shouldDisplay && ui.visible) View.VISIBLE else View.INVISIBLE
    }
}
