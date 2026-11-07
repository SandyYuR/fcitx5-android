/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.data.stats.StatsKeyGeometry
import org.fcitx.fcitx5.android.data.stats.StatsLayout
import java.util.Locale

/** Converts already laid-out keycaps into an immutable snapshot, never on the per-key input path. */
internal object KeyboardStatisticsLayout {
    fun label(definition: KeyDef): String? {
        if (definition is PlaceholderKey || definition.behaviors.isEmpty()) return null
        val raw = when (definition) {
            is AlphabetKey -> definition.character
            is SpaceKey, is MiniSpaceKey -> "Space"
            is BackspaceKey -> "Backspace"
            is ReturnKey -> "Enter"
            is CapsKey -> "Shift"
            is LanguageKey -> "Language"
            is LayoutSwitchKey, is ImageLayoutSwitchKey -> "Layout"
            is CommaKey -> ","
            is SymbolKey -> "."
            is MacroKey -> definition.displayText?.takeIf(String::isNotBlank) ?: definition.label
            else -> (definition.appearance as? KeyDef.Appearance.Text)?.displayText
                ?: definition.iconSlot ?: "Key"
        }
        // Record the visible key name, never a macro's expanded text or clipboard payload.
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 32 || trimmed.any(Char::isISOControl)) return null
        return if (trimmed.length == 1) trimmed.lowercase(Locale.ROOT) else trimmed
    }

    fun capture(root: ViewGroup, keys: Collection<KeyView>): StatsLayout {
        if (!root.isAttachedToWindow || root.width <= 0 || root.height <= 0) return StatsLayout()
        val location = IntArray(2).also(root::getLocationInWindow)
        val density = root.resources.displayMetrics.density
        val grouped = keys.filter { it.statisticsKeyLabel != null && it.isShown && it.isEnabled && belongsTo(it, root) }
            .groupBy { it.parent as View }
        val rowLocation = IntArray(2)
        val rows = grouped.map { (row, children) ->
            row.getLocationInWindow(rowLocation)
            val y = ((rowLocation[1] - location[1]) / density).coerceAtLeast(0f)
            children.mapNotNull { key ->
                key.updateBounds()
                val bounds = Rect(key.bounds).apply {
                    offset(-location[0], -location[1])
                    inset(key.hMargin, key.vMargin)
                }
                if (!bounds.intersect(0, 0, root.width, root.height) || bounds.isEmpty) null else
                    StatsKeyGeometry(key.statisticsKeyLabel!!, bounds.left.toFloat() / root.width, y,
                        bounds.width().toFloat() / root.width, bounds.height() / density)
            }.sortedBy { it.x }
        }.filter(List<StatsKeyGeometry>::isNotEmpty).sortedBy { it.first().y }
        var remaining = 128
        val bounded = rows.take(32).mapNotNull { row ->
            row.take(remaining).also { remaining -= it.size }.takeIf(List<StatsKeyGeometry>::isNotEmpty)
        }
        return StatsLayout(bounded.map { row -> row.map { it.label } }, bounded.flatten())
    }

    private fun belongsTo(view: View, root: View): Boolean {
        var parent = view.parent
        while (parent is View) { if (parent === root) return true; parent = parent.parent }
        return false
    }
}
