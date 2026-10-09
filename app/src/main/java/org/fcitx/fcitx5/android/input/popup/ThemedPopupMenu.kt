/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

class ThemedPopupMenu(
    private val context: Context,
    private val theme: Theme,
    private val onDismiss: () -> Unit = {}
) {

    sealed interface Item {
        data class Action(
            val label: CharSequence,
            val onClick: () -> Unit,
            val enabled: Boolean = true,
            val bold: Boolean = false,
        ) : Item

        data object Divider : Item
    }

    private var popup: ListPopupWindow? = null

    fun show(anchor: View, items: List<Item>) {
        dismiss()
        if (items.isEmpty()) return

        val listPopup = ListPopupWindow(context).apply {
            setAdapter(Adapter(context, theme, items))
            anchorView = anchor
            isModal = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            width = context.dp(196).coerceAtMost(
                context.resources.displayMetrics.widthPixels - context.dp(24)
            )
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = context.dp(4f)
                setColor(theme.backgroundColor)
            })
            setOnItemClickListener { _, _, position, _ ->
                val item = items[position] as? Item.Action ?: return@setOnItemClickListener
                if (!item.enabled) return@setOnItemClickListener
                item.onClick()
                dismiss()
            }
            setOnDismissListener {
                if (popup === this) {
                    popup = null
                    onDismiss()
                }
            }
        }
        popup = listPopup
        listPopup.show()
    }

    fun dismiss() {
        popup?.dismiss()
    }

    private class Adapter(
        private val context: Context,
        private val theme: Theme,
        private val items: List<Item>,
    ) : BaseAdapter() {
        private val rowHeight = context.dp(48)
        private val horizontalPadding = context.dp(20)
        private val dividerColor = ColorUtils.setAlphaComponent(
            theme.keyTextColor,
            (Color.alpha(theme.keyTextColor) * 0.24f).toInt().coerceIn(0, 255)
        )
        private val disabledTextColor = ColorUtils.setAlphaComponent(
            theme.keyTextColor,
            (Color.alpha(theme.keyTextColor) * 0.55f).toInt().coerceIn(0, 255)
        )

        override fun getCount() = items.size

        override fun getItem(position: Int) = items[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun isEnabled(position: Int) = items[position] is Item.Action &&
            (items[position] as Item.Action).enabled

        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val item = items[position]) {
                Item.Divider -> View(context).apply {
                    background = ColorDrawable(dividerColor)
                    layoutParams = AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        context.dp(1)
                    )
                }

                is Item.Action -> TextView(context).apply {
                    text = item.label
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(horizontalPadding, 0, horizontalPadding, 0)
                    minHeight = rowHeight
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(if (item.enabled) theme.keyTextColor else disabledTextColor)
                    setTypeface(null, if (item.bold) Typeface.BOLD else Typeface.NORMAL)
                    background = if (item.enabled) {
                        StateListDrawable().apply {
                            addState(
                                intArrayOf(android.R.attr.state_pressed),
                                ColorDrawable(theme.keyPressHighlightColor)
                            )
                            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                        }
                    } else {
                        ColorDrawable(Color.TRANSPARENT)
                    }
                    layoutParams = AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        rowHeight
                    )
                }
            }
        }
    }
}
