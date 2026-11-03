/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

internal data class EditorKeyPosition(val row: Int, val key: Int)

internal object LayoutKeyOperations {
    private const val SPLIT_ROW_DISABLED = "splitRowDisabled"

    fun isSplitRowDisabled(row: List<Map<String, Any?>>): Boolean =
        row.firstOrNull()?.get(SPLIT_ROW_DISABLED) == true

    /** Keep row metadata on the first key so the setting travels with its row in layout JSON. */
    fun setSplitRowDisabled(row: List<MutableMap<String, Any?>>, disabled: Boolean) {
        row.forEach { it.remove(SPLIT_ROW_DISABLED) }
        if (disabled) row.firstOrNull()?.set(SPLIT_ROW_DISABLED, true)
    }

    fun replaceKey(
        row: MutableList<MutableMap<String, Any?>>,
        index: Int,
        key: MutableMap<String, Any?>
    ): Boolean {
        if (index !in row.indices) return false
        val splitDisabled = isSplitRowDisabled(row)
        row[index] = key
        setSplitRowDisabled(row, splitDisabled)
        return true
    }

    fun removeKey(row: MutableList<MutableMap<String, Any?>>, index: Int): MutableMap<String, Any?>? {
        if (index !in row.indices) return null
        val splitDisabled = isSplitRowDisabled(row)
        val removed = row.removeAt(index)
        setSplitRowDisabled(row, splitDisabled)
        return removed
    }

    /** Destination is an insertion slot before removal, including the slot after the last key. */
    fun move(
        rows: List<MutableList<MutableMap<String, Any?>>>,
        source: EditorKeyPosition,
        target: EditorKeyPosition
    ): EditorKeyPosition? {
        val from = rows.getOrNull(source.row) ?: return null
        val to = rows.getOrNull(target.row) ?: return null
        if (source.key !in from.indices || target.key !in 0..to.size) return null
        val insertion = target.key - if (source.row == target.row && source.key < target.key) 1 else 0
        if (source.row == target.row && source.key == insertion) return source
        val sourceSplitDisabled = isSplitRowDisabled(from)
        val targetSplitDisabled = if (from === to) sourceSplitDisabled else isSplitRowDisabled(to)
        val key = from.removeAt(source.key)
        to.add(insertion, key)
        setSplitRowDisabled(from, sourceSplitDisabled)
        if (from !== to) setSplitRowDisabled(to, targetSplitDisabled)
        return EditorKeyPosition(target.row, insertion)
    }

    fun setSplitBoundary(row: List<MutableMap<String, Any?>>, after: Int?) {
        row.forEachIndexed { index, key ->
            key.remove("splitAfter")
            if (after == index && index < row.lastIndex) key["splitAfter"] = true
        }
    }
}
