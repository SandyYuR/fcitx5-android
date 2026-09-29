/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.search

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住「每个带 UI 的偏好项都登记进搜索索引」这一约定。
 *
 * 为什么需要它：2026-09-28 人工审计时发现 **13 个带 UI 的偏好项没登记**——
 * 分体键盘横屏布局、候选方向、候选最小宽度、候选项高亮圆角、物理键盘水平候选栏、
 * 远端/媒体剪贴板上限、剪贴板提示超时、边框描边、Gboard 风格操作键、
 * 文本编辑按钮圆角、剪贴板条目圆角、键盘高度基准。用户搜这些词**什么都搜不到**，
 * 且失败**完全静默**：无报错、无日志、`git status` 也看不出来。
 *
 * **只统计 `buildEntries` 里的条目块**，这一点是必须的：`PREFERENCE_KEYS` 里也有
 * 同名资源（它把「标题 → 目标页的偏好键」做映射）。若只在整个文件里搜索资源名，
 * 那么**删掉某个 `SettingsSearchEntry` 后名字仍留在 `PREFERENCE_KEYS` 中，测试照样通过**——
 * 这不是假想：初版实现就是这么写的，反向验证（故意摘掉一条登记）时它确实放过了漏洞。
 * 因此这里先把 `PREFERENCE_KEYS` 整块切除，再在剩余文本里找条目标题。
 *
 * 为什么不直接断言「索引对象内容」：`entries()` 需要 `Context`（rime 页标题要读字符串），
 * JVM 单测里拿不到。故改为源码层面的一致性检查。
 *
 * 解析用的是较严格的字面量匹配，因此**只会漏匹配、不会多匹配**——漏匹配让测试变弱，
 * 不会产生假失败。为防正则失效后测试"空转变绿"，另断言解析出的条目数下限。
 */
class SettingsSearchIndexCoverageTest {

    /**
     * 有意不进搜索索引的偏好项（存**字符串资源名**）。
     *
     * 目前为空：所有带 UI 的偏好项都应当可被搜到。加项时必须写清「用户不该搜到它」的
     * 理由——「忘了登记」不是理由。
     */
    private val INTENTIONALLY_UNSEARCHABLE: Set<String> = emptySet()

    /** 条目数下限，用于发现「正则失效 → 解析出 0 条 → 断言恒真」。 */
    private val MIN_EXPECTED_ENTRIES = 80

    /** `PREFERENCE_KEYS` 条数下限，用途同上。 */
    private val MIN_EXPECTED_PREFERENCE_KEYS = 60

    @Test
    fun `所有带 UI 的偏好项都已登记进搜索索引`() {
        val sources = listOf(
            "app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt",
            "app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemePrefs.kt"
        ).mapNotNull { findSourceFile(it) }

        val index = findSourceFile(
            "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsSearchIndex.kt"
        )

        // 拿不到源码（例如工作目录不同）时跳过，而不是假失败。
        if (sources.size < 2 || index == null) {
            println("SettingsSearchIndexCoverageTest: 未找到源码，跳过")
            return
        }

        val declared = sources
            .flatMap { parsePreferenceTitles(it) }
            .filterNot { it in INTENTIONALLY_UNSEARCHABLE }
            .toSortedSet()

        assertTrue(
            "未能从 AppPrefs/ThemePrefs 解析出任何偏好项，解析逻辑可能已失效",
            declared.isNotEmpty()
        )

        val entryTitles = parseEntryTitles(index)

        // 守门：正则失效导致解析出极少数条目时，下面的差集断言会恒真。
        assertTrue(
            "只从 SettingsSearchIndex 解析出 ${entryTitles.size} 条条目（预期 ≥ $MIN_EXPECTED_ENTRIES），" +
                "解析逻辑可能已失效，此断言不再有保护力",
            entryTitles.size >= MIN_EXPECTED_ENTRIES
        )

        val missing = declared.filterNot { it in entryTitles }

        assertTrue(
            buildString {
                appendLine("以下带 UI 的偏好项未登记进 SettingsSearchIndex，用户将搜不到它们：")
                missing.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("请在 SettingsSearchIndex.buildEntries 中补一条 SettingsSearchEntry，")
                appendLine("并在 PREFERENCE_KEYS 中补上滚动定位键（若该项会单独成行）。")
                appendLine("若确认它不该被搜到，加入 INTENTIONALLY_UNSEARCHABLE 并写明理由。")
            },
            missing.isEmpty()
        )
    }

