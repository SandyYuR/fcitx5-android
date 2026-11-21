/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils

/**
 * 数字输入框（PIN、验证码、手机号这一类）使用哪套键盘布局的解析规则。
 *
 * 优先级固定为：显式偏好（能解析时）→ 当前布局自带的数字层 → 内置数字键盘。
 * 显式偏好解析不出来时退回自动发现：换成套主题、删掉层之后残留的旧值很常见，
 * 让它静默变成「内置数字键盘」比自动选一层更让人费解。
 *
 * 布局里表达数字键盘有两种写法，自动发现两种都认：
 * - **层**：基线（通常是 rime）下面的子布局，如 "rime": { "__layer__:数字": [...] }；
 * - **独立条目**：整个文件里另起一项，如 Sandy 的 "数字": { "default": [...] }。
 *   这一项本身就是布局键（forcedLayoutKey 可以直接用 "数字"），不需要基线前缀。
 *
 * 做成纯逻辑对象是因为它被三处读取，且都不该各自实现一套匹配规则：
 * 运行时 [TextKeyboard.resolveNumericLayoutKey]、设置页的摘要与选择对话框。
 */
internal object NumericLayoutResolver {

    /**
     * 偏好里表示「强制使用内置数字键盘」的哨兵值。
     * 空串表示自动：布局里有数字键盘就用，没有才回落到内置键盘。
     */
    const val BUILTIN = "@builtin"

    /**
     * 数字层/数字布局的层名白名单（已归一化：小写、去掉空白与标点）。
     *
     * 成套主题的层名由作者决定，「数字」「123」「?123」「numpad」都见过；
     * 与其要求作者改名，不如把常见写法都认下来。
     */
    private val NUMERIC_LAYER_TOKENS = setOf(
        "数字", "数字键盘", "数字层", "数字键", "数字输入",
        "123", "1234",
        "number", "numbers", "numeric", "num", "numpad", "keypad", "digits"
    )

    /** 同一基线里出现多个数字层时的取舍顺序；未列出的名字排在最后并按名称排序。 */
    private val TOKEN_PRIORITY = listOf(
        "数字", "数字键盘", "数字层", "数字键", "数字输入",
        "123", "1234", "numpad", "keypad", "number", "numbers", "numeric", "num", "digits"
    )

    /**
     * 自动发现的一个候选项。
     *
     * [standalone] 区分两种写法：true 表示它本身就是一个布局条目（键就是 [base]），
     * false 表示它是某个基线下的数字层（键是 base:layer）。
     */
    private data class Candidate(
        val key: String,
        val base: String,
        val label: String,
        val standalone: Boolean
    )

    /** 层名是否是数字层；接受带 __layer__: 前缀的标签，也接受裸层名。 */
    fun isNumericLayerLabel(label: String): Boolean = isNumericName(label.removePrefix(LayoutJsonUtils.LAYER_SUBMODE_PREFIX))

    /**
     * 解析数字键盘布局键。返回 null 表示用内置数字键盘。
     *
     * preference 是 numeric_layout_override 的原始值：空串=自动，[BUILTIN]=内置，
     * 其他值先按布局键解析，解析失败再走自动发现。
     * preferredBases 是当前输入法对应的基线（uniqueName / displayName / 子模式名），
     * 按优先级排列；自动发现只在多个基线都有数字层时才会用到它们做取舍。
     */
    fun resolve(
        layoutJson: JsonObject,
        preference: String,
        preferredBases: List<String>
    ): String? {
        val explicit = preference.trim()
        when {
            explicit == BUILTIN -> return null
            explicit.isNotEmpty() ->
                resolveExplicit(layoutJson, explicit, preferredBases)?.let { return it }
        }
        return autoDetect(layoutJson, preferredBases)
    }

    /**
     * 从布局条目键（rime、rime:__layer__:数字、数字 这种）做同样的自动发现。
     * 设置页不加载布局 JSON 也能显示「自动会选中哪一项」，并保证与运行时同一套规则。
     */
    fun autoDetect(entryKeys: Collection<String>, preferredBases: List<String>): String? {
        val candidates = entryKeys.mapNotNull { key ->
            val base = key.substringBefore(':')
            val sub = key.substringAfter(':', "")
            when {
                // 独立条目：键本身就是数字布局名，如 Sandy 的「数字」。
                sub.isEmpty() -> if (isNumericName(base)) {
                    Candidate(key, base, base, standalone = true)
                } else null
                // 某个基线下的数字层。
                isNumericLayerLabel(sub) -> Candidate(key, base, sub, standalone = false)
                else -> null
            }
        }
        return select(candidates, preferredBases)
    }

