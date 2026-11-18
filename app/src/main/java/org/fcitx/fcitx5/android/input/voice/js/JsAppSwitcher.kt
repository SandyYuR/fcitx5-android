/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.TooltipCompat
import com.google.android.material.textview.MaterialTextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.settingsColor
import kotlin.math.roundToInt

internal data class JsAppPanelEntry(val target: JsAppButtonBinding.Target, val label: String)

/** Host controls live outside the plugin's event and value namespace. */
internal class JsAppSwitcher(
    context: Context,
    private val entries: List<JsAppPanelEntry>,
    private val availableArea: () -> Rect,
    private val open: (JsAppButtonBinding.Target) -> Unit,
) : LinearLayout(context) {
    private val ink = context.settingsColor(com.google.android.material.R.attr.colorOnSurface)
    private val surface = context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    private val buttonSize = dp(48)
    private var popup: ListPopupWindow? = null
    private var directCount = -1
    private val direct = entries.take(3).map { entry ->
        button(entry.target.icon, context.getString(R.string.js_app_switch_to, entry.label)) {
            dismissMenu()
            open(entry.target)
        }
    }
    private val overflow = button(R.drawable.ic_baseline_more_horiz_24,
        context.getString(R.string.js_app_switch_tools), ::showMenu)

    init {
        gravity = Gravity.CENTER_VERTICAL
        direct.forEach { addView(it, LayoutParams(buttonSize, buttonSize)) }
        addView(overflow, LayoutParams(buttonSize, buttonSize))
        fitWidth(buttonSize)
    }

    fun fitWidth(width: Int) {
        val slots = (width / buttonSize).coerceIn(1, direct.size)
        val count = if (entries.size <= slots) entries.size else slots - 1
        if (directCount == count) return
        dismissMenu()
        directCount = count
        direct.forEachIndexed { index, view -> view.visibility = if (index < count) VISIBLE else GONE }
        overflow.visibility = if (count < entries.size) VISIBLE else GONE
    }

    private fun button(icon: Int, label: String, click: () -> Unit) = ImageButton(context).apply {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        try {
            background = attrs.getDrawable(0)
        } finally {
            attrs.recycle()
        }
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(ink)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = label
        TooltipCompat.setTooltipText(this, label)
        setOnClickListener { click() }
    }

    private fun showMenu() {
        if (popup?.isShowing == true) { dismissMenu(); return }
        val anchor = parent as? View ?: return
        if (!anchor.isAttachedToWindow || anchor.width <= 0) return
        val remaining = entries.drop(directCount)
        if (remaining.isEmpty()) return
        val adapter = object : ArrayAdapter<JsAppPanelEntry>(context, 0, remaining) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val entry = getItem(position)!!
                return (convertView as? MaterialTextView ?: MaterialTextView(context).apply {
                    minimumHeight = buttonSize
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setTextColor(ink)
                    compoundDrawablePadding = dp(12)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }).apply {
                    text = entry.label
                    val icon = AppCompatResources.getDrawable(context, entry.target.icon)?.mutate()?.apply {
                        setTint(ink)
                        setBounds(0, 0, dp(24), dp(24))
                    }
                    setCompoundDrawablesRelative(icon, null, null, null)
                }
            }
        }
        val area = availableArea()
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val above = location[1] - area.top
        val below = area.bottom - location[1] - anchor.height
        val desired = minOf(remaining.size * buttonSize, dp(256))
        val showAbove = below < desired && above > below
        val popupHeight = minOf(desired, if (showAbove) above else below)
        if (popupHeight <= 0) return
        popup = ListPopupWindow(context).apply {
            anchorView = anchor
            isModal = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setWindowLayoutType(IME_PANEL_DROPDOWN_WINDOW_TYPE)
            width = anchor.width
            height = popupHeight
            if (showAbove) verticalOffset = -anchor.height - popupHeight
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(12).toFloat()
            })
            setAdapter(adapter)
            setOnItemClickListener { _, _, position, _ ->
                dismiss()
                open(remaining[position].target)
            }
            setOnDismissListener { popup = null }
        }.also { it.show() }
    }

    fun dismissMenu(): Boolean {
        val menu = popup?.takeIf { it.isShowing } ?: return false
        menu.dismiss()
        return true
    }

    override fun onDetachedFromWindow() {
        dismissMenu()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
