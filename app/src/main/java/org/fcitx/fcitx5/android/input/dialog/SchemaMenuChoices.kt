/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.dialog

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.radiobutton.MaterialRadioButton
import splitties.dimensions.dp

/** AlertDialog constrains this viewport; every schema remains reachable by scrolling. */
internal fun buildSchemaMenuChoices(
    context: Context,
    entries: List<Pair<String, Int>>,
    enabledIndex: Int,
    onSelect: (Int) -> Unit
): ScrollView {
    val choices = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        minimumWidth = context.dp(280)
        setPadding(context.dp(24), 0, context.dp(24), context.dp(24))
    }
    entries.forEachIndexed { index, (label, actionId) ->
        choices.addView(MaterialRadioButton(context).apply {
            text = label
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            ellipsize = null
            isChecked = index == enabledIndex
            setUseMaterialThemeColors(true)
            minHeight = context.dp(48)
            minimumHeight = context.dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(8), 0, context.dp(8))
            setOnClickListener { onSelect(actionId) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    return ScrollView(context).apply {
        isFillViewport = false
        addView(choices, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