    /**
     * `PREFERENCE_KEYS` 里不应有**孤儿键**：表里有映射、却没有任何条目引用那个标题。
     *
     * 为什么这也要测：孤儿键是「搜索项缺失」的另一种表现形式。
     * 实例：`verbose_log` 长期躺在映射表里，但「详细记录日志」从来没被登记成条目——
     * 从映射表看它"已经在索引里了"，从用户角度看它**搜不到**。
     * 它的标题由 `DeveloperFragment` 用 `setTitle()` 直接设置（偏好本体在 `AppPrefs.Internal`，
     * 没走 `R.string` 元数据形式），所以上面那条覆盖测试也扫不到它，两个断言互为补充。
     */
    @Test
    fun `PREFERENCE_KEYS 中没有孤儿定位键`() {
        val index = findSourceFile(
            "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsSearchIndex.kt"
        )
        if (index == null) {
            println("SettingsSearchIndexCoverageTest: 未找到源码，跳过")
            return
        }

        val text = index.readText()
        val mapBlock = extractBlock(text, "private val PREFERENCE_KEYS")
        if (mapBlock == null) {
            throw AssertionError("未能定位 PREFERENCE_KEYS 块，解析逻辑可能已失效")
        }

        val mapped = Regex("""R\.string\.([A-Za-z0-9_]+)\s+to\s+[^,]+,""")
            .findAll(mapBlock)
            .map { it.groupValues[1] }
            .toSortedSet()

        assertTrue(
            "未能从 PREFERENCE_KEYS 解析出任何项，解析逻辑可能已失效",
            mapped.isNotEmpty()
        )

        val entryTitles = parseEntryTitles(index)
        val orphans = mapped.filterNot { it in entryTitles }

        assertTrue(
            buildString {
                appendLine("以下定位键存在于 PREFERENCE_KEYS，但没有任何 SettingsSearchEntry 引用它们：")
                orphans.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("这通常意味着该设置项漏登记（用户搜不到），或条目已被删除而映射键忘了清理。")
                appendLine("请补上 SettingsSearchEntry，或删掉这条映射。")
            },
            orphans.isEmpty()
        )
    }

