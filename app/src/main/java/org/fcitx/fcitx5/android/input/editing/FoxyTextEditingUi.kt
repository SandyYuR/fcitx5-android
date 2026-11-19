/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.LayoutKeyColorResolver
import splitties.dimensions.dp

/** Foxy 风格的编辑器页面：上方光标触控板，下方读取 text_editor 布局的按键行。 */
class FoxyTextEditingUi(
    private val viewContext: Context,
    private val viewTheme: Theme,
    private val rippleEnabled: Boolean,
    private val borderEnabled: Boolean,
    private val buttonRadius: Float,
    stepDp: Int,
    longPressDelayMs: Long,
    promptMove: String,
    promptLongPress: String,
    promptSelecting: String,
    promptReleaseSelection: String,
    private val configuredLayout: TextEditingLayout? = null,
    private val onLayoutAction: ((KeyAction) -> Unit)? = null
) : TextEditingUi(viewContext, viewTheme, rippleEnabled, borderEnabled, buttonRadius) {

    // 46dp 时图标和文字都贴着上下边，抬高一点给按键留呼吸感。
    private val keyHeight = viewContext.dp(50)

    val cursorPad = TextEditingCursorPadKey(
        viewContext,
        viewTheme,
        stepDp,
        longPressDelayMs,
        promptMove,
        promptLongPress,
        promptSelecting,
        promptReleaseSelection
    )

    private fun createTextButton(spec: TextEditingKeySpec): TextEditingButton =
        TextEditingButton(
            viewContext,
            viewTheme,
            rippleEnabled,
            borderEnabled,
            buttonRadius,
            spec.altStyle
        ).apply {
            spec.labelRes?.let(::setText)
            if (spec.contentDescriptionRes != null) {
                contentDescription = viewContext.getString(spec.contentDescriptionRes)
            }
            if (spec.iconSlot != null && spec.fallbackIconRes != null) {
                setThemedIcon(spec.iconSlot, spec.fallbackIconRes)
            }
        }

    private fun applySpec(button: TextEditingButton, spec: TextEditingKeySpec) {
        spec.labelRes?.let(button::setText)
        if (spec.contentDescriptionRes != null) {
            button.contentDescription = viewContext.getString(spec.contentDescriptionRes)
        }
        if (spec.iconSlot != null && spec.fallbackIconRes != null) {
            button.setThemedIcon(spec.iconSlot, spec.fallbackIconRes)
        }
    }

    val backToKeyboardButton = createTextButton(
        TextEditingKeySpec(
            labelRes = R.string.text_editing_abc,
            contentDescriptionRes = R.string.back_to_keyboard,
            altStyle = false
        )
    )

    init {
        (super.root as? android.view.ViewGroup)?.removeAllViews()
        // Static fallback uses compact labels; a configured layout owns its own labels.
        applySpec(selectAllButton, TextEditingKeySpec(R.string.text_editing_select_all_short))
        applySpec(cutButton, TextEditingKeySpec(R.string.text_editing_cut))
        applySpec(copyButton, TextEditingKeySpec(R.string.text_editing_copy))
        applySpec(pasteButton, TextEditingKeySpec(R.string.text_editing_paste))
        applySpec(
            backspaceButton,
            TextEditingKeySpec(
                contentDescriptionRes = R.string.backspace,
                iconSlot = "keys.backspace",
                fallbackIconRes = R.drawable.ic_baseline_backspace_24
            )
        )
        backspaceButton.soundEffect = InputFeedbacks.SoundEffect.Delete
        cutButton.visibility = View.VISIBLE
    }

    private fun appearanceLabel(appearance: KeyDef.Appearance): String? = when (appearance) {
        is KeyDef.Appearance.Text -> appearance.displayText
        is KeyDef.Appearance.AltText -> appearance.displayText
        is KeyDef.Appearance.ImageText -> appearance.displayText
        is KeyDef.Appearance.ImageAltText -> appearance.altText.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun createConfiguredButton(key: TextEditingLayoutKey): TextEditingButton {
        val appearance = key.appearance
        val altStyle = appearance.variant == KeyDef.Appearance.Variant.Alternative ||
            appearance.variant == KeyDef.Appearance.Variant.AltForeground
        val button = TextEditingButton(
            viewContext,
            viewTheme,
            rippleEnabled,
            borderEnabled,
            buttonRadius,
            altStyle,
            // 布局里的颜色可以写成 theme: 主题字段（也能写 Monet 资源名），这里必须和键盘
            // 走同一个解析器；直接传 appearance.backgroundColor 只认得出静态色，成套主题
            // 用主题引用给按键上色时编辑器会退回键型默认的灰/白，跟主键盘对不上。
            backgroundColor = LayoutKeyColorResolver.resolve(
                viewContext, viewTheme, appearance.backgroundColor, appearance.backgroundColorMonet
            ),
            shadowColor = LayoutKeyColorResolver.resolve(
                viewContext, viewTheme, appearance.shadowColor, appearance.shadowColorMonet
            ),
            textColor = LayoutKeyColorResolver.resolve(
                viewContext, viewTheme, appearance.textColor, appearance.textColorMonet
            )
        )
        // 布局给键点名了图标槽位就优先显示图标，主题里没有该槽位时仍回落文字。
        val themedIconSlot = key.keyDef.effectiveIconSlot
        when (appearance) {
            is KeyDef.Appearance.Text -> {
                if (themedIconSlot == null || !button.trySetThemedIcon(themedIconSlot)) {
                    button.setText(appearance.displayText)
                }
            }
            is KeyDef.Appearance.AltText -> {
                if (themedIconSlot == null || !button.trySetThemedIcon(themedIconSlot)) {
                    button.setText(appearance.displayText)
                }
            }
            is KeyDef.Appearance.ImageText -> {
                if (appearance.displayText.isNotBlank()) button.setText(appearance.displayText)
                else button.setIcon(appearance.src)
            }
            is KeyDef.Appearance.Image -> {
                if (themedIconSlot != null) {
                    button.setThemedIcon(themedIconSlot, appearance.src)
                } else {
                    button.setIcon(appearance.src)
                }
            }
            is KeyDef.Appearance.ImageAltText -> {
                if (themedIconSlot != null) {
                    button.setThemedIcon(themedIconSlot, appearance.src)
                } else {
                    button.setIcon(appearance.src)
                }
            }
        }
        button.contentDescription = appearanceLabel(appearance)
            ?: viewContext.getString(R.string.text_editing)
        val hasAction = key.tapAction != null || key.repeatAction != null || key.longPressAction != null
        button.isEnabled = hasAction
        key.tapAction?.let { action ->
            button.setOnClickListener { onLayoutAction?.invoke(action) }
        }
        key.repeatAction?.let { action ->
            button.repeatEnabled = true
            button.onRepeatListener = { onLayoutAction?.invoke(action) }
        }
        key.longPressAction?.let { action ->
            button.setOnLongClickListener {
                onLayoutAction?.invoke(action)
                true
            }
        }
        return button
    }

    private fun createConfiguredBottomBar(layout: TextEditingLayout): LinearLayout =
        LinearLayout(viewContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(viewTheme.keyboardColor)
            layout.rows.forEach { row ->
                val rowView = LinearLayout(viewContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    val rowHeight = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0
                    ).apply { weight = 1f }
                    layoutParams = rowHeight
                }
                row.forEach { key ->
                    val weight = key.appearance.percentWidth.coerceAtLeast(0.5f)
                    rowView.addView(
                        createConfiguredButton(key),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                    )
                }
                addView(rowView)
            }
        }

    private fun createDefaultBottomBar(): LinearLayout = LinearLayout(viewContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(viewTheme.keyboardColor)
        addView(backToKeyboardButton, LinearLayout.LayoutParams(0, keyHeight, 1f))
        addView(selectAllButton, LinearLayout.LayoutParams(0, keyHeight, 1f))
        addView(cutButton, LinearLayout.LayoutParams(0, keyHeight, 1f))
        addView(copyButton, LinearLayout.LayoutParams(0, keyHeight, 1f))
        addView(pasteButton, LinearLayout.LayoutParams(0, keyHeight, 1f))
        addView(backspaceButton, LinearLayout.LayoutParams(0, keyHeight, 1f))
    }

    private val bottomBar = configuredLayout?.takeUnless { it.isEmpty }
        ?.let(::createConfiguredBottomBar)
        ?: createDefaultBottomBar()

    override val root: View = LinearLayout(viewContext).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(viewTheme.keyboardColor)
        addView(cursorPad, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(bottomBar, LinearLayout.LayoutParams(-1, keyHeight))
    }

    override fun updateSelection(hasSelection: Boolean, userSelection: Boolean) {
        selectButton.isActivated = hasSelection || userSelection
        if (configuredLayout == null) {
            cutButton.visibility = View.VISIBLE
            cutButton.isEnabled = hasSelection
            cutButton.alpha = if (hasSelection) 1f else 0.45f
        }
    }
}
