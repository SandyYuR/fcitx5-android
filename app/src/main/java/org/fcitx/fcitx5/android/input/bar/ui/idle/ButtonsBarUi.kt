/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.action.ButtonAction
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ToolbarMetrics
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.config.ButtonIconFile
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

/**
 * The configurable button strip in the middle of the Kawaii Bar.
 *
 * One [ToolButton] is created per configured button and kept for the lifetime of the strip; the
 * row itself ([KawaiiBarRowLayout]) only decides widths and positions. There is deliberately no
 * adapter and no view recycling: with at most a handful of static buttons, recycling bought
 * nothing and its recycled holders (created without an icon) were a way for the strip to end up
 * blank.
 */
class ButtonsBarUi(
    override val ctx: Context,
    private val theme: Theme,
    private var buttons: List<ConfigurableButton> = ButtonsLayoutConfig.default().kawaiiBarButtons
) : Ui {

    override val root = view(::KawaiiBarRowLayout) { }

    // Map to store button references by ID
    private val buttonMap = mutableMapOf<String, ToolButton>()
    // Keep per-button active state so a rebuilt button always restores its correct tint.
    private val buttonActiveMap = mutableMapOf<String, Boolean>()

    // Click listeners for each button
    private val clickListeners = mutableMapOf<String, View.OnClickListener>()
    private val longClickListeners = mutableMapOf<String, View.OnLongClickListener>()

    init {
        buildButtons()
    }

    private fun buildButtons() {
        root.removeAllViews()
        buttonMap.clear()
        val scale = KawaiiBarComponent.resolveScale()
        // 横向足迹固定 44dp，**不随「工具栏大小」变化**：见 ToolbarMetrics 的类注释。
        // 足迹跟着百分比长大的话，8 个按钮很快就不再满足「平分宽度」，整行退回横向滚动，
        // 用户只看得见 3 个——那正是这次要修的现象。
        val footprintDp = ToolbarMetrics.BUTTON_FOOTPRINT_DP
        buttons.forEach { config ->
            val button = ToolButton(ctx, 0, theme)
            button.layoutParams = MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                // Horizontal margin for spacing between buttons
                marginStart = ctx.dp(2)
                marginEnd = ctx.dp(2)
            }
            button.contentDescription = config.label ?: getDefaultLabel(config.id)
            button.tag = config.id
            button.setOnClickListener(clickListeners[config.id])
            button.setOnLongClickListener(longClickListeners[config.id])
            applyIconAndText(button, config)
            button.applyHorizontalFootprint(footprintDp)
            button.minimumHeight = KawaiiBarComponent.resolveHeightPx(ctx)
            button.applyToolbarScale(scale)
            button.setActive(buttonActiveMap[config.id] == true)
            buttonMap[config.id] = button
            root.addView(button)
        }
    }

    fun updateConfig(newButtons: List<ConfigurableButton>) {
        if (newButtons != buttons) {
            buttons = newButtons
            buildButtons()
        }
    }

    /**
     * Re-resolve every button's icon and label from the current icon theme / button config.
     *
     * Call this when icon theme settings or icon files changed on disk. The icons are resolved
     * once per button instead of on every layout pass, so this is the only path that has to run
     * after such a change.
     */
    fun reloadIcons() {
        buttons.forEach { config ->
            val button = buttonMap[config.id] ?: return@forEach
            applyIconAndText(button, config)
        }
    }

    fun setOnClickListener(buttonId: String, listener: View.OnClickListener?) {
        if (listener != null) {
            clickListeners[buttonId] = listener
        } else {
            clickListeners.remove(buttonId)
        }
        buttonMap[buttonId]?.setOnClickListener(listener)
    }

    fun setOnLongClickListener(buttonId: String, listener: View.OnLongClickListener?) {
        if (listener != null) {
            longClickListeners[buttonId] = listener
        } else {
            longClickListeners.remove(buttonId)
        }
        buttonMap[buttonId]?.setOnLongClickListener(listener)
    }

    private fun loadFileIcon(path: String) = ButtonIconFile.loadDrawable(path)

    private fun applyIconThemeIfAvailable(button: ToolButton, buttonId: String): Boolean {
        val action = ButtonAction.fromId(buttonId) ?: return false
        val slot = action.iconSlot ?: return false
        val iconInfo = IconThemeManager.resolveIconDrawableInfo(slot)
        if (iconInfo != null) {
            button.setIconFromDrawable(iconInfo.drawable, tintWithTheme = iconInfo.tintWithTheme)
            return true
        }
        val textValue = IconThemeManager.resolveIcon(slot)
        if (textValue != null) {
            button.setText(textValue)
            return true
        }
        return false
    }

    private fun applyConfiguredIconIfAvailable(button: ToolButton, config: ConfigurableButton): Boolean {
        if (!config.text.isNullOrEmpty()) {
            button.setText(config.text)
            return true
        }
        val customIcon = config.icon ?: return false
        if (customIcon.startsWith(ButtonIconFile.PREFIX)) {
            val drawable = loadFileIcon(customIcon) ?: return false
            val tintWithTheme = ButtonIconFile.shouldTintIcon(customIcon)
            button.setIconFromDrawable(drawable, tintWithTheme = tintWithTheme)
            return true
        }
        val resId = ctx.resources.getIdentifier(customIcon, "drawable", ctx.packageName)
        if (resId != 0) {
            button.setIcon(resId)
            return true
        }
        return false
    }

    /**
     * Apply the icon (or text) for [config] to [button].
     *
     * Every branch that does not actually set a drawable or a text falls through to the default
     * icon, so a button can never be left without visual content.
     */
    private fun applyIconAndText(button: ToolButton, config: ConfigurableButton) {
        if (applyIconThemeIfAvailable(button, config.id)) return
        if (applyConfiguredIconIfAvailable(button, config)) return
        val fallbackIcon = ButtonAction.fromId(config.id)?.defaultIcon ?: R.drawable.ic_baseline_more_horiz_24
        button.setIcon(fallbackIcon)
    }

    private fun getDefaultLabel(buttonId: String): String {
        // Return default label from ButtonAction
        return ButtonAction.fromId(buttonId)?.let { action ->
            ctx.getString(action.defaultLabelRes)
        } ?: when (buttonId) {
            "floating_toggle" -> ctx.getString(R.string.floating_keyboard)
            else -> buttonId
        }
    }

    fun getButton(buttonId: String): ToolButton? = buttonMap[buttonId]

    fun clearTransientPressState() {
        buttonMap.values.forEach { it.clearTransientPressState() }
    }

    fun setFloatingState(isFloating: Boolean) {
        buttonActiveMap["floating_toggle"] = isFloating
        buttonMap["floating_toggle"]?.setActive(isFloating)
    }

    fun setOneHandKeyboardState(isOneHanded: Boolean) {
        buttonActiveMap["one_handed_keyboard"] = isOneHanded
        buttonMap["one_handed_keyboard"]?.setActive(isOneHanded)
    }

    /**
     * Re-run the row layout.
     *
     * The row derives every button's width from the width it is measured with, so a plain layout
     * request is all that is needed to bring it back in sync — there is no cached flex/scroll
     * state to invalidate, and no rebind to schedule.
     *
     * 「工具栏大小」变化也走这里（[KawaiiBarComponent.refreshButtonsLayout]）：几何值全部
     * 现读偏好，但按钮的 minimumHeight 与图标缩放是**写进 View 实例**的，必须逐个重放，
     * 否则设置页调完大小后行高变了、图标却还是旧的。
     */
    fun refreshLayout() {
        applyToolbarScale()
        root.requestLayout()
        root.invalidate()
    }

    /** 把当前的「工具栏大小」重新应用到每个按钮（尺寸设置变化后调用）。 */
    private fun applyToolbarScale() {
        val scale = KawaiiBarComponent.resolveScale()
        val height = KawaiiBarComponent.resolveHeightPx(ctx)
        buttonMap.values.forEach { button ->
            button.applyHorizontalFootprint(ToolbarMetrics.BUTTON_FOOTPRINT_DP)
            button.minimumHeight = height
            button.applyToolbarScale(scale)
        }
    }

    /**
     * Update all buttons' active state based on their ButtonAction.isActive() method.
     */
    fun updateButtonsState(service: FcitxInputMethodService) {
        ButtonAction.allConfigurableActions.forEach { action ->
            val active = action.isActive(service)
            buttonActiveMap[action.id] = active
            buttonMap[action.id]?.setActive(active)
        }
    }
}
