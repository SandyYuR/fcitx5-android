/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/** Streaming TSV conversion; no Python runtime, pinyin database, or full dictionary in memory. */
internal object WanxiangDictionaryConverter {
    private const val MAX_FILE = 128L * 1024 * 1024
    private fun reader(file: File): BufferedReader {
        require(file.isFile && file.length() <= MAX_FILE) { "附加词库缺失或超过 128 MiB" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return BufferedReader(InputStreamReader(file.inputStream(), decoder))
    }
    private fun line(reader: BufferedReader): String? {
        val text = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (text.isEmpty()) null else text.toString().removeSuffix("\r")
            if (c == 10) return text.toString().removeSuffix("\r")
            require(text.length < 32 * 1024) { "词库行过长" }
            text.append(c.toChar())
        }
    }
    private fun root(code: String) = code.substringBefore(';').substringBefore('[')
    fun auxiliary(file: File, checkActive: () -> Unit = {}): Map<String, String> {
        val result = HashMap<String, String>()
        reader(file).use { input ->
            while (true) {
                checkActive()
                val text = line(input) ?: break
                if (text.startsWith('#')) continue
                val columns = text.split('\t')
                if (columns.size < 2 || points(columns[0]).size != 1) continue
                val code = columns[1]
                val split = code.indexOfFirst { it == ';' || it == '[' }
                result[columns[0]] = (if (split >= 0) code.substring(split + 1) else code).trim().takeUnless { it == ";" }.orEmpty()
                require(result.size <= 250_000) { "辅助码字表过大" }
            }
        }
        require(result.isNotEmpty()) { "辅助码字表没有可用的单字记录" }
        return result
    }
    // Match the upstream Lite mapping; Unicode normalization also strips unrelated marks.
    fun lite(code: String): String = buildString {
        code.replace("m\u0300", "me").forEach { character ->
            append(when (character) {
                'ā', 'á', 'ǎ', 'à' -> "a"
                'ē', 'é', 'ě', 'è' -> "e"
                'ī', 'í', 'ǐ', 'ì' -> "i"
                'ō', 'ó', 'ǒ', 'ò' -> "o"
                'ū', 'ú', 'ǔ', 'ù' -> "u"
                'ü', 'ǖ', 'ǘ', 'ǚ', 'ǜ' -> "v"
                'ḿ' -> "me"
                'ń', 'ň', 'ǹ' -> "en"
                else -> character.toString()
            })
        }
    }
    private fun points(word: String): List<String> {
        val result = ArrayList<String>()
        var offset = 0
        while (offset < word.length) {
            val cp = word.codePointAt(offset); offset += Character.charCount(cp)
            if (!Character.isWhitespace(cp)) result += String(Character.toChars(cp))
        }
        return result
    }
    private fun han(text: String): Boolean = text.codePointAt(0).let { it == 0x3007 || it in 0x2e80..0x2fff || it in 0x3400..0x9fff ||
        it in 0xf900..0xfaff || it in 0x20000..0x3347f }
    private fun alignment(word: String, roots: List<String>, aux: Map<String, String>): List<String>? {
        val visible = points(word)
        if (visible.size > 128 || roots.size > 128) return null
        if (visible.size == roots.size) return visible.map { if (han(it)) aux[it].orEmpty() else "" }
        val chinese = visible.filter(::han)
        if (chinese.size == roots.size) return chinese.map { aux[it].orEmpty() }
        val units = ArrayList<String>()
        visible.forEach { point ->
            if (han(point) || units.isEmpty() || han(units.last())) units += point
            else units[units.lastIndex] += point
        }
        val memo = HashMap<Pair<Int, Int>, List<String>?>()
        fun visit(u: Int, p: Int): List<String>? {
            if (u == units.size) return emptyList<String>().takeIf { p == roots.size }
            if (p == roots.size) return null
            val key = u to p
            if (memo.containsKey(key)) return memo[key]
            val item = units[u]
            val answer = if (han(item)) visit(u + 1, p + 1)?.let { listOf(aux[item].orEmpty()) + it }
            else {
                val remainingHan = units.drop(u + 1).count(::han)
                val maximum = roots.size - p - remainingHan
                val exact = (1..maximum).filter { roots.subList(p, p + it).joinToString("").equals(item, true) }
                (exact + (maximum downTo 1)).distinct().firstNotNullOfOrNull { n -> visit(u + 1, p + n)?.let { List(n) { "" } + it } }
            }
            memo[key] = answer
            return answer
        }
        return visit(0, 0)
    }
    fun pro(word: String, code: String, aux: Map<String, String>): String {
        val tokens = code.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return code
        val roots = tokens.map(::root)
        val values = alignment(word, roots, aux) ?: return code
        return roots.indices.joinToString(" ") { "${roots[it]};${values[it]}" }
    }
    fun convert(source: File, target: File, name: String, scheme: WanxiangSelection.Scheme, aux: Map<String, String>,
                checkActive: () -> Unit = {}) {
        require(name.matches(Regex("[a-z0-9][a-z0-9_.-]{0,95}")))
        FileUtil.writeAtomically(target) { output ->
            val writer = output.bufferedWriter(Charsets.UTF_8)
            var header = true
            var named = false
            reader(source).use { input ->
                while (true) {
                    checkActive()
                    var text = line(input) ?: break
                    if (header) {
                        if (text.matches(Regex("\\s*name\\s*:.*"))) { text = "name: $name"; named = true }
                        if (text.trim() == "...") { require(named) { "词库 YAML 头缺少名称" }; header = false }
                    } else if (text.isNotBlank() && !text.startsWith('#')) {
                        val fields = text.split('\t').toMutableList()
                        if (fields.size >= 2 && !(fields.size == 2 && fields[1].all(Char::isDigit))) {
                            fields[1] = when (scheme) {
                                WanxiangSelection.Scheme.LITE -> lite(fields[1])
                                WanxiangSelection.Scheme.PRO -> pro(fields[0], fields[1], aux)
                                else -> fields[1]
                            }
                            text = fields.joinToString("\t")
                        }
                    }
                    writer.append(text).append('\n')
                }
            }
            require(!header) { "附加词库不是带 YAML 头的 Rime 词库" }
            writer.flush()
        }
    }
}
