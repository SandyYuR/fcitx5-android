/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.settingsColor

/** Presentation only: the caller owns the operation, its lifetime and any retry. */
class OperationStatusView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    MaterialCardView(context, attrs) {
    enum class State { IDLE, WORKING, SUCCESS, ERROR }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    val heading = MaterialTextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val detail = MaterialTextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextIsSelectable(true)
    }
    private val detailsButton = MaterialButton(context).apply {
        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        strokeWidth = 0
        elevation = 0f
        stateListAnimator = null
        setText(R.string.ui_operation_details)
        minHeight = dp(48)
        isAllCaps = false
        setOnClickListener {
            detail.visibility = View.VISIBLE
            visibility = View.GONE
        }
    }
    private val progress = LinearProgressIndicator(context).apply { isIndeterminate = true }

    init {
        radius = dp(20).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        addView(content, LayoutParams(-1, -2))
        content.addView(heading, LinearLayout.LayoutParams(-1, -2))
        content.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(detailsButton, LinearLayout.LayoutParams(-2, -2))
        content.addView(progress, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        show("", "")
    }

    fun show(title: CharSequence, message: CharSequence = "", state: State = State.IDLE) {
        heading.text = title
        detail.text = message
        val error = state == State.ERROR
        val surface = if (error) com.google.android.material.R.attr.colorErrorContainer
            else com.google.android.material.R.attr.colorSurfaceContainerHigh
        val foreground = if (error) com.google.android.material.R.attr.colorOnErrorContainer
            else com.google.android.material.R.attr.colorOnSurface
        setCardBackgroundColor(context.settingsColor(surface))
        heading.setTextColor(context.settingsColor(foreground))
        detail.setTextColor(context.settingsColor(foreground))
        detailsButton.setTextColor(context.settingsColor(foreground))
        detail.visibility = if (message.isNotEmpty() && !error) View.VISIBLE else View.GONE
        detailsButton.visibility = if (message.isNotEmpty() && error) View.VISIBLE else View.GONE
        progress.visibility = if (state == State.WORKING) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
