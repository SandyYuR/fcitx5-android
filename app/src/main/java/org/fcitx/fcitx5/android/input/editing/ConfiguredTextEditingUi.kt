/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.content.Context
import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.LayoutKeyColorResolver
import org.fcitx.fcitx5.android.input.keyboard.MacroAction
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import kotlin.math.roundToInt
import splitties.dimensions.dp

/** The normal editor uses the entire existing keyboard viewport, including spanning keys. */
class ConfiguredTextEditingUi(
    context: Context,
    private val viewTheme: Theme,
    private val ripple: Boolean,
    private val border: Boolean,
    private val radius: Float,
    private val configuredLayout: TextEditingLayout,
    private val onLayoutAction: ((KeyAction) -> Unit)? = null
) : TextEditingUi(context, viewTheme, ripple, border, radius) {
    data class RenderedKey(val row: Int, val index: Int, val view: TextEditingButton)
    private val keys = configuredLayout.rows.flatten()
    private val selectingKeys = keys.filter { key ->
        (key.tapAction as? MacroAction)?.steps?.any {
            it is MacroStep.Edit && it.action in setOf("toggleSelection", "toggle_selection")
        } == true
    }.toSet()
    val keyViews: List<RenderedKey> = keys.map { key ->
        val appearance = key.appearance
        val button = TextEditingButton(
            context, viewTheme, ripple, border, radius,
            appearance.variant in setOf(KeyDef.Appearance.Variant.Alternative, KeyDef.Appearance.Variant.AltForeground),
            LayoutKeyColorResolver.resolve(context, viewTheme, appearance.backgroundColor, appearance.backgroundColorMonet),
            LayoutKeyColorResolver.resolve(context, viewTheme, appearance.shadowColor, appearance.shadowColorMonet),
            LayoutKeyColorResolver.resolve(context, viewTheme, appearance.textColor, appearance.textColorMonet)
        )
        applyIcon(button, key)
        button.contentDescription = label(key).takeIf { it.isNotBlank() } ?: context.getString(R.string.text_editing)
        button.soundEffect = appearance.soundEffect
        val swipeBehavior = key.swipeBehavior
        val hasSwipeAction = swipeBehavior?.let {
            it.upMacro != null || it.downMacro != null || it.action != null ||
                it.downAction != null || it.legacyMacro != null
        } == true
        button.isEnabled = key.tapAction != null || key.longPressAction != null ||
            key.repeatAction != null || hasSwipeAction
        key.tapAction?.let { action -> button.setOnClickListener { onLayoutAction?.invoke(action) } }
        key.repeatAction?.let { action ->
            button.repeatEnabled = true
            button.onRepeatListener = { onLayoutAction?.invoke(action) }
        }
        key.longPressAction?.let { action ->
            button.setOnLongClickListener { onLayoutAction?.invoke(action); true }
        }
        if (hasSwipeAction && swipeBehavior != null) {
            button.swipeEnabled = true
            button.swipeThresholdY = context.dp(24).toFloat()
            button.onGestureListener = CustomGestureView.OnGestureListener { _, event ->
                if (event.type != CustomGestureView.GestureType.Up || event.cancelled) {
                    return@OnGestureListener false
                }
                val action = selectTextEditingSwipeAction(
                    swipeBehavior,
                    event.totalY,
                    AppPrefs.getInstance().keyboard.swipeSymbolDirection.getValue()
                ) ?: return@OnGestureListener false
                onLayoutAction?.invoke(action)
                true
            }
        }
        if (key in selectingKeys) button.enableActivatedState()
        RenderedKey(key.row, key.index, button)
    }

    private fun label(key: TextEditingLayoutKey): String = when (val a = key.appearance) {
        is KeyDef.Appearance.Text -> a.displayText
        is KeyDef.Appearance.AltText -> a.displayText
        is KeyDef.Appearance.ImageText -> a.displayText
        is KeyDef.Appearance.ImageAltText -> a.altText
        else -> ""
    }

    private fun applyIcon(button: TextEditingButton, key: TextEditingLayoutKey, selecting: Boolean = false) {
        val fallback = when (val a = key.appearance) {
            is KeyDef.Appearance.Image -> a.src
            is KeyDef.Appearance.ImageAltText -> a.src
            is KeyDef.Appearance.ImageText -> a.src
            else -> null
        }
        if (fallback != null) button.setIcon(fallback) else button.setText(label(key))
        val slot = if (selecting && key in selectingKeys) "text_editor.select_locked" else key.keyDef.effectiveIconSlot
        if (slot != null && !button.trySetThemedIcon(slot) && selecting) {
            key.keyDef.effectiveIconSlot?.let { button.trySetThemedIcon(it) }
        }
    }

    override val root: View = object : ViewGroup(context) {
        init {
            setBackgroundColor(viewTheme.keyboardColor)
            keyViews.forEach { addView(it.view) }
        }
        private fun bounds(index: Int): FloatArray {
            val key = keys[index]
            val rowCount = configuredLayout.rows.size.toFloat()
            key.gridCell?.let {
                val cols = configuredLayout.columnCount.toFloat()
                return floatArrayOf(it.column / cols, it.row / rowCount,
                    (it.column + it.columnSpan) / cols, (it.row + it.rowSpan) / rowCount)
            }
            val row = configuredLayout.rows[key.row]
            fun weight(item: TextEditingLayoutKey): Float =
                item.appearance.percentWidth.takeIf { it.isFinite() && it > 0f } ?: 1f
            val sum = row.sumOf { weight(it).toDouble() }.toFloat()
            val before = row.takeWhile { it !== key }.sumOf { weight(it).toDouble() }.toFloat()
            return floatArrayOf(before / sum, key.row / rowCount, (before + weight(key)) / sum, (key.row + 1) / rowCount)
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = resolveSize(context.dp(360), widthMeasureSpec)
            val h = resolveSize(context.dp(200), heightMeasureSpec)
            setMeasuredDimension(w, h)
            keyViews.forEachIndexed { index, item ->
                val b = bounds(index)
                val left = (b[0] * w).roundToInt()
                val top = (b[1] * h).roundToInt()
                item.view.measure(MeasureSpec.makeMeasureSpec(((b[2] * w).roundToInt() - left).coerceAtLeast(0), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(((b[3] * h).roundToInt() - top).coerceAtLeast(0), MeasureSpec.EXACTLY))
            }
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            keyViews.forEachIndexed { index, item ->
                val b = bounds(index)
                item.view.layout((b[0] * width).roundToInt(), (b[1] * height).roundToInt(),
                    (b[2] * width).roundToInt(), (b[3] * height).roundToInt())
            }
        }
    }

    private var selecting = false
    override fun updateSelection(hasSelection: Boolean, userSelection: Boolean) {
        selecting = hasSelection || userSelection
        keys.zip(keyViews).forEach { (key, item) ->
            if (key in selectingKeys) {
                applyIcon(item.view, key, selecting)
                item.view.isActivated = selecting
            }
        }
    }
    override fun refreshThemedIcons() {
        super.refreshThemedIcons()
        keys.zip(keyViews).forEach { (key, item) -> applyIcon(item.view, key, selecting) }
    }
}

internal fun selectTextEditingSwipeAction(
    behavior: KeyDef.Behavior.Swipe,
    totalY: Int,
    direction: SwipeSymbolDirection
): KeyAction? {
    if (totalY == 0) return null
    val macro = if (behavior.overrideDefaults) {
        if (totalY < 0) behavior.upMacro else behavior.downMacro
    } else {
        when (direction) {
            SwipeSymbolDirection.Disabled -> null
            SwipeSymbolDirection.Up -> behavior.upMacro?.takeIf { totalY < 0 }
            SwipeSymbolDirection.Down -> behavior.downMacro?.takeIf { totalY > 0 }
            SwipeSymbolDirection.Auto -> if (totalY < 0) behavior.upMacro else behavior.downMacro
        }
    }
    if (macro != null) return macro
    if (!direction.checkY(totalY)) return null
    return behavior.action ?: behavior.downAction ?: behavior.legacyMacro
}
