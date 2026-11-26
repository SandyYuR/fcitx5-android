/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils

/**
 * 解析常规文本编辑完整面板所用的当前配置布局。
 *
 * 有效显式偏好优先；空值或失效值自动发现，仍无匹配时使用内置常规面板。
 * 自动发现支持独立编辑布局条目和基线下的编辑层。保留名 [TEXT_EDITOR_LAYOUT]
 * 是 Foxy 滑动风格独立底栏，不参与自动发现；用户仍可显式选择它。
 *
 * 运行时加载器、设置摘要与选择对话框、键盘预览共用此匹配规则。
 */
internal object TextEditingLayoutResolver {

    /** 偏好里表示「使用内置常规完整面板」的哨兵值；空串表示自动。 */
    const val BUILTIN = "@builtin"

    /** Foxy 滑动风格独立底栏的布局键，保留兼容且不参与自动发现。 */
    const val TEXT_EDITOR_LAYOUT = "text_editor"

    /**
     * 文本编辑层/文本编辑布局的层名白名单（已归一化：小写、去掉空白与标点）。
     * 编辑器的两种写法在成套主题里都见过，与其要求作者改名，不如把常见写法都认下来。
     */
    private val EDITOR_LAYER_TOKENS = setOf(
        "editor", "texteditor", "textedit",
        "编辑", "文本编辑", "编辑键盘", "文本编辑布局", "编辑布局"
    )

    /** 同一基线里出现多个编辑层时的取舍顺序；未列出的名字排在最后并按名称排序。 */
    private val TOKEN_PRIORITY = listOf(
        "editor", "文本编辑", "编辑键盘", "编辑", "文本编辑布局", "编辑布局", "texteditor", "textedit"
    )

    /** 自动发现的一个候选项。[standalone] 表示它本身就是布局条目，否则是基线下的编辑层。 */
    private data class Candidate(
        val key: String,
        val base: String,
        val label: String,
        val standalone: Boolean
    )

    /** 层名是否是编辑层；接受带 __layer__: 前缀的标签，也接受裸层名。 */
    fun isEditorLayerLabel(label: String): Boolean =
        isEditorName(label.removePrefix(LayoutJsonUtils.LAYER_SUBMODE_PREFIX))

    /**
     * 解析常规文本编辑面板布局键。返回 null 表示使用内置常规完整面板。
     *
     * preference 是 text_editing_layout_override 的原始值：空串=自动，[BUILTIN]=内置，
     * 其他值先按布局键解析，解析失败再走自动发现。
     */
    fun resolve(
        layoutJson: JsonObject,
        preference: String,
        preferredBases: List<String>,
        isUsable: (String) -> Boolean = { true }
    ): String? {
        val explicit = preference.trim()
        when {
            explicit == BUILTIN -> return null
            explicit.isNotEmpty() ->
                resolveExplicit(layoutJson, explicit, preferredBases, isUsable)?.let { return it }
        }
        return autoDetect(layoutJson, preferredBases, isUsable)
    }

    /** 从布局条目键做同样的自动发现，供设置页显示「自动会选中哪一项」。 */
    fun autoDetect(entryKeys: Collection<String>, preferredBases: List<String>): String? {
        val candidates = entryKeys.mapNotNull { key ->
            val base = key.substringBefore(':')
            if (isLegacyTextEditorName(base)) return@mapNotNull null
            val sub = key.substringAfter(':', "")
            when {
                sub.isEmpty() -> if (isEditorName(base))
                    Candidate(key, base, base, standalone = true) else null
                isEditorLayerLabel(sub) -> Candidate(key, base, sub, standalone = false)
                else -> null
            }
        }
        return select(candidates, preferredBases)
    }

    private fun autoDetect(
        layoutJson: JsonObject,
        preferredBases: List<String>,
        isUsable: (String) -> Boolean
    ): String? {
        val candidates = buildList {
            layoutJson.forEach { (base, element) ->
                if (isLegacyTextEditorName(base)) return@forEach
                if (isEditorName(base) && hasRows(element) && isUsable(base)) {
                    add(Candidate(base, base, base, standalone = true))
                }
                val container = element as? JsonObject ?: return@forEach
                container.keys
                    .filter {
                        isEditorLayerLabel(it) && hasRows(container[it]) && isUsable("$base:$it")
                    }
                    .minWithOrNull { a, b -> compareLayerLabels(a, b) }
                    ?.let { add(Candidate(base + ":" + it, base, it, standalone = false)) }
            }
        }
        return select(candidates, preferredBases)
    }

