/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.manager

import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.FcitxConnection

/**
 * SubMode manager for handling keyboard layout submode-related logic.
 *
 * Features:
 * - Extract submode labels from layout data
 * - Extract submode labels from Fcitx status area menu
 * - Resolve current input method state and available submode list
 */
class SubModeManager(
    private val fcitxConnection: FcitxConnection,
    private val allImesFromJson: Array<InputMethodEntry>,
    private val entries: Map<String, List<List<Map<String, Any?>>>>
) {

    var nameToIdMap: Map<String, String> = emptyMap()
        private set

    companion object {
        /**
         * 把 selector 语义暴露给运行时：从状态区动作解析方案菜单。
         *
         * 与布局编辑器共用同一个解析（状态区方案菜单 → 分隔符之前的条目 → 去掉首位
         * 西文伪条目），返回方案条目（显示名 → 动作 id），无菜单时为空。
         *
         * @see RimeSchemaMenuDialog
         */
        fun resolveSchemaMenuEntries(actions: Array<Action>): List<Pair<String, Int>> {
            val currentLabels = actions.mapNotNull { SubModeMenuResolver.toMenuLabel(it) }
            for (currentLabel in currentLabels) {
                val picked = SubModeMenuResolver.pickSchemeMenu(actions, currentLabel) ?: continue
                val result = SubModeMenuResolver.takeItemsBeforeSeparator(picked)
                    .drop(1)
                    .mapNotNull { item ->
                        val name = item.shortText.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        name to item.id
                    }
                if (result.isNotEmpty()) return result
            }
            return emptyList()
        }

        /**
         * 子模式下拉框的「显示位置 → 子模式标签」映射。
         *
         * 位置 0 固定为显式「默认」项，映射为 null——它代表基础布局，即布局 JSON 里
         * `"rime": { "default": [...] }` 的 default。此前下拉框只列方案名，基础布局在编辑器
         * 里没有任何入口：某个方案一旦有了专用布局，基础布局就既看不到也改不了，而它正是
         * 其它（无专用布局的）方案在运行时实际使用的布局。
         *
         * 用位置而不是显示文本建立映射：首项的显示文本随系统语言变化（「默认」/Default），
         * 直接拿文本当标签会去查 `entries["rime:默认"]` 这种不存在的键。
         *
         * @param labels 可用子模式标签（方案名等）
         * @return 下标即下拉框位置，值为子模式标签；null 表示首位的「默认」项
         */
        fun buildSpinnerSelectionMap(labels: List<String>): List<String?> =
            listOf<String?>(null) + labels

        /**
         * 下拉框位置是否有效。
         *
         * 位置 0 的映射值是 null（显式「默认」项 = 基础布局），这是**合法取值**，不能用
         * `getOrNull(position) ?: return` 这类写法把"值恰好是 null"当成"取不到"：那会让
         * 「默认」项永远点不动——用户看着下拉框回到「默认」、编辑区却仍是原方案。
         *
         * @param selectionMap 见 [buildSpinnerSelectionMap]
         * @param position 下拉框选中位置
         */
        fun isValidSpinnerPosition(selectionMap: List<String?>, position: Int): Boolean =
            position in selectionMap.indices
    }

    /**
     * SubMode state data class
     *
     * @property currentIme Current input method entry
     * @property labels List of all available submode labels
     */
    data class SubModeState(
        val currentIme: InputMethodEntry?,
        val labels: List<String>
    )

    /**
     * Extract submode labels from layout data
     *
     * @param layoutName Layout name
     * @return List of submode labels
     */
    fun extractSubModeLabelsFromLayout(layoutName: String): List<String> {
        // Collect from submode layout keys
        val labels = linkedSetOf<String>()

        entries.keys.forEach { key ->
            if (key.startsWith("$layoutName:")) {
                val subModeLabel = key.substringAfter("$layoutName:")
                if (subModeLabel.isNotEmpty() && subModeLabel != "default") {
                    labels.add(subModeLabel)
                }
            }
        }

        // 从 displayText 收集
        val rows = entries[layoutName] ?: return labels.toList()

        rows.forEach { row ->
            row.forEach { key ->
                when (val displayText = key["displayText"]) {
                    is JsonObject -> {
                        displayText.keys.forEach { mode ->
                            val normalized = mode.trim()
                            if (normalized.isNotEmpty() && normalized != "default") {
                                labels.add(normalized)
                            }
                        }
                    }
                    is Map<*, *> -> {
                        displayText.keys.forEach { mode ->
                            val normalized = mode?.toString()?.trim().orEmpty()
                            if (normalized.isNotEmpty() && normalized != "default") {
                                labels.add(normalized)
                            }
                        }
                    }
                }
            }
        }

        return labels.toList()
    }

    /**
     * Resolve submode state
     *
     * @param layoutName Layout name
     * @param layoutLabels Submode labels from layout
     * @return SubMode state
     */
    fun resolveSubModeState(layoutName: String, layoutLabels: List<String>): SubModeState {
        val (currentIme, fcitxLabels) = fetchCurrentImeAndSubModeLabels(layoutName)
        val idToNameMap = nameToIdMap.entries.associate { (name, id) -> id to name }
        val normalizedLayoutLabels = layoutLabels.map { idToNameMap[it] ?: it }
        val labels = (fcitxLabels + normalizedLayoutLabels)
            .map { it.trim() }
            .distinct()
            .filter { it.isNotBlank() }
        return SubModeState(currentIme, labels)
    }

    /**
     * Check if current layout is Rime input method
     *
     * @param layoutName Layout name
     * @return Whether it is Rime
     */
    fun isCurrentLayoutRime(layoutName: String): Boolean {
        val ime = allImesFromJson.firstOrNull {
            it.uniqueName == layoutName || it.displayName == layoutName
        }
        val uniqueName = ime?.uniqueName ?: layoutName
        val displayName = ime?.displayName ?: layoutName
        return uniqueName.contains("rime", ignoreCase = true) ||
            displayName.contains("rime", ignoreCase = true)
    }

    /**
     * Fetch current input method entry and submode labels
     *
     * @param layoutName Layout name
     * @return Pair(Current input method entry, submode labels list)
     */
    internal fun fetchCurrentImeAndSubModeLabels(layoutName: String): Pair<InputMethodEntry?, List<String>> {
        return runCatching {
            fcitxConnection.runImmediately {
                val targetImeUniqueName = resolveTargetImeUniqueName(layoutName)

                if (targetImeUniqueName != null) {
                    runCatching { activateIme(targetImeUniqueName) }.onFailure { e ->
                        android.util.Log.w("SubModeManager", "Failed to activate IME: $targetImeUniqueName", e)
                    }
                }

                val ime = runCatching { currentIme() }.getOrElse { 
                    android.util.Log.w("SubModeManager", "Failed to get current IME, using cached")
                    inputMethodEntryCached 
                }
                val currentLabel = ime.subMode.label.ifEmpty { ime.subMode.name }.trim()
                
                var actions = runCatching { 
                    statusArea() 
                }.onFailure { e ->
                    android.util.Log.w("SubModeManager", "Failed to get status area, using cached", e)
                }.getOrNull() ?: statusAreaActionsCached

                var fromStatusMenu = extractLabelsFromStatusArea(actions, currentLabel)

                if (fromStatusMenu.isEmpty() && targetImeUniqueName != null) {
                    android.util.Log.d("SubModeManager", "First attempt returned empty, retrying IME activation")
                    runCatching { activateIme(targetImeUniqueName) }.onFailure { e ->
                        android.util.Log.w("SubModeManager", "Failed to activate IME on retry: $targetImeUniqueName", e)
                    }
                    actions = runCatching { 
                        statusArea() 
                    }.onFailure { e ->
                        android.util.Log.w("SubModeManager", "Failed to get status area on retry", e)
                    }.getOrNull() ?: statusAreaActionsCached
                    fromStatusMenu = extractLabelsFromStatusArea(actions, currentLabel)
                }

                if (fromStatusMenu.isEmpty()) {
                    android.util.Log.d("SubModeManager", "Second attempt returned empty, trying focusOutIn")
                    runCatching { focusOutIn() }.onFailure { e ->
                        android.util.Log.w("SubModeManager", "Failed to execute focusOutIn", e)
                    }
                    actions = runCatching { 
                        statusArea() 
                    }.onFailure { e ->
                        android.util.Log.w("SubModeManager", "Failed to get status area after focusOutIn", e)
                    }.getOrNull() ?: statusAreaActionsCached
                    fromStatusMenu = extractLabelsFromStatusArea(actions, currentLabel)
                }

                nameToIdMap = SubModeMenuResolver.buildNameToIdMap(actions, currentLabel)

                val baseLabels = when {
                    fromStatusMenu.isNotEmpty() -> fromStatusMenu
                    else -> {
                        android.util.Log.d("SubModeManager", "No submode labels found for layout: $layoutName")
                        emptyList()
                    }
                }

                val labels = baseLabels.toMutableList().apply {
                    if (currentLabel.isNotEmpty() && currentLabel !in this) add(0, currentLabel)
                }.distinct()

                ime to labels
            }
        }.onFailure { e ->
            android.util.Log.e("SubModeManager", "Failed to fetch current IME and submode labels for layout: $layoutName", e)
        }.getOrElse {
            null to emptyList()
        }
    }

    /**
     * Resolve target IME unique name
     *
     * @param layoutName Layout name
     * @return Target IME unique name
     */
    private fun resolveTargetImeUniqueName(layoutName: String): String? {
        return layoutName
            .let { layout ->
                allImesFromJson.firstOrNull {
                    it.uniqueName == layout || it.displayName == layout
                }?.uniqueName
            }
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Extract submode labels from status area menu
     *
     * @param actions Status area actions list
     * @param currentLabel Current submode label
     * @return Submode labels list
     */
    private fun extractLabelsFromStatusArea(
        actions: Array<Action>,
        currentLabel: String
    ): List<String> {
        return SubModeMenuResolver.extractLabels(actions, currentLabel)
    }

    /**
     * SubMode menu resolver
     */
    private object SubModeMenuResolver {
        /**
         * Extract submode labels from status area actions
         *
         * @param actions Status area actions list
         * @param currentLabel Current submode label
         * @return Submode labels list
         */
        fun extractLabels(
            actions: Array<Action>,
            currentLabel: String
        ): List<String> {
            return pickSchemeMenu(actions, currentLabel)
                ?.let { takeItemsBeforeSeparator(it) }
                ?.drop(1) // The first scheme menu item is a pseudo entry (e.g. English mode), exclude by position.
                ?.mapNotNull { toMenuLabel(it) }
                .orEmpty()
        }

        /**
         * Pick scheme menu
         *
         * @param actions Status area actions list
         * @param currentLabel Current submode label
         * @return Scheme menu list
         */
        internal fun pickSchemeMenu(
            actions: Array<Action>,
            currentLabel: String
        ): List<Action>? {
            val topMenus = actions.mapNotNull { action ->
                action.menu?.toList()?.takeIf { it.isNotEmpty() }
            }
            if (topMenus.isEmpty()) return null

            val byCurrentLabel = topMenus.firstOrNull { menu ->
                menu.any { toMenuLabel(it) == currentLabel }
            }
            if (byCurrentLabel != null) return byCurrentLabel

            val withSeparator = topMenus.firstOrNull { menu ->
                val schemePart = takeItemsBeforeSeparator(menu)
                schemePart.size >= 2
            }
            if (withSeparator != null) return withSeparator

            return topMenus.firstOrNull()
        }

        /**
         * Take items before separator
         *
         * @param items Actions list
         * @return Items before separator
         */
        internal fun takeItemsBeforeSeparator(
            items: List<Action>
        ): List<Action> {
            val separatorIndex = items.indexOfFirst { it.isSeparator }
            val head = if (separatorIndex >= 0) items.subList(0, separatorIndex) else items
            return head.filterNot { it.isSeparator }
        }

        /**
         * Convert to menu label
         *
         * @param action Action
         * @return Menu label
         */
        internal fun toMenuLabel(action: Action): String? =
            action.shortText.ifEmpty { action.longText }.ifEmpty { action.name }.trim().takeIf { it.isNotEmpty() }

        fun buildNameToIdMap(
            actions: Array<Action>,
            currentLabel: String
        ): Map<String, String> {
            val menu = pickSchemeMenu(actions, currentLabel)
                ?.let { takeItemsBeforeSeparator(it) }
                ?.drop(1)
                ?: return emptyMap()
            val map = mutableMapOf<String, String>()
            for (item in menu) {
                val name = item.shortText.trim().takeIf { it.isNotEmpty() } ?: continue
                val id = item.longText.trim().takeIf { it.isNotEmpty() } ?: continue
                map[name] = id
            }
            return map
        }
    }
}
