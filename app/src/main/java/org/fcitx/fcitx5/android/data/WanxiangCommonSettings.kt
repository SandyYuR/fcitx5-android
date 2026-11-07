/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.yaml.snakeyaml.nodes.*

/** Small, original convenience layer over the installed configuration and Rime's real view. */
internal class WanxiangCommonSettings(private val session: WanxiangAdvancedSession) {
    enum class Key { SCHEMAS, PAGE_SIZE, PAGING, SECOND_KEY, THIRD_KEY, USER_DICTIONARY, MAIN_DICTIONARY,
        PHRASE_DICTIONARY, PHRASE_SCHEMAS, ABBREV_DICTIONARY, ABBREV_SCHEMAS, REVERSE_KEY, TIPS_DATABASE, TIPS_KEY, TIPS_DISABLED, GRAMMAR, REMEMBER_SWITCHES }
    private val y = session.yaml
    private val mains get() = session.mainFiles
    val primary: String get() {
        val enabled = (session.value("default.yaml", "schema_list") as? SequenceNode)?.value.orEmpty().mapNotNull {
            (it as? MappingNode)?.let { map -> (y.member(map, "schema") as? ScalarNode)?.value }
        }
        return enabled.firstNotNullOfOrNull { id -> mains.firstOrNull { it.removeSuffix(".schema.yaml") == id } }
            ?: mains.firstOrNull() ?: error("请先安装万象方案")
    }
    private fun path(key: Key) = when (key) {
        Key.USER_DICTIONARY -> "translator/enable_user_dict"
        Key.MAIN_DICTIONARY -> "translator/dictionary"
        Key.REVERSE_KEY -> "wanxiang_lookup/key"
        Key.TIPS_DATABASE -> "super_tips/db_name"
        Key.TIPS_KEY -> "super_tips/tips_key"
        Key.TIPS_DISABLED -> "super_tips/disabled_types"
        else -> error("No scalar path")
    }
    private fun producer(abbrev: Boolean) = if (abbrev) "wanxiang_abbrev.schema.yaml" else "wanxiang_phrase.schema.yaml"
    private fun consumer(abbrev: Boolean) = if (abbrev) "abbrev_phrase/dictionary" else "custom_phrase/dictionary"
    private fun dictionaryReference(node: Node?) = (node as? ScalarNode)?.takeIf { it.tag != Tag.NULL }?.value?.takeIf { it.isNotBlank() }
    private fun consumerFiles() = listOf(primary) + mains.filter { it != primary }
    private fun dictionaryName(abbrev: Boolean): String = producer(abbrev).takeIf { it in session.documents }
        ?.let { dictionaryReference(session.value(it, "translator/dictionary")) }
        ?: consumerFiles().firstNotNullOfOrNull { dictionaryReference(session.value(it, consumer(abbrev))) }
        // Disabling every association in a draft must not immediately replace its
        // original dictionary name with the default before it can be re-enabled.
        ?: consumerFiles().firstNotNullOfOrNull { dictionaryReference(session.baseline(it, consumer(abbrev))) }
        ?: if (abbrev) "wanxiang_abbrev" else "custom_phrase"

    fun help(key: Key): String {
        val abbrev = when (key) {
            Key.PHRASE_DICTIONARY, Key.PHRASE_SCHEMAS -> false
            Key.ABBREV_DICTIONARY, Key.ABBREV_SCHEMAS -> true
            else -> return ""
        }
        if (producer(abbrev) in session.documents) return ""
        val references = consumerFiles().mapNotNull { file -> dictionaryReference(session.value(file, consumer(abbrev)))?.let { file to it } }
        if (references.map { it.second }.distinct().size < 2) return ""
        val (file, name) = references.first()
        return "此处显示 ${file.removeSuffix(".schema.yaml")} 使用的词库「$name」。重命名只更新使用该词库的方案；关联列表仅列出该词库的方案，未选中的其他词库保持不变。"
    }

    private fun bindings(file: String) = (session.value(file, "key_binder/bindings") as? SequenceNode)?.value.orEmpty()
    private fun scalar(node: Node?, fallback: String = "") = (node as? ScalarNode)?.value ?: fallback
    private fun bindingValue(node: Node, key: String) = (node as? MappingNode)?.let { scalar(y.member(it, key)) }.orEmpty()
    private val pages = listOf("comma" to "period", "bracketleft" to "bracketright", "minus" to "equal")
    private fun paging(node: Node) = bindingValue(node, "send").lowercase() in setOf("page_up", "page_down", "prior", "next") &&
        bindingValue(node, "accept").lowercase() in setOf("comma", "period", "bracketleft", "bracketright", "minus", "equal", ",", ".", "[", "]", "-", "=")
    private fun candidate(node: Node, number: Int) = bindingValue(node, "send") == number.toString() &&
        bindingValue(node, "accept").lowercase() !in setOf(number.toString(), "kp_$number")