    private fun autoDetect(layoutJson: JsonObject, preferredBases: List<String>): String? {
        val candidates = buildList {
            layoutJson.forEach { (base, element) ->
                if (isNumericName(base) && hasRows(element)) {
                    add(Candidate(base, base, base, standalone = true))
                }
                val container = element as? JsonObject ?: return@forEach
                container.keys
                    .filter { isNumericLayerLabel(it) }
                    .minWithOrNull { a, b -> compareLayerLabels(a, b) }
                    ?.let { add(Candidate("$base:$it", base, it, standalone = false)) }
            }
        }
        return select(candidates, preferredBases)
    }

    /**
     * 选出最终条目键。
     *
     * 顺序是：当前基线自己的数字层 → 独立的数字布局条目 → 其他基线的数字层。
     * 前两者都比「别的基线里的层」更贴近当前布局：前者属于当前方案，后者是作者
     * 专门写的数字键盘；别的基线多半是另一套方案的层，跟着猜容易选错。
     * 其他基线里只有唯一一个候选才采用，多基线并存时宁可交回内置键盘。
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
     * 显式值：完整布局键优先；只写了层名（数字 / __layer__:数字）时补上基线再试。
     * 这样旧版本存下来的裸层名也能继续用，而不是变成一个「不可用」的死值。
     * 裸层名也可能正好是一个独立布局条目（Sandy 的「数字」），containsLayoutKey
     * 对 base 形式会直接命中，不需要再拼基线。
     */
    private fun resolveExplicit(
        layoutJson: JsonObject,
        explicit: String,
        preferredBases: List<String>
    ): String? {
        if (containsLayoutKey(layoutJson, explicit)) return explicit
        val subModes = buildList {
            add(explicit)
            if (!LayoutJsonUtils.isLayerSubModeLabel(explicit)) {
                add(LayoutJsonUtils.toLayerSubModeLabel(explicit))
            }
        }
        (preferredBases + layoutJson.keys).distinct().forEach { base ->
            subModes.forEach { sub ->
                val key = base + ":" + sub
                if (containsLayoutKey(layoutJson, key)) return key
            }
        }
        return null
    }

    /** 与 TextKeyboard 的布局键查找保持一致：base 或 base:sub。 */
    private fun containsLayoutKey(layoutJson: JsonObject, layoutKey: String): Boolean {
        val base = layoutKey.substringBefore(':')
        val element = layoutJson[base] ?: return false
        val sub = layoutKey.substringAfter(':', "")
        if (sub.isEmpty()) return true
        return (element as? JsonObject)?.containsKey(sub) == true
    }

    /**
     * 独立条目是否真的有可渲染的行：数组本身，或带 default / "" / 形态变体的对象。
     * 只有元数据、没有排列的条目不该被自动选中（选它也画不出键盘）。
     */
    private fun hasRows(element: JsonElement?): Boolean = when (element) {
        is JsonArray -> element.isNotEmpty()
        is JsonObject -> (element["default"] as? JsonArray)?.isNotEmpty() == true ||
            (element[""] as? JsonArray)?.isNotEmpty() == true ||
            element.keys.any {
                it.startsWith(LayoutJsonUtils.VARIANT_SUBMODE_PREFIX) && element[it] is JsonArray
            }
        else -> false
    }

    private fun isNumericName(raw: String): Boolean = normalizeLabel(raw) in NUMERIC_LAYER_TOKENS

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

    /**
     * 归一化层名：小写、去掉空白与标点、全角转半角，
     * 让 ?123、#123、１２３ 都落到 123 上。
     */
    private fun normalizeLabel(raw: String): String {
        val sb = StringBuilder(raw.length)
        raw.forEach { ch ->
            when {
                ch in '０'..'９' -> sb.append('0' + (ch - '０'))
                ch in 'Ａ'..'Ｚ' -> sb.append('a' + (ch - 'Ａ'))
                ch in 'ａ'..'ｚ' -> sb.append('a' + (ch - 'ａ'))
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                else -> Unit
            }
        }
        return sb.toString()
    }
}
