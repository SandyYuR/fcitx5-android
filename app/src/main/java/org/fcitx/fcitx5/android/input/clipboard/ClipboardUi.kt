/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ViewAnimator
import androidx.core.view.doOnLayout
import androidx.core.graphics.ColorUtils
import androidx.transition.Fade
import androidx.transition.TransitionManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardCategory
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.gravityCenter
import splitties.views.setPaddingDp
import timber.log.Timber

class ClipboardUi(override val ctx: Context, private val theme: Theme, private val searchMode: Boolean = false,
                  private val palette: ClipboardColors = if (searchMode) ClipboardColors.panel(ctx) else ClipboardColors.keyboard(theme)) : Ui {

    val recyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
    }

    val enableUi = ClipboardInstructionUi.Enable(ctx, theme, palette)

    val emptyUi = ClipboardInstructionUi.Empty(ctx, theme, palette)

    private val noResults = textView {
        text = ctx.getString(R.string.clipboard_search_no_results)
        textSize = 14f
        gravity = gravityCenter
        setTextColor(palette.muted)
    }

    val viewAnimator = view(::ViewAnimator) {
        add(recyclerView, lParams(matchParent, matchParent))
        add(if (searchMode) noResults else emptyUi.root, lParams(matchParent, matchParent))
        add(enableUi.root, lParams(matchParent, matchParent))
    }

    private val categoryButtons = linkedMapOf(
        ClipboardCategory.All to createCategoryButton(R.string.clipboard_category_all),
        ClipboardCategory.Favorites to createCategoryButton(R.string.clipboard_category_favorites),
        ClipboardCategory.Local to createCategoryButton(R.string.clipboard_category_local),
        ClipboardCategory.Media to createCategoryButton(R.string.clipboard_category_media),
        ClipboardCategory.Remote to createCategoryButton(R.string.clipboard_category_remote)
    )

    val categoryBar: ViewGroup = if (searchMode) ChipGroup(ctx).apply {
        isSingleLine = false
        isSingleSelection = true
        isSelectionRequired = true
        setChipSpacingHorizontal(dp(4))
        setChipSpacingVertical(dp(0))
        setPaddingDp(4, 4, 4, 4)
        clipChildren = true
        clipToPadding = true
        categoryButtons.values.forEach { addView(it, ViewGroup.MarginLayoutParams(wrapContent, wrapContent)) }
    } else view(::HorizontalScrollView) {
        isHorizontalScrollBarEnabled = false
        add(horizontalLayout {
            setPaddingDp(8, 8, 8, 4)
            categoryButtons.entries.forEachIndexed { index, (_, button) ->
                add(
                    button,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(30)
                    ).apply { if (index > 0) marginStart = dp(6) }
                )
            }
        }, lParams(wrapContent, matchParent))
    }

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    override val root = coordinatorLayout {
        if (!keyBorder && !searchMode) {
            backgroundColor = palette.surface
        }
        add(verticalLayout {
            if (!searchMode) add(categoryBar, LinearLayout.LayoutParams(matchParent, LinearLayout.LayoutParams.WRAP_CONTENT))
            add(
                viewAnimator,
                LinearLayout.LayoutParams(matchParent, 0).apply { weight = 1f }
            )
        }, defaultLParams(matchParent, matchParent))
    }

    val deleteAllButton = ToolButton(ctx, R.drawable.ic_baseline_delete_sweep_24, theme).apply {
        contentDescription = ctx.getString(R.string.delete_all)
    }

    val searchButton = ToolButton(ctx, R.drawable.ic_clipboard_history_search_24, theme).apply {
        contentDescription = ctx.getString(R.string.search)
    }

    val extension = horizontalLayout {
        add(deleteAllButton, lParams(dp(40), dp(40)))
        add(searchButton, lParams(dp(40), dp(40)))
    }

    private fun setDeleteButtonShown(enabled: Boolean) {
        deleteAllButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        searchButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    private fun createCategoryButton(textRes: Int): android.widget.TextView = if (searchMode) Chip(ctx).apply {
        id = View.generateViewId()
        text = ctx.getString(textRes)
        textSize = 13f
        isCheckable = true
        isCheckedIconVisible = false
        setEnsureMinTouchTargetSize(true)
        chipMinHeight = dp(36).toFloat()
        chipStartPadding = dp(8).toFloat(); chipEndPadding = dp(8).toFloat()
        textStartPadding = dp(4).toFloat(); textEndPadding = dp(4).toFloat()
        chipStrokeWidth = dp(1).toFloat()
        chipStrokeColor = ColorStateList.valueOf(palette.outline)
        chipBackgroundColor = ColorStateList.valueOf(palette.category)
        rippleColor = ColorStateList.valueOf(palette.ripple)
        setTextColor(palette.text)
    } else textView {
        gravity = gravityCenter
        minWidth = dp(64)
        text = ctx.getString(textRes)
        textSize = 13f
        setPaddingDp(12, 0, 12, 0)
        background = categoryBackground(selected = false)
        setTextColor(palette.text)
    }

    private fun categoryBackground(selected: Boolean) = RippleDrawable(
        ColorStateList.valueOf(palette.ripple),
        GradientDrawable().apply {
            cornerRadius = ctx.dp(15).toFloat()
            setColor(if (selected) palette.primary else palette.category)
        },
        GradientDrawable().apply {
            cornerRadius = ctx.dp(15).toFloat()
            setColor(Color.WHITE)
        }
    )

    fun setOnCategorySelectedListener(listener: (ClipboardCategory) -> Unit) {
        categoryButtons.forEach { (category, button) ->
            button.setOnClickListener {
                setSelectedCategory(category)
                listener(category)
            }
        }
    }

    fun setSelectedCategory(category: ClipboardCategory) {
        (categoryBar as? ChipGroup)?.check(categoryButtons.getValue(category).id)
        categoryButtons.forEach { (buttonCategory, button) ->
            val selected = buttonCategory == category
            button.isSelected = selected
            if (button is Chip) {
                button.chipBackgroundColor = ColorStateList.valueOf(if (selected) palette.primary else palette.category)
                button.chipStrokeWidth = if (selected) 0f else ctx.dp(1).toFloat()
                button.rippleColor = ColorStateList.valueOf(if (selected)
                    ColorUtils.setAlphaComponent(palette.onPrimary, 0x30) else palette.ripple)
            } else button.background = categoryBackground(selected)
            button.setTextColor(if (selected) palette.onPrimary else palette.text)
            if (selected && !searchMode) button.doOnLayout {
                if (button.isSelected) button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
            }
        }
    }

    fun switchUiByState(state: ClipboardStateMachine.State) {
        Timber.d("Switch clipboard to $state")
        if (!disableAnimation)
            TransitionManager.beginDelayedTransition(root, Fade().apply { duration = 100L })
        when (state) {
            ClipboardStateMachine.State.Normal -> {
                viewAnimator.displayedChild = 0
                setDeleteButtonShown(true)
            }

            ClipboardStateMachine.State.AddMore -> {
                viewAnimator.displayedChild = 1
                setDeleteButtonShown(false)
            }

            ClipboardStateMachine.State.EnableListening -> {
                viewAnimator.displayedChild = 2
                setDeleteButtonShown(false)
            }
        }
    }
}
