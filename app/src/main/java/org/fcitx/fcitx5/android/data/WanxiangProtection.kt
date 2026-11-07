/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import java.io.File

/** User rules protect existing resources. Runtime databases are always excluded separately. */
internal class WanxiangProtection(text: String) {
    private val rules: List<Regex>
    init {
        require(text.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "保护规则过长" }
        val lines = text.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }
        require(lines.size <= 128) { "保护规则不能超过 128 条" }
        rules = lines.mapIndexed { index, value ->
            require(value.length <= 512) { "第 ${index + 1} 条保护规则过长" }
            try { Regex(value) } catch (_: IllegalArgumentException) { error("第 ${index + 1} 条保护规则不是有效的正则表达式") }
        }
    }
    fun protects(root: File, path: String): Boolean = WanxiangPayload.runtimeState(path) ||
        (WanxiangPayload.child(root.canonicalFile, path).exists() && rules.any { it.containsMatchIn(path) })

    fun filter(payload: File, user: File) {
        payload.walkTopDown().filter { it.isFile }.forEach { file ->
            val path = file.relativeTo(payload).invariantSeparatorsPath
            if (protects(user, path)) check(file.delete()) { "无法清理受保护的暂存文件：$path" }
        }
    }
    companion object {
        val DEFAULT = listOf(
            "(^|/)custom_phrase\\.(txt|dict\\.yaml)$",
            "(^|/)wanxiang_abbrev\\.dict\\.yaml$",
            "^(?!custom/).*\\.custom\\.yaml$",
            "(^|/)sequence[^/]*\\.txt$"
        ).joinToString("\n")
    }
}