    /**
     * 选出最终条目键：当前基线自己的编辑层 → 独立的编辑布局条目 → 其他基线里唯一的编辑层。
     * 其他基线里多于一个候选时宁可交回内置常规面板，跟着别的方案猜容易选错。
     */
    private fun select(candidates: List<Candidate>, preferredBases: List<String>): String? {
        preferredBases.forEach { base ->
            candidates
                .filter { !it.standalone && it.base == base }
                .minWithOrNull { a, b -> compareLayerLabels(a.label, b.label) }
                ?.let { return it.key }
        }
        candidates
            .filter { it.standalone }
            .sortedWith(compareBy({ priorityOf(it.label) }, { it.key }))
            .firstOrNull()
            ?.let { return it.key }
        return candidates.filter { !it.standalone }.map { it.key }.distinct().singleOrNull()
    }

    /**
     * 显式值：完整布局键优先；只写了层名（editor / __layer__:editor）时补上基线再试。
     * 这样旧版本存下来的裸层名也能继续用，而不是变成一个「不可用」的死值。
     */
    private fun resolveExplicit(
        layoutJson: JsonObject,
        explicit: String,
        preferredBases: List<String>,
        isUsable: (String) -> Boolean
    ): String? {
        if (containsLayoutKey(layoutJson, explicit) && isUsable(explicit)) return explicit
        val subModes = buildList {
            add(explicit)
            if (!LayoutJsonUtils.isLayerSubModeLabel(explicit)) {
                add(LayoutJsonUtils.toLayerSubModeLabel(explicit))
            }
        }
        (preferredBases + layoutJson.keys).distinct().forEach { base ->
            subModes.forEach { sub ->
                val key = base + ":" + sub
                if (containsLayoutKey(layoutJson, key) && isUsable(key)) return key
            }
        }
        return null
    }

    /** 与布局键查找保持一致：base 或 base:sub。 */
    private fun containsLayoutKey(layoutJson: JsonObject, layoutKey: String): Boolean {
        val base = layoutKey.substringBefore(':')
        val element = layoutJson[base] ?: return false
        val sub = layoutKey.substringAfter(':', "")
        if (sub.isEmpty()) return hasRows(element)
        return (element as? JsonObject)?.get(sub)?.let(::hasRows) == true
    }

    /** 条目是否真的有可渲染的行：数组本身，或带 default / 空串子项的对象。 */
    internal fun hasRows(element: JsonElement?): Boolean = when (element) {
        is JsonArray -> element.isNotEmpty()
        is JsonObject -> (element["default"] as? JsonArray)?.isNotEmpty() == true ||
            (element[""] as? JsonArray)?.isNotEmpty() == true
        else -> false
    }

    private fun isEditorName(raw: String): Boolean =
        !isLegacyTextEditorName(raw) && normalizeLabel(raw) in EDITOR_LAYER_TOKENS

    private fun isLegacyTextEditorName(raw: String): Boolean =
        normalizeLabel(raw) == normalizeLabel(TEXT_EDITOR_LAYOUT)

    private fun compareLayerLabels(a: String, b: String): Int {
        val byPriority = priorityOf(a).compareTo(priorityOf(b))
        return if (byPriority != 0) byPriority else bareLabel(a).compareTo(bareLabel(b))
    }

    private fun priorityOf(label: String): Int {
        val index = TOKEN_PRIORITY.indexOf(normalizeLabel(bareLabel(label)))
        return if (index >= 0) index else TOKEN_PRIORITY.size
    }

    private fun bareLabel(label: String): String =
        label.removePrefix(LayoutJsonUtils.LAYER_SUBMODE_PREFIX)

    /** 归一化层名：小写、去掉空白与标点、全角转半角。 */
    private fun normalizeLabel(raw: String): String {
        val sb = StringBuilder(raw.length)
        raw.forEach { ch ->
            when {
                ch in '\uFF10'..'\uFF19' -> sb.append('0' + (ch - '\uFF10'))
                ch in '\uFF21'..'\uFF3A' -> sb.append('a' + (ch - '\uFF21'))
                ch in '\uFF41'..'\uFF5A' -> sb.append('a' + (ch - '\uFF41'))
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                else -> Unit
            }
        }
        return sb.toString()
    }
}
