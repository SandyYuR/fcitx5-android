/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.PopupWindow
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.font.FontProviders
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 候选词上滑选字弹窗：把候选词拆成的单字排成网格，手指滑到哪个字就高亮哪个，
 * 抬手提交；抬手位置在弹窗外则取消。
 *
 * 上游 boomker/fcitx5-android `7085b3f0` 的移植。本项目只移植“上滑选字”，
 * 长按菜单、词频重置等逻辑保持不变。
 *
 * 命中测试保持在窗口坐标下进行（`getLocationInWindow` + `showAtLocation` 同一套
 * 坐标系），改动弹窗几何时不要换成屏幕坐标。
 */
internal class CandidateCharacterPopup(
    private val anchor: View,
    private val characters: List<String>,
    private val theme: Theme
) {
    private val density = anchor.resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).roundToInt()

    private val grid = candidateCharacterGrid(characters.size)
    private val rowCount = grid.rows
    private val columnCount = grid.columns
    private val padding = dp(4f)
    private val cellSize = min(
        dp(48f),
        max(1, (anchor.resources.displayMetrics.widthPixels - padding * 2) / columnCount)
    )
    private val popupWidth = cellSize * columnCount + padding * 2
    private val popupHeight = cellSize * rowCount + padding * 2

    private val inactiveBackground = GradientDrawable().apply {
        cornerRadius = dp(8f).toFloat()
        setColor(theme.popupBackgroundColor)
    }
    private val focusedBackground = GradientDrawable().apply {
        cornerRadius = dp(6f).toFloat()
        setColor(theme.genericActiveBackgroundColor)
    }

    private val characterViews = arrayOfNulls<AutoScaleTextView>(characters.size)
    private var focusedIndex: Int? = null
    private var popupLeft = 0
    private var popupTop = 0

    private val content = GridLayout(anchor.context).apply {
        rowCount = this@CandidateCharacterPopup.rowCount
        columnCount = this@CandidateCharacterPopup.columnCount
        setPadding(padding, padding, padding, padding)
        background = inactiveBackground
        elevation = dp(4f).toFloat()

        for (visualRow in 0 until rowCount) {
            for (column in 0 until columnCount) {
                val characterIndex = (rowCount - visualRow - 1) * columnCount + column
                val cell = AutoScaleTextView(context).apply {
                    gravity = Gravity.CENTER
                    scaleMode = AutoScaleTextView.Mode.Proportional
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
                    setTextColor(theme.popupTextColor)
                    typeface = FontProviders.resolveTypeface("popup_key_font", typeface)
                    text = characters.getOrNull(characterIndex).orEmpty()
                }
                addView(cell, GridLayout.LayoutParams(
                    GridLayout.spec(visualRow),
                    GridLayout.spec(column)
                ).apply {
                    width = cellSize
                    height = cellSize
                })
                if (characterIndex < characters.size) {
                    characterViews[characterIndex] = cell
                }
            }
        }
    }

    private val window = PopupWindow(content, popupWidth, popupHeight, false).apply {
        isTouchable = false
        isOutsideTouchable = false
        isClippingEnabled = true
        animationStyle = 0
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    val isShowing: Boolean
        get() = window.isShowing

    fun show() {
        if (characters.isEmpty() || !anchor.isAttachedToWindow) return
        val location = IntArray(2).also(anchor::getLocationInWindow)
        val root = anchor.rootView
        popupLeft = (location[0] + (anchor.width - popupWidth) / 2)
            .coerceIn(0, max(0, root.width - popupWidth))
        popupTop = max(0, location[1] - popupHeight)
        window.showAtLocation(anchor, Gravity.TOP or Gravity.START, popupLeft, popupTop)
    }

    fun updateFocus(anchorX: Float, anchorY: Float) {
        if (!isShowing) return
        val location = IntArray(2).also(anchor::getLocationInWindow)
        val popupX = location[0] + anchorX - popupLeft
        val popupY = location[1] + anchorY - popupTop
        if (!isInsideCandidateCharacterPopup(popupX, popupY, popupWidth, popupHeight)) {
            clearFocus()
            return
        }

        val localX = popupX - padding
        val localY = popupY - padding
        val column = (localX / cellSize).toInt().coerceIn(0, columnCount - 1)
        val visualRow = (localY / cellSize).toInt().coerceIn(0, rowCount - 1)
        val target = nearestCharacterIndex(visualRow, column)
        if (target == focusedIndex) return
        clearFocus()
        focusedIndex = target
        markFocused(target)
    }

    fun selectedCharacter(): String? = focusedIndex?.let(characters::getOrNull)

    fun dismiss() {
        window.dismiss()
    }

    private fun nearestCharacterIndex(visualRow: Int, column: Int): Int =
        characters.indices.minBy { index ->
            val row = rowCount - index / columnCount - 1
            val col = index % columnCount
            val rowDelta = row - visualRow
            val colDelta = col - column
            rowDelta * rowDelta + colDelta * colDelta
        }

    private fun clearFocus() {
        val index = focusedIndex ?: return
        markInactive(index)
        focusedIndex = null
    }

    private fun markFocused(index: Int) {
        characterViews.getOrNull(index)?.apply {
            background = focusedBackground
            setTextColor(theme.genericActiveForegroundColor)
        }
    }

    private fun markInactive(index: Int) {
        characterViews.getOrNull(index)?.apply {
            background = null
            setTextColor(theme.popupTextColor)
        }
    }
}