    fun get(key: Key): Node = when (key) {
        Key.SCHEMAS -> session.value("default.yaml", "schema_list") ?: y.sequence(emptyList())
        Key.PAGE_SIZE -> session.value("default.yaml", "menu/page_size") ?: y.scalar(6)
        Key.PAGING -> {
            val items = bindings(primary).ifEmpty { bindings("default.yaml") }
            y.scalar(pages.indexOfFirst { pair -> items.any { bindingValue(it, "accept").equals(pair.first, true) && paging(it) } } + 1)
        }
        Key.SECOND_KEY, Key.THIRD_KEY -> y.string(bindings(primary).ifEmpty { bindings("default.yaml") }
            .firstOrNull { candidate(it, if (key == Key.SECOND_KEY) 2 else 3) }?.let { bindingValue(it, "accept") }.orEmpty())
        Key.USER_DICTIONARY -> session.value(primary, path(key)) ?: y.scalar(true)
        Key.PHRASE_DICTIONARY, Key.ABBREV_DICTIONARY -> y.string(dictionaryName(key == Key.ABBREV_DICTIONARY))
        Key.PHRASE_SCHEMAS, Key.ABBREV_SCHEMAS -> {
            val abbrev = key == Key.ABBREV_SCHEMAS
            y.sequence(mains.filter { session.text(it, consumer(abbrev)) == dictionaryName(abbrev) }.map { y.string(it.removeSuffix(".schema.yaml")) })
        }
        Key.TIPS_DISABLED -> session.value(primary, path(key)) ?: y.sequence(emptyList())
        Key.GRAMMAR -> y.scalar(0)
        Key.REMEMBER_SWITCHES -> session.value("default.yaml", "switcher/save_options") ?: y.sequence(emptyList())
        else -> session.value(primary, path(key)) ?: y.string(when (key) {
            Key.REVERSE_KEY -> "`"
            Key.TIPS_DATABASE -> "lua/tips"
            Key.TIPS_KEY -> "comma"
            Key.MAIN_DICTIONARY -> "wanxiang"
            else -> ""
        })
    }

