/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import splitties.dimensions.dp

/** Full-width preview and properties share a scroll surface instead of competing for height. */
internal class KeyboardEditorWorkspace(
    context: Context,
    selectors: View,
    actions: View,
    preview: View,
    properties: View
) : NestedScrollView(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        listOf(selectors, actions, preview, properties).forEach {
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    init {
        isFillViewport = true
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun revealProperties(properties: View) {
        post {
            if (!properties.isShown) return@post
            // Reveal the heading and first fields without jumping to the end of a long form.
            val rect = Rect(0, 0, properties.width, minOf(properties.height, context.dp(240)))
            content.offsetDescendantRectToMyCoords(properties, rect)
            requestChildRectangleOnScreen(content, rect, false)
        }
    }
}
