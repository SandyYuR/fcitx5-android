/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.content.Context
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams

class TextEditingUi(
    override val ctx: Context,
    private val theme: Theme,
    private val ripple: Boolean,
    private val border: Boolean,
    private val radius: Float
) : Ui {

    private fun textButton(@StringRes id: Int, altStyle: Boolean = false) =
        TextEditingButton(ctx, theme, ripple, border, radius, altStyle).apply {
            setText(id)
        }

    private fun themedIconButton(slot: String, @DrawableRes fallback: Int, altStyle: Boolean = false) =
        TextEditingButton(ctx, theme, ripple, border, radius, altStyle).apply {
            setThemedIcon(slot, fallback)
        }

    val upButton = themedIconButton("keys.cursor_up", R.drawable.ic_baseline_keyboard_arrow_up_24).apply {
        contentDescription = ctx.getString(R.string.move_cursor_up)
    }

    val rightButton = themedIconButton("keys.cursor_right", R.drawable.ic_baseline_keyboard_arrow_right_24).apply {
        contentDescription = ctx.getString(R.string.move_cursor_right)
    }

    val downButton = themedIconButton("keys.cursor_down", R.drawable.ic_baseline_keyboard_arrow_down_24).apply {
        contentDescription = ctx.getString(R.string.move_cursor_down)
    }

    val leftButton = themedIconButton("keys.cursor_left", R.drawable.ic_baseline_keyboard_arrow_left_24).apply {
        contentDescription = ctx.getString(R.string.move_cursor_left)
    }

    val selectButton = textButton(R.string.select).apply {
        enableActivatedState()
    }

    val homeButton = themedIconButton("keys.home", R.drawable.ic_baseline_first_page_24).apply {
        contentDescription = ctx.getString(R.string.move_cursor_to_start)
    }

    val endButton = themedIconButton("keys.end", R.drawable.ic_baseline_last_page_24).apply {
        contentDescription = ctx.getString(R.string.move_cursor_to_end)
    }

    val selectAllButton = textButton(android.R.string.selectAll, altStyle = true)

    val cutButton = textButton(android.R.string.cut, altStyle = true).apply {
        visibility = View.GONE
    }

    val copyButton = textButton(android.R.string.copy, altStyle = true)

    val pasteButton = textButton(android.R.string.paste, altStyle = true)

    val backspaceButton = themedIconButton("keys.backspace", R.drawable.ic_baseline_backspace_24, altStyle = true).apply {
        soundEffect = InputFeedbacks.SoundEffect.Delete
        contentDescription = ctx.getString(R.string.backspace)
    }

    override val root = constraintLayout {
        add(leftButton, lParams {
            topOfParent()
            leftOfParent()
            above(homeButton)
            rightToLeftOf(selectButton)
        })
        add(upButton, lParams {
            topOfParent()
            leftToRightOf(leftButton)
            above(selectButton)
            rightToLeftOf(rightButton)
        })
        add(selectButton, lParams {
            below(upButton)
            leftToRightOf(leftButton)
            above(downButton)
            rightToLeftOf(rightButton)
        })
        add(downButton, lParams {
            below(selectButton)
            leftToRightOf(leftButton)
            above(homeButton)
            rightToLeftOf(rightButton)
        })
        add(rightButton, lParams {
            topOfParent()
            leftToRightOf(selectButton)
            above(endButton)
            rightToLeftOf(copyButton)
        })

        add(homeButton, lParams {
            below(downButton)
            leftOfParent()
            bottomOfParent()
            rightToLeftOf(endButton)
        })
        add(endButton, lParams {
            below(downButton)
            leftToRightOf(homeButton)
            bottomOfParent()
            rightToLeftOf(backspaceButton)
        })

        add(selectAllButton, lParams {
            topOfParent()
            leftToRightOf(rightButton)
            rightOfParent()
            above(cutButton)
            matchConstraintPercentWidth = 0.3f
        })
        add(cutButton, lParams {
            below(selectAllButton)
            leftToRightOf(rightButton)
            rightOfParent()
            above(copyButton)
            matchConstraintPercentWidth = 0.3f
        })
        add(copyButton, lParams {
            below(cutButton)
            leftToRightOf(rightButton)
            rightOfParent()
            above(pasteButton)
            matchConstraintPercentWidth = 0.3f
        })
        add(pasteButton, lParams {
            below(copyButton)
            leftToRightOf(rightButton)
            rightOfParent()
            above(backspaceButton)
            matchConstraintPercentWidth = 0.3f
        })
        add(backspaceButton, lParams {
            below(pasteButton)
            leftToRightOf(rightButton)
            rightOfParent()
            bottomOfParent()
            matchConstraintPercentWidth = 0.3f
        })
    }

    fun updateSelection(hasSelection: Boolean, userSelection: Boolean) {
        selectButton.isActivated = (hasSelection || userSelection)
        if (hasSelection) {
            selectAllButton.apply {
                visibility = View.GONE
            }
            cutButton.apply {
                visibility = View.VISIBLE
            }
        } else {
            selectAllButton.apply {
                visibility = View.VISIBLE
            }
            cutButton.apply {
                visibility = View.GONE
            }
        }
    }

    val clipboardButton = ToolButton(ctx, R.drawable.ic_clipboard, theme).apply {
        contentDescription = ctx.getString(R.string.clipboard)
        applyThemeClipboardIcon(this)
    }

    private fun applyThemeClipboardIcon(button: ToolButton) {
        val iconInfo = IconThemeManager.resolveIconDrawableInfo("toolbar.clipboard")
        if (iconInfo != null) {
            button.setIconFromDrawable(iconInfo.drawable, tintWithTheme = iconInfo.tintWithTheme)
        } else {
            button.setIcon(R.drawable.ic_clipboard)
        }
    }

    fun refreshThemedIcons() {
        upButton.setThemedIcon("keys.cursor_up", R.drawable.ic_baseline_keyboard_arrow_up_24)
        downButton.setThemedIcon("keys.cursor_down", R.drawable.ic_baseline_keyboard_arrow_down_24)
        leftButton.setThemedIcon("keys.cursor_left", R.drawable.ic_baseline_keyboard_arrow_left_24)
        rightButton.setThemedIcon("keys.cursor_right", R.drawable.ic_baseline_keyboard_arrow_right_24)
        homeButton.setThemedIcon("keys.home", R.drawable.ic_baseline_first_page_24)
        endButton.setThemedIcon("keys.end", R.drawable.ic_baseline_last_page_24)
        backspaceButton.setThemedIcon("keys.backspace", R.drawable.ic_baseline_backspace_24)
        applyThemeClipboardIcon(clipboardButton)
    }

    val extension = horizontalLayout {
        add(clipboardButton, lParams(dp(40), dp(40)))
    }
}
