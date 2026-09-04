/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.action.ButtonAction
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.config.ButtonIconFile
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(
    override val ctx: Context,
    private val theme: Theme,
    private var buttons: List<ConfigurableButton> = ButtonsLayoutConfig.default().kawaiiBarButtons
) : Ui {

    @DrawableRes
    private val floatingIcon = R.drawable.ic_floating_toggle_24

    override val root = view(::KawaiiBarRecyclerView) {
        // Set fixed height to match KawaiiBar height
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ctx.dp(KawaiiBarComponent.HEIGHT)
        )
    }

    // Map to store button references by ID
    private val buttonMap = mutableMapOf<String, ToolButton>()
    // Keep per-button active state so recycled/rebound views always restore correct tint.
    private val buttonActiveMap = mutableMapOf<String, Boolean>()

    // Click listeners for each button
    private val clickListeners = mutableMapOf<String, View.OnClickListener>()
    private val longClickListeners = mutableMapOf<String, View.OnLongClickListener>()

    init {
        buildButtons()
    }

    private fun buildButtons() {
        buttonMap.clear()
        val recyclerView = root
        // Recreate adapter to ensure clean state
        recyclerView.adapter = ButtonsBarAdapter()
        // The button count decides even-distribution vs scroll mode, and bind no longer works
        // that out for itself (see E8).
        recyclerView.updateLayoutMode()
    }

    fun updateConfig(newButtons: List<ConfigurableButton>) {
        if (newButtons != buttons) {
            buttons = newButtons
            buildButtons()
        }
    }

    /**
     * Reload icons from disk for all buttons that use file-based custom icons.
     * Call this when icon files have changed on disk to refresh button drawables
     * without rebuilding the entire adapter.
     */
    fun reloadIcons() {
        buttons.forEach { config ->
            val button = buttonMap[config.id] ?: return@forEach
            if (config.icon != null && config.icon.startsWith("file:")) {
                applyIconAndText(button, config)
            }
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

    @DrawableRes
    private fun getIconResForButton(buttonId: String, customIcon: String?): Int {
        // If custom icon is specified, try to find it
        if (customIcon != null && !customIcon.startsWith("file:")) {
            val resId = ctx.resources.getIdentifier(customIcon, "drawable", ctx.packageName)
            if (resId != 0) return resId
        }

        // Check icon theme for SVG icon (resource icons handled separately in applyIconAndText)
        val action = ButtonAction.fromId(buttonId)
        return action?.defaultIcon ?: R.drawable.ic_baseline_more_horiz_24
    }

    private fun loadFileIcon(path: String): Drawable? {
        return ButtonIconFile.loadDrawable(path)
    }

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
        if (customIcon.startsWith("file:")) {
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
     * Re-evaluate the layout mode and rebind every button.
     *
     * The mode decision now lives in [KawaiiBarRecyclerView.updateLayoutMode] (see E8), so it
     * has to be triggered here — the adapter no longer does it from within bind.
     */
    fun refreshLayout() {
        val recyclerView = root
        // One rebind, issued from updateLayoutMode()'s posted runnable. Notifying here as well
        // meant two overlapping change notifications per refresh.
        recyclerView.updateLayoutMode(alwaysRebind = true)
        recyclerView.requestLayout()
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

    private inner class ButtonsBarAdapter : RecyclerView.Adapter<ButtonsBarAdapter.ButtonViewHolder>() {

        inner class ButtonViewHolder(val button: ToolButton) : RecyclerView.ViewHolder(button)

        override fun getItemCount(): Int = buttons.size

        /**
         * Creates an unconfigured button; everything position-dependent happens in
         * [onBindViewHolder].
         *
         * This used to index `buttons[viewType]` — [getItemViewType] returned the position, so
         * every position was its own view type, the recycler pool never hit, and a holder was
         * created per button (see E8).
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ButtonViewHolder {
            // Icon 0 means "no drawable yet"; onBindViewHolder always assigns a real one through
            // applyIconAndText, which is also what a recycled holder goes through.
            val button = ToolButton(ctx, 0, theme).apply {
                // Ensure button always fills KawaiiBar height
                minimumHeight = ctx.dp(KawaiiBarComponent.HEIGHT)
                layoutParams = FlexboxLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    // Add horizontal margin for spacing between buttons
                    marginStart = ctx.dp(2)
                    marginEnd = ctx.dp(2)
                }
            }
            return ButtonViewHolder(button)
        }

        override fun onBindViewHolder(holder: ButtonViewHolder, position: Int) {
            val recyclerView = root
            // as?, not as: the layout manager can legitimately be absent or replaced, and the
            // unchecked cast crashed instead of skipping the width work (see E8).
            val kawaiiBarLayout = recyclerView.layoutManager as? KawaiiBarLayout ?: return
            val parentWidth = recyclerView.width
            val childCount = itemCount
            val button = holder.button
            val config = buttons[position]
            buttonMap[config.id] = button
            button.contentDescription = config.label ?: getDefaultLabel(config.id)
            button.tag = config.id
            // Listeners are keyed by button id, so they belong to bind, not create.
            button.setOnClickListener(clickListeners[config.id])
            button.setOnLongClickListener(longClickListeners[config.id])
            applyIconAndText(button, config)

            val params = holder.button.layoutParams as FlexboxLayoutManager.LayoutParams

            // Width is a pure function of the current bar width, computed read-only. It
            // deliberately does not consult kawaiiBarLayout.isEvenDistributionMode, which is
            // written by updateLayoutMode()'s posted runnable — reading it here would make each
            // button's width depend on whether that runnable happened to have run yet. What
            // bind must not do is *change* the mode or call notify* (see E8); deriving the same
            // decision without side effects is fine.
            if (parentWidth > 0 && childCount > 0) {
                val idealWidth = kawaiiBarLayout.calculateEvenDistributedWidth(childCount, parentWidth)
                if (idealWidth >= kawaiiBarLayout.minButtonWidth) {
                    // Even distribution: one fixed width per button.
                    params.width = idealWidth
                    params.minWidth = 0
                } else {
                    // Scroll mode: WRAP_CONTENT with minimum width ensures buttons don't shrink
                    params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    params.minWidth = kawaiiBarLayout.minButtonWidth
                }
            } else {
                // Width unknown yet; stay at the intrinsic size so nothing collapses.
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                params.minWidth = kawaiiBarLayout.minButtonWidth
            }
            button.image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            button.setActive(buttonActiveMap[config.id] == true)
        }
    }
}
