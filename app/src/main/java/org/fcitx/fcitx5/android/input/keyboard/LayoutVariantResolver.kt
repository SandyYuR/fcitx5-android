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
 * 从布局 JSON 的某一层里挑出「当前排列形态」对应的那份行集合。
 *
 * 抽成独立对象是为了能单测：这段优先级决定用户在分体下看到哪套按键，而它读的是磁盘上的
 * JSON 结构（[TextKeyboard] 在构建键盘的过程中调用），出错的代价是"键盘排布不对"这种
 * 只靠肉眼很难定位的问题。
 *
 * ## 排列挂在**子布局**上，不是挂在布局上
 *
 * 分体排列属于某一个具体的子布局（方案、层、或布局本体的「默认」那份），而不是整个布局
 * 共用一份。一层里的结构因此是"每个子布局内部可以再带一份分体排列"：
 *
 * ```json
 * "rime": {
 *   "default": [...],                       // 布局本体（默认子布局）的普通排列
 *   "__variant__:split": [...],             // 默认子布局的分体排列
 *   "倉頡五代": {
 *     "default": [...],                     // 该方案的普通排列
 *     "__variant__:split": [...]            // 该方案自己的分体排列
 *   }
 * }
 * ```
 *
 * 变体条目就写在该子布局的 `default` **旁边**：默认子布局的变体是层对象的兄弟键，
 * 方案子布局的变体在方案对象内部。这样"要改哪个子布局的分体排列"在文件里一眼可见。
 *
 * ## 查找顺序
 *
 * 1. **分体排列逐层往上找**：当前方案的分体排列 → 布局本体的分体排列；
 * 2. 都没有就退回**普通排列**，同样是先当前方案、再布局本体。
 *
 * 分体排列优先于普通排列，是因为用户既然专门画了一套分体排列，就是在说"分体时用它"；
 * 而先方案后本体，是因为方案专用布局的键位集合才是该方案真正需要的。中间插一层
 * "本体的分体排列"是必要的：用户在「默认」项下配好分体排列后，绝大多数方案并没有自己的
 * 分体排列，若直接跳到方案自己的普通排列，那套分体排列就形同虚设。
 */
object LayoutVariantResolver {

    /** 取出「就是行集合」的那层数组：直接是数组，或对象里的 `default` / `""`。 */
    fun rowsArrayOf(element: JsonElement?): JsonArray? = when (element) {
        is JsonArray -> element
        is JsonObject -> (element["default"] as? JsonArray) ?: (element[""] as? JsonArray)
        else -> null
    }

    /**
     * [container] 里直接配了 [variant]（或其回退链上更接近的形态）的变体条目时返回它，
     * 否则返回 null。
     *
     * 只认**确实是行集合**的条目：一个畸形（比如被写成字符串）的变体条目应当被跳过并继续
     * 沿着回退链找，而不是把畸形的值当成"找到了"，让键盘退化成空布局。
     */
    fun variantEntry(container: JsonObject, variant: LayoutVariant): JsonElement? {
        for (candidate in variant.fallbackChain()) {
            // Docked 没有对应条目：它是子布局的普通排列，也是回退终点。
            val jsonKey = candidate.jsonKey ?: break
            val entry = container[LayoutJsonUtils.toVariantSubModeLabel(jsonKey)] ?: continue
            if (rowsArrayOf(entry) != null) return entry
        }
        return null
    }

    /** 该元素在 [variant] 下是否能给出行集合（普通排列或变体条目都算）。 */
    private fun hasRowsFor(element: JsonElement, variant: LayoutVariant): Boolean =
        rowsArrayOf(element) != null ||
            (element is JsonObject && variantEntry(element, variant) != null)

    private fun variantEntryOf(element: JsonElement, variant: LayoutVariant): JsonElement? =
        (element as? JsonObject)?.let { variantEntry(it, variant) }

    /**
     * 解析该层在 [variant] 下实际使用的行集合元素。
     *
     * 顺序见类注释。返回的可能是"不是行集合"的元素（例如某个只带 `displayText` 的方案
     * 对象）——那表示这一层没有可用内容，由调用方继续走它自己的回退，而不是在这里抛出
     * 或返回 null 让调用方误以为布局不存在。
     */
    fun resolveRowsElement(
        layoutElement: JsonElement?,
        variant: LayoutVariant,
        subModeCandidates: List<String>
    ): JsonElement? {
        val obj = layoutElement as? JsonObject ?: return layoutElement

        val schemaEntry = subModeCandidates.firstNotNullOfOrNull { key ->
            obj[key]?.takeIf { hasRowsFor(it, variant) }
        }

        if (variant.isSplit) {
            schemaEntry?.let { entry -> variantEntryOf(entry, variant)?.let { return it } }
            variantEntry(obj, variant)?.let { return it }
        }

        schemaEntry?.let { return it }
        return obj["default"]
            ?: obj[""]
            ?: obj
    }
}