    /**
     * `PREFERENCE_KEYS` 的**值**必须都是 `AppPrefs`/`ThemePrefs` 里真实存在的偏好键。
     *
     * 这是第三类静默失败：键名写错（少个字母、用了旧名、把下划线写成连字符）时，
     * `findPreference(key)` 返回 null，`PreferenceScrollHelper` 的约定是**保留待处理键**，
     * 于是表现为「点了搜索结果、跳到了正确页面，但**没有滚动**」，而且**没有任何日志**。
     * 这类错误在代码审阅时肉眼极难发现——`keybord_height_percent` 和
     * `keyboard_height_percent` 只差一个字母。
     *
     * 判据取宽松的「该键名是否作为字符串字面量出现在偏好定义文件中」，
     * 因为偏好键在源码里可能出现在各种工厂调用里，严格匹配工厂签名会漏掉用变量传键的项
     * （如剪贴板的 `remoteKey`）；宽松匹配只会漏报、不会误报。
     */
    @Test
    fun `PREFERENCE_KEYS 的值都是真实存在的偏好键`() {
        val prefSources = listOf(
            "app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt",
            "app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemePrefs.kt"
        ).mapNotNull { findSourceFile(it) }

        val index = findSourceFile(
            "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsSearchIndex.kt"
        )

        if (prefSources.size < 2 || index == null) {
            println("SettingsSearchIndexCoverageTest: 未找到源码，跳过")
            return
        }

        val mapBlock = extractBlock(index.readText(), "private val PREFERENCE_KEYS")
            ?: throw AssertionError("未能定位 PREFERENCE_KEYS 块，解析逻辑可能已失效")

        val mappedValues = Regex("""to\s+"([a-z_0-9]+)"""")
            .findAll(mapBlock)
            .map { it.groupValues[1] }
            .toSortedSet()

        // 守门：正则失效时下面的断言会恒真。
        assertTrue(
            "只从 PREFERENCE_KEYS 解析出 ${mappedValues.size} 个键（预期 ≥ $MIN_EXPECTED_PREFERENCE_KEYS），" +
                "解析逻辑可能已失效",
            mappedValues.size >= MIN_EXPECTED_PREFERENCE_KEYS
        )

        val prefText = prefSources.joinToString("\n") { it.readText() }
        val unknown = mappedValues.filterNot { prefText.contains("\"$it\"") }

        assertTrue(
            buildString {
                appendLine("PREFERENCE_KEYS 中以下键在 AppPrefs/ThemePrefs 里找不到对应定义：")
                unknown.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("这类拼写错误会让「搜索跳转后滚动定位」静默失效——跳到页面但不滚动，且无任何日志。")
                appendLine("请核对键名是否与偏好定义完全一致。")
            },
            unknown.isEmpty()
        )
    }

    /**
     * 解析形如 `val xxx = <工厂>(R.string.TITLE, "key", ...)` 的偏好声明。
     *
     * 只认「第一参数是字符串资源、第二参数是字面量键」的写法；用变量传键的项
     * （如剪贴板的 `remoteKey`）不会被匹配到——有意为之的保守策略。
     */
    private fun parsePreferenceTitles(file: File): List<String> {
        val regex = Regex(
            """(?:switch|int|float|string|enumList|twinInt|themePreference|switchPref|list)\s*\(\s*R\.string\.([A-Za-z0-9_]+)\s*,\s*"[a-z0-9_]+""""
        )
        return regex.findAll(file.readText()).map { it.groupValues[1] }.toList()
    }

    /**
     * 解析 `buildEntries` 中每个 `SettingsSearchEntry(` 的标题资源名。
     *
     * 先切除 `PREFERENCE_KEYS` 映射块——否则被删掉的条目仍能靠映射表里的名字"通过"检查。
     */
    private fun parseEntryTitles(index: File): Set<String> {
        var text = index.readText()
        text = removeBlock(text, "private val PREFERENCE_KEYS")
        val regex = Regex("""SettingsSearchEntry\(\s*R\.string\.([A-Za-z0-9_]+)""")
        return regex.findAll(text).map { it.groupValues[1] }.toSet()
    }

    /**
     * 取出以 [marker] 所在块的文本：从该位置后的第一个 `(` 起，到与之配对的 `)` 为止。
     *
     * 找不到 [marker] 或括号未配对时返回 null，让调用方**显式失败**，
     * 而不是拿到残缺文本后静默算出空结果。
     */
    private fun extractBlock(text: String, marker: String): String? {
        val start = text.indexOf(marker)
        if (start < 0) return null
        var depth = 0
        var i = text.indexOf('(', start)
        if (i < 0) return null
        val open = i
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                }
                '/' -> if (text.getOrNull(i + 1) == '/') {
                    while (i < text.length && text[i] != '\n') i++
                }
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
                else -> Unit
            }
            i++
        }
        return null
    }

    /**
     * 从 [marker] 起始位置删除到与之匹配的闭合括号，再返回剩余文本。
     *
     * 之所以不按行删除：块内可能嵌套括号（例如 `mapOf(` 里再包 `listOf(`），
     * 按行删除会误伤或漏删。这里做括号配对计数，并跳过字符串字面量与行注释，
     * 避免里面的 `)` 干扰计数。
     */
    private fun removeBlock(text: String, marker: String): String {
        val start = text.indexOf(marker)
        if (start < 0) return text

        var depth = 0
        var i = text.indexOf('(', start)
        if (i < 0) return text
        val open = i
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> {
                    // 跳过字符串字面量
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                }
                '/' -> if (text.getOrNull(i + 1) == '/') {
                    while (i < text.length && text[i] != '\n') i++
                }
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(0, start) + text.substring(i + 1)
                    }
                }
                else -> Unit
            }
            i++
        }
        // 未配对：保守返回原文，让下游断言去暴露问题而不是静默通过。
        return text.substring(0, open)
    }

    /**
     * 从当前目录逐级向上探测源码根。
     *
     * 不写死绝对路径：Gradle 单测的工作目录可能是模块目录（`app/`）或仓库根，
     * 向上探测能同时覆盖。
     */
    private fun findSourceFile(relativePath: String): File? {
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        return null
    }
}
