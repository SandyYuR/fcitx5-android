/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.fcitx.fcitx5.android.input.keyboard.AuxBarConfig

/** Copies nested macros, display-text maps and compose overrides, not just the outer key. */
internal fun copyKeyData(key: Map<String, Any?>): MutableMap<String, Any?> =
    key.mapValues { (_, value) -> copyLayoutValue(value) }.toMutableMap()

private fun copyLayoutValue(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to copyLayoutValue(v) }.toMutableMap()
    is List<*> -> value.map(::copyLayoutValue).toMutableList()
    else -> value
}

internal data class LayoutDocument(
    val entries: Map<String, List<List<Map<String, Any?>>>>,
    val heights: Map<String, Int>,
    val landscapeHeights: Map<String, Int>,
    val auxConfigs: Map<String, AuxBarConfig?>,
    val auxKeys: Map<String, List<Map<String, Any?>>>
) {
    // A conservative estimate of retained object memory; large custom layouts keep fewer steps.
    val estimatedBytes: Long by lazy {
        estimate(entries) + estimate(heights) + estimate(landscapeHeights) + estimate(auxConfigs) + estimate(auxKeys)
    }

    fun restore(manager: LayoutDataManager) {
        manager.entries.clear()
        manager.entries.putAll(entries.mapValues { (_, rows) ->
            rows.map { row -> row.map(::copyKeyData).toMutableList() }.toMutableList()
        })
        manager.layoutHeightPercentOverrides.apply { clear(); putAll(heights) }
        manager.layoutHeightPercentOverridesLandscape.apply { clear(); putAll(landscapeHeights) }
        manager.layoutAuxBarConfigs.apply { clear(); putAll(auxConfigs) }
        manager.layoutAuxBarKeys.apply {
            clear()
            putAll(auxKeys.mapValues { (_, keys) -> keys.map(::copyKeyData).toMutableList() })
        }
    }

    companion object {
        private fun estimate(value: Any?): Long = when (value) {
            null -> 0L
            is String -> 40L + value.length * 2L
            is Map<*, *> -> 64L + value.entries.sumOf { 48L + estimate(it.key) + estimate(it.value) }
            is List<*> -> 32L + value.sumOf { 8L + estimate(it) }
            else -> 32L
        }

        fun capture(manager: LayoutDataManager) = LayoutDocument(
            manager.entries.mapValues { (_, rows) -> rows.map { row -> row.map(::copyKeyData) } },
            manager.layoutHeightPercentOverrides.toMap(),
            manager.layoutHeightPercentOverridesLandscape.toMap(),
            manager.layoutAuxBarConfigs.toMap(),
            manager.layoutAuxBarKeys.mapValues { (_, keys) -> keys.map(::copyKeyData) }
        )
    }
}

internal data class LayoutEditTarget(val layout: String?, val subMode: String?, val split: Boolean)

/** One record per completed operation. Selection changes never consume an undo step. */
internal class LayoutEditHistory(
    private val limit: Int = 30,
    private val byteLimit: Long = 16L * 1024 * 1024
) {
    data class Entry(val document: LayoutDocument, val target: LayoutEditTarget)
    private val entries = mutableListOf<Entry>()
    private var position = -1
    val canUndo get() = position > 0
    val canRedo get() = position >= 0 && position < entries.lastIndex
    val current get() = entries.getOrNull(position)

    fun reset(document: LayoutDocument, target: LayoutEditTarget) {
        entries.clear()
        entries.add(Entry(document, target))
        position = 0
    }

    fun record(document: LayoutDocument, target: LayoutEditTarget) {
        if (current?.document == document) {
            entries[position] = Entry(current!!.document, target)
            return
        }
        while (entries.lastIndex > position) entries.removeAt(entries.lastIndex)
        entries.add(Entry(document, target))
        var bytes = entries.sumOf { it.document.estimatedBytes }
        while (entries.size > 2 && (entries.size > limit.coerceAtLeast(2) || bytes > byteLimit)) {
            bytes -= entries.removeAt(0).document.estimatedBytes
        }
        position = entries.lastIndex
    }

    fun undo(): Entry? = if (canUndo) entries[--position] else null
    fun redo(): Entry? = if (canRedo) entries[++position] else null
}

/** Keep history across Activity recreation without putting large layouts in a Bundle. */
internal class LayoutEditorHistoryModel : ViewModel() {
    var profile: String? = null
    val history = LayoutEditHistory()
    val draftJson = LayoutDraftJsonCache(viewModelScope)
}
