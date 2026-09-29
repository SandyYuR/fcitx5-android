/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.search

import java.util.Locale

/**
 * 设置搜索的匹配规则。
 *
 * 刻意做成**不依赖 Android Context 的纯对象**，这样规则本身可以被 JVM 单测完整覆盖
 * （见 `SettingsSearchQueryTest`），而不必为了测一段字符串逻辑引入 Robolectric。
 *
 * 规则（与 boomker/fcitx5-android 的做法一致）：
 * - 查询串按空白切成若干词；
 * - **每个词都必须命中**（AND 语义），而不是整个查询串作为一个子串；
 *   这样「空格 长按」这类多词输入才能收敛到想要的那一项；
 * - 大小写不敏感，用 [Locale.ROOT] 做小写转换，避免土耳其语 i/İ 之类的
 *   区域差异让同一份索引在不同语言环境下表现不同；
 * - 不做模糊匹配、不做拼音切分——中文同义词靠 [SettingsSearchEntry.keywords] 补齐。
 */
object SettingsSearchQuery {

    /**
     * 切词。[query] 为空白时返回空列表，由调用方决定「空查询」怎么处理
     * （设置首页的做法是恢复常规列表，而不是显示全部条目）。
     */
    fun terms(query: String): List<String> =
        query.trim().lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

    /**
     * [haystack] 是否命中 [query]。
     *
     * 空查询一律返回 false——「什么都没搜」不应等同于「匹配一切」，
     * 否则调用方很容易在空查询时渲染出整份目录。
     */
    fun matches(haystack: String, query: String): Boolean {
        val terms = terms(query)
        if (terms.isEmpty()) return false
        val hay = haystack.lowercase(Locale.ROOT)
        return terms.all { hay.contains(it) }
    }
}