    fun set(key: Key, node: Node) {
        when (key) {
            Key.SCHEMAS -> session.change("default.yaml", "schema_list", node)
            Key.PAGE_SIZE -> (listOf("default.yaml") + mains).forEach { session.change(it, "menu/page_size", node) }
            Key.PAGING, Key.SECOND_KEY, Key.THIRD_KEY -> setBindings(key, node)
            Key.USER_DICTIONARY -> mains.forEach { session.change(it, path(key), node) }
            Key.MAIN_DICTIONARY -> {
                val name = scalar(node).trim()
                session.prepareDictionary(scalar(get(key)), name)
                session.change(primary, path(key), y.string(name))
                if (primary == "wanxiang_pro.schema.yaml") listOf("user_dict_set/dictionary", "add_user_dict/dictionary").forEach { session.change(primary, it, y.string(name)) }
            }
            Key.PHRASE_DICTIONARY, Key.ABBREV_DICTIONARY -> {
                val abbrev = key == Key.ABBREV_DICTIONARY
                val old = dictionaryName(abbrev)
                val name = scalar(node).trim()
                val selected = mains.filter { session.text(it, consumer(abbrev)) == old }
                session.prepareDictionary(old, name)
                if (producer(abbrev) in session.documents) session.change(producer(abbrev), "translator/dictionary", y.string(name))
                selected.forEach { session.change(it, consumer(abbrev), y.string(name)) }
            }
            Key.PHRASE_SCHEMAS, Key.ABBREV_SCHEMAS -> {
                val abbrev = key == Key.ABBREV_SCHEMAS
                val selected = (node as SequenceNode).value.map { scalar(it) }.toSet()
                val name = dictionaryName(abbrev)
                mains.forEach { file ->
                    if (file.removeSuffix(".schema.yaml") in selected) session.change(file, consumer(abbrev), y.string(name))
                    // Removing a patch restores the scheme's default association. An empty
                    // dictionary explicitly disables this translator in either editing mode.
                    else if (session.text(file, consumer(abbrev)) == name) session.change(file, consumer(abbrev), y.string(""))
                }
            }
            Key.REVERSE_KEY -> {
                val next = scalar(node)
                require(next.length == 1 && !next[0].isLetterOrDigit() && !next[0].isWhitespace() && !next[0].isISOControl()) { "反查键须为单个非字母数字符号" }
                fun escaped(value: String) = value.map { if (it in "\\.^$|?*+()[]{}") "\\$it" else it.toString() }.joinToString("")
                mains.forEach { file ->
                    val old = session.text(file, "wanxiang_lookup/key", "`")
                    val regex = session.text(file, "recognizer/patterns/wanxiang_reverse", "^`[A-Za-z;]*$")
                    val prefix = "^" + escaped(old)
                    session.change(file, "wanxiang_lookup/key", y.string(next))
                    session.change(file, "wanxiang_reverse/prefix", y.string(next))
                    session.change(file, "recognizer/patterns/wanxiang_reverse", y.string(if (regex.startsWith(prefix)) "^" + escaped(next) + regex.removePrefix(prefix) else regex))
                    val alphabet = session.text(file, "speller/alphabet")
                    if (alphabet.isNotEmpty()) session.change(file, "speller/alphabet", y.string(alphabet.replace(old, "").let { if (next in it) it else it + next }))
                }
            }
            Key.TIPS_DATABASE, Key.TIPS_KEY, Key.TIPS_DISABLED -> mains.filter { y.get(session.documents.getValue(it).source, "super_tips") != null }
                .forEach { session.change(it, path(key), node) }
            Key.GRAMMAR -> {
                val action = scalar(node).toInt()
                require(action in 0..2)
                if (action != 0) {
                    val previous = LinkedHashMap(session.edits)
                    try {
                        mains.forEach { file ->
                            if (action == 1) {
                                session.change(file, "grammar", y.document("language: wanxiang-lts-zh-hans\ncollocation_max_length: 7\ncollocation_min_length: 3\ncollocation_penalty: -10\nnon_collocation_penalty: 3\nweak_collocation_penalty: -35\nunseen_two_char_penalty: 0\nrear_penalty: -8\n"))
                                session.change(file, "translator/contextual_suggestions", y.scalar(false))
                                session.change(file, "translator/max_homophones", y.scalar(8))
                                session.change(file, "translator/max_homographs", y.scalar(8))
                            } else listOf("grammar", "translator/contextual_suggestions", "translator/max_homophones", "translator/max_homographs").forEach { session.reset(file, it) }
                        }
                    } catch (error: Exception) {
                        session.edits.clear()
                        session.edits.putAll(previous)
                        throw error
                    }
                }
            }
            Key.REMEMBER_SWITCHES -> session.change("default.yaml", "switcher/save_options", node)
        }
    }

    fun importSwitches(): Node = y.sequence((session.value(primary, "switches") as? SequenceNode)?.value.orEmpty().flatMap { node ->
        val map = node as? MappingNode ?: return@flatMap emptyList()
        val name = scalar(y.member(map, "name"))
        if (name.isNotBlank() && y.member(map, "reset") == null) listOf(name)
        else (y.member(map, "options") as? SequenceNode)?.value.orEmpty().drop(1).map { scalar(it) }
    }.filter { it.isNotBlank() }.distinct().map(y::string))

    private fun setBindings(key: Key, node: Node) {
        val value = scalar(node).trim()
        if (key != Key.PAGING) {
            require(value.length <= 100 && value.none { it.isISOControl() }) { "无效按键" }
            val other = scalar(get(if (key == Key.SECOND_KEY) Key.THIRD_KEY else Key.SECOND_KEY))
            require(value.isEmpty() || value != other) { "次选与三选不能使用同一个按键" }
        }
        fun binding(accept: String, send: String) = y.mapping().also {
            y.put(it, "when", y.string("has_menu")); y.put(it, "accept", y.string(accept)); y.put(it, "send", y.string(send))
        }
        (listOf("default.yaml") + mains).forEach { file ->
            val next = bindings(file).filterNot { if (key == Key.PAGING) paging(it) else candidate(it, if (key == Key.SECOND_KEY) 2 else 3) }.map { y.clone(it) }.toMutableList()
            if (key == Key.PAGING) {
                val index = value.toInt()
                require(index in 0..3)
                if (index > 0) { val pair = pages[index - 1]; next += binding(pair.first, "Page_Up"); next += binding(pair.second, "Page_Down") }
            } else if (value.isNotEmpty()) next += binding(value, if (key == Key.SECOND_KEY) "2" else "3")
            session.change(file, "key_binder/bindings", y.sequence(next))
        }
    }
}
