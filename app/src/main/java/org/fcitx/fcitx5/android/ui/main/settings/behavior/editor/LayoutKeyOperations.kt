/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

internal data class EditorKeyPosition(val row: Int, val key: Int)

internal object LayoutKeyOperations {
    /** Destination is an insertion slot before removal, including the slot after the last key. */
    fun move(
        rows: MutableList<MutableList<MutableMap<String, Any?>>>,
        source: EditorKeyPosition,
        target: EditorKeyPosition
    ): EditorKeyPosition? {
        val from = rows.getOrNull(source.row) ?: return null
        val to = rows.getOrNull(target.row) ?: return null
        if (source.key !in from.indices || target.key !in 0..to.size) return null
        val insertion = target.key - if (source.row == target.row && source.key < target.key) 1 else 0
        if (source.row == target.row && source.key == insertion) return source
        val key = from.removeAt(source.key)
        to.add(insertion, key)
        return EditorKeyPosition(target.row, insertion)
    }

    fun setSplitBoundary(row: List<MutableMap<String, Any?>>, after: Int?) {
        row.forEachIndexed { index, key ->
            key.remove("splitAfter")
            if (after == index && index < row.lastIndex) key["splitAfter"] = true
        }
    }
}
