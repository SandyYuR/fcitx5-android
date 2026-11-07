/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.comments.CommentLine
import org.yaml.snakeyaml.comments.CommentType
import org.yaml.snakeyaml.nodes.*
import org.yaml.snakeyaml.representer.Representer
import java.io.StringReader
import java.io.StringWriter
import java.util.IdentityHashMap

/** Comment-preserving YAML syntax editing. Rime itself compiles includes and patches. */
internal class WanxiangYaml {
    private val loader = LoaderOptions().apply {
        isAllowDuplicateKeys = false
        allowRecursiveKeys = false
        isProcessComments = true
        maxAliasesForCollections = 50
        nestingDepthLimit = 42
        codePointLimit = MAX_TEXT
    }
    private val dumper = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isProcessComments = true
        indent = 2
        width = 120
        splitLines = false
    }
    private val yaml = Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader)

    fun parse(text: String): Node {
        require(text.length <= MAX_TEXT) { "YAML exceeds the editing limit" }
        yaml.load<Any?>(text) // SafeConstructor checks duplicate keys and rejects object tags.
        val node = yaml.compose(StringReader(text)) ?: mapping()
        val ancestors = IdentityHashMap<Node, Boolean>()
        fun check(node: Node, depth: Int) {
            require(depth <= 40 && ancestors.put(node, true) == null) { "Recursive YAML aliases cannot be edited" }
            when (node) {
                is MappingNode -> node.value.forEach { entry ->
                    require(entry.keyNode is ScalarNode) { "Configuration keys must be scalars" }
                    check(entry.valueNode, depth + 1)
                }
                is SequenceNode -> node.value.forEach { check(it, depth + 1) }
            }
            ancestors.remove(node)
        }
        check(node, 0)
        return node
    }

    fun document(text: String) = parse(text).also { require(it is MappingNode) { "Configuration root must be a mapping" } } as MappingNode
    private fun wrapped(node: Node): String {
        val wrapper = mapping()
        val detached = clone(node)
        fun normalize(at: Node) {
            if (at is CollectionNode<*>) {
                // The emitter loses indentation on commented flow collections used as list items.
                if (!at.blockComments.isNullOrEmpty()) at.flowStyle = DumperOptions.FlowStyle.BLOCK
                // Inline comments on a block collection need a node on the same line to be legal.
                if (at.flowStyle == DumperOptions.FlowStyle.BLOCK && !at.inLineComments.isNullOrEmpty()) {
                    at.blockComments = at.blockComments.orEmpty() + at.inLineComments.map {
                        CommentLine(it.startMark, it.endMark, it.value, CommentType.BLOCK)
                    }
                    at.inLineComments = null
                }
            }
            when (at) {
                is MappingNode -> at.value.forEach { normalize(it.keyNode); normalize(it.valueNode) }
                is SequenceNode -> at.value.forEach(::normalize)
            }
        }
        normalize(detached)
        wrapper.value.add(NodeTuple(ScalarNode(Tag.STR, WRAPPER, null, null, DumperOptions.ScalarStyle.PLAIN), detached))
        return StringWriter().also { yaml.serialize(wrapper, it) }.toString()
    }
    /** SnakeYAML cannot emit a root scalar's inline comment. Emit it as a mapping value first. */
    fun dump(node: Node): String {
        val lines = wrapped(node).lines()
        val index = lines.indexOfFirst { it.startsWith("$WRAPPER:") }
        check(index >= 0)
        val prefix = lines.take(index)
        val first = lines[index].removePrefix("$WRAPPER:").trimStart()
        val remaining = lines.drop(index + 1)
        if (first.isNotEmpty()) return (prefix + first + remaining).joinToString("\n")
        // Mapping values may contain indentless lists. Dedent only by the actual root indent.
        val indent = remaining.firstOrNull { it.isNotBlank() && !it.trimStart().startsWith('#') }
            ?.takeWhile { it == ' ' }?.length ?: 0
        return (prefix + remaining.map { if (it.startsWith(" ".repeat(indent))) it.drop(indent) else it }).joinToString("\n")
    }
    fun clone(node: Node): Node {
        val copies = IdentityHashMap<Node, Node>()
        fun copy(at: Node): Node {
            copies[at]?.let { return it }
            val next: Node = when (at) {
                is ScalarNode -> ScalarNode(at.tag, if (at.tag == Tag.NULL && at.value.isEmpty()) "null" else at.value, null, null, at.scalarStyle)
                is MappingNode -> MappingNode(at.tag, mutableListOf(), at.flowStyle)
                is SequenceNode -> SequenceNode(at.tag, mutableListOf(), at.flowStyle)
                else -> error("Unsupported YAML node")
            }
            copies[at] = next
            next.blockComments = at.blockComments?.toList()
            next.inLineComments = at.inLineComments?.toList()
            next.endComments = at.endComments?.toList()
            next.anchor = at.anchor
            when {
                at is MappingNode && next is MappingNode -> at.value.forEach { next.value.add(NodeTuple(copy(it.keyNode), copy(it.valueNode))) }
                at is SequenceNode && next is SequenceNode -> at.value.forEach { next.value.add(copy(it)) }
            }
            return next
        }
        return copy(node)
    }
    fun equivalent(first: Node?, second: Node?): Boolean = when {
        first == null || second == null -> first == second
        else -> yaml.load<Map<String, Any?>>(wrapped(first))[WRAPPER] == yaml.load<Map<String, Any?>>(wrapped(second))[WRAPPER]
    }
    fun mapping() = MappingNode(Tag.MAP, mutableListOf(), DumperOptions.FlowStyle.BLOCK)
    fun sequence(nodes: List<Node>) = SequenceNode(Tag.SEQ, nodes.toMutableList(), DumperOptions.FlowStyle.BLOCK)
    fun string(value: String) = ScalarNode(Tag.STR, value, null, null,
        if ('\n' in value) DumperOptions.ScalarStyle.LITERAL else DumperOptions.ScalarStyle.SINGLE_QUOTED)
    fun scalar(value: Boolean) = ScalarNode(Tag.BOOL, value.toString(), null, null, DumperOptions.ScalarStyle.PLAIN)
    fun scalar(value: Int) = ScalarNode(Tag.INT, value.toString(), null, null, DumperOptions.ScalarStyle.PLAIN)
    fun key(node: Node) = (node as ScalarNode).value
    fun member(map: MappingNode, name: String): Node? = map.value.firstOrNull { key(it.keyNode) == name }?.valueNode

    fun put(map: MappingNode, name: String, value: Node?) {
        val index = map.value.indexOfFirst { key(it.keyNode) == name }
        if (value == null) { if (index >= 0) map.value.removeAt(index); return }
        val previous = map.value.getOrNull(index)
        previous?.valueNode?.let {
            value.blockComments = it.blockComments
            value.inLineComments = it.inLineComments
            value.endComments = it.endComments
        }
        val pair = NodeTuple(previous?.keyNode ?: string(name), value)
        if (index < 0) map.value.add(pair) else map.value[index] = pair
    }

    fun path(value: String): List<String> {
        val tokens = value.split('/')
        require(tokens.size in 1..20 && tokens.none { it.isEmpty() || it.any { c -> c.code < 32 } }) { "Invalid configuration path" }
        return tokens
    }

    fun get(root: Node, path: String): Node? = path(path).fold(root as Node?) { current, token ->
        when (current) {
            is MappingNode -> member(current, token)
            is SequenceNode -> index(token, current.value.size, append = false)?.let { current.value.getOrNull(it) }
            else -> null
        }
    }

    private fun index(token: String, size: Int, append: Boolean): Int? = when {
        token == "@last" -> (size - 1).takeIf { it >= 0 }
        token == "@next" && append -> size
        token.matches(Regex("@\\d+")) -> token.drop(1).toIntOrNull()?.takeIf { it in 0..10000 }
        else -> null
    }

    fun set(root: MappingNode, path: String, value: Node?) {
        val tokens = path(path)
        var parent: Node = root
        tokens.forEachIndexed { position, token ->
            val last = position == tokens.lastIndex
            when (val at = parent) {
                is MappingNode -> if (last) put(at, token, value) else {
                    parent = member(at, token) ?: (if (tokens[position + 1].startsWith('@')) sequence(emptyList()) else mapping()).also { put(at, token, it) }
                }
                is SequenceNode -> {
                    val i = index(token, at.value.size, append = true) ?: error("Invalid list index: $token")
                    if (value == null && last) { if (i < at.value.size) at.value.removeAt(i) }
                    else {
                        while (at.value.size <= i) at.value.add(ScalarNode(Tag.NULL, "null", null, null, DumperOptions.ScalarStyle.PLAIN))
                        if (last) at.value[i] = value!! else {
                            if (at.value[i].tag == Tag.NULL) at.value[i] = mapping()
                            parent = at.value[i]
                        }
                    }
                }
                else -> error("Configuration path crosses a scalar: $path")
            }
        }
    }

    fun patchPath(key: String): String = key.replace(Regex("/(?:[+=]|\\d+\\+)$"), "")

    /** Remove a literal override without leaving a parent replacement hiding its source value. */
    fun removePatchOverride(patch: MappingNode, path: String, source: MappingNode) {
        fun touches(value: Node, relative: String): Boolean {
            var at = value
            this.path(relative).forEach { token ->
                val map = at as? MappingNode ?: return true
                if (map.value.any { key(it.keyNode).startsWith("__") || '/' in key(it.keyNode) }) return true
                at = member(map, token) ?: return false
            }
            return true
        }
        val ancestors = patch.value.filter { entry ->
            val key = key(entry.keyNode)
            val parent = patchPath(key)
            !key.startsWith("__") && path.startsWith("$parent/") &&
                (touches(entry.valueNode, path.removePrefix("$parent/")) ||
                    !key.endsWith("/+") && get(source, path) != null)
        }
        require(ancestors.size <= 1) { "此字段有多层父级补丁，请先在父字段中移除覆盖" }
        val ancestor = ancestors.singleOrNull()
        val expanded = mutableListOf<NodeTuple>()
        if (ancestor != null) {
            require(patch.value.none { key(it.keyNode).startsWith("__") }) { "父级补丁包含引用，请在父字段中移除覆盖" }
            val parentKey = key(ancestor.keyNode)
            val parent = patchPath(parentKey)
            require(patch.value.count {
                val at = patchPath(key(it.keyNode))
                at == parent || parent.startsWith("$at/")
            } == 1) { "此字段有重叠父级补丁，请先编辑整个父字段" }
            require(parentKey == parent || parentKey == "$parent/=" || parentKey == "$parent/+") {
                "无法单独移除追加列表中的字段，请编辑整个列表"
            }
            fun emit(name: String, value: Node, original: Node? = null) {
                val field = string(name).apply {
                    blockComments = original?.blockComments
                    inLineComments = original?.inLineComments
                }
                expanded += NodeTuple(field, clone(value))
            }
            fun split(prefix: String, value: Node?, original: Node?, tokens: List<String>) {
                require(value == null || value is MappingNode) { "父字段不是映射，请编辑整个父字段" }
                val map = value as? MappingNode ?: mapping()
                val base = original as? MappingNode
                require((map.value + base?.value.orEmpty()).none { key(it.keyNode).startsWith("__") || '/' in key(it.keyNode) }) {
                    "父字段包含编译指令或路径补丁，请编辑整个父字段"
                }
                if (original != null && base == null) emit(prefix, mapping())
                val selected = tokens.first()
                map.value.forEach { entry ->
                    val name = key(entry.keyNode)
                    if (name != selected) emit("$prefix/$name", entry.valueNode, entry.keyNode)
                }
                base?.value?.filter { key(it.keyNode) != selected && member(map, key(it.keyNode)) == null }?.forEach { entry ->
                    // A replacement also removed source siblings absent from its map.
                    emit("$prefix/${key(entry.keyNode)}", parse("null"))
                }
                if (tokens.size > 1) split("$prefix/$selected", member(map, selected), base?.let { member(it, selected) }, tokens.drop(1))
            }
            val tokens = this.path(path.removePrefix("$parent/"))
            if (parentKey.endsWith("/+")) {
                val remaining = clone(ancestor.valueNode)
                fun removeMerged(at: Node, tokens: List<String>) {
                    require(at is MappingNode) { "无法单独移除追加列表中的字段，请编辑整个列表" }
                    require(at.value.none { key(it.keyNode).startsWith("__") || '/' in key(it.keyNode) }) {
                        "父字段包含编译指令或路径补丁，请编辑整个父字段"
                    }
                    val name = tokens.first()
                    val child = member(at, name) ?: return
                    if (tokens.size == 1) put(at, name, null)
                    else {
                        removeMerged(child, tokens.drop(1))
                        if (child is MappingNode && child.value.isEmpty()) put(at, name, null)
                    }
                }
                removeMerged(remaining, tokens)
                if ((remaining as MappingNode).value.isNotEmpty()) expanded += NodeTuple(clone(ancestor.keyNode), remaining)
            } else split(parent, ancestor.valueNode, get(source, parent), tokens)
            if (!parentKey.endsWith("/+")) expanded.firstOrNull()?.keyNode?.let { first ->
                first.blockComments = ancestor.keyNode.blockComments.orEmpty() + first.blockComments.orEmpty()
            }
        }
        val next = mapping()
        fun keep(entry: NodeTuple) {
            val name = key(entry.keyNode)
            next.value.firstOrNull { key(it.keyNode) == name }?.let { previous ->
                entry.keyNode.blockComments = previous.keyNode.blockComments.orEmpty() + entry.keyNode.blockComments.orEmpty()
            }
            next.value.removeAll { key(it.keyNode) == name }
            next.value.add(entry)
        }
        // Matching existing child patches supersede entries expanded from their parent.
        patch.value.sortedBy { key(it.keyNode) }.forEach { entry ->
            val key = key(entry.keyNode)
            val at = patchPath(key)
            when {
                entry === ancestor -> expanded.forEach(::keep)
                !key.startsWith("__") && (at == path || at.startsWith("$path/")) -> Unit
                else -> keep(entry)
            }
        }
        patch.value.clear()
        patch.value.addAll(next.value)
    }

    /** For source-level directive editors only. Compiled scalar values come from librime. */
    fun literalView(source: MappingNode, custom: MappingNode): MappingNode {
        val result = clone(source) as MappingNode
        val patch = member(custom, "patch") as? MappingNode ?: return result
        fun apply(target: MappingNode, raw: String, next: Node, mergeTree: Boolean) {
            val append = raw.endsWith("/+")
            val replace = raw.endsWith("/=")
            val path = if (append || replace) raw.dropLast(2) else raw
            val old = get(target, path)
            val merge = append || mergeTree && !replace
            when {
                append && old is SequenceNode && next is SequenceNode -> old.value.addAll(next.value)
                merge && old is MappingNode && next is MappingNode -> next.value.sortedBy { key(it.keyNode) }.forEach {
                    apply(old, key(it.keyNode), it.valueNode, true)
                }
                merge && old != null && next.tag == Tag.NULL -> Unit
                else -> set(target, path, next)
            }
        }
        patch.value.sortedBy { key(it.keyNode) }.forEach { entry ->
            val raw = key(entry.keyNode)
            if (raw.startsWith("__")) return@forEach
            runCatching { apply(result, raw, clone(entry.valueNode), false) }
        }
        return result
    }

    enum class Type { BOOLEAN, INTEGER, DECIMAL, STRING, YAML }
    fun type(node: Node): Type = when (node.tag) {
        Tag.BOOL -> Type.BOOLEAN
        Tag.INT -> Type.INTEGER
        Tag.FLOAT -> Type.DECIMAL
        Tag.STR, Tag.TIMESTAMP -> Type.STRING
        else -> Type.YAML
    }
    fun text(node: Node): String = if (node is ScalarNode && type(node) != Type.YAML) node.value else dump(node)
    fun boolean(node: Node?) = (node as? ScalarNode)?.value?.lowercase() in setOf("true", "yes", "on")
    fun value(text: String, type: Type): Node = when (type) {
        Type.STRING -> string(text)
        Type.DECIMAL -> {
            val number = yaml.load<Any?>(text) as? Number ?: error("Value must be decimal")
            require(number.toDouble().isFinite()) { "Number must be finite" }
            ScalarNode(Tag.FLOAT, number.toDouble().toString(), null, null, DumperOptions.ScalarStyle.PLAIN)
        }
        else -> parse(text).also { node ->
            require(type == Type.YAML || this.type(node) == type) { "Value must be ${type.name.lowercase()}" }
        }
    }

    data class Field(val path: String, val value: String, val type: Type, val help: String)
    fun fields(source: MappingNode, effective: MappingNode, custom: MappingNode, direct: Boolean): List<Field> {
        val result = mutableListOf<Field>()
        val literal = if (direct) source else literalView(source, custom)
        val catalog = if (direct) source else (clone(source) as MappingNode).also { root ->
            fun includeAddedFields(target: MappingNode, patched: MappingNode) {
                patched.value.forEach { entry ->
                    val name = key(entry.keyNode)
                    val original = member(target, name)
                    when {
                        original == null -> target.value.add(NodeTuple(clone(entry.keyNode), clone(entry.valueNode)))
                        original is MappingNode && entry.valueNode is MappingNode -> includeAddedFields(original, entry.valueNode as MappingNode)
                    }
                }
            }
            // Custom-only fields must remain editable after saving and reopening the page.
            // Keep source directives as the catalog for fields that already exist there.
            includeAddedFields(root, literal)
        }
        fun visit(map: MappingNode, prefix: String, depth: Int) {
            map.value.forEach { entry ->
                val name = key(entry.keyNode)
                if (name.startsWith("__")) return@forEach
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val raw = entry.valueNode
                val structured = raw is MappingNode && (raw.value.any { key(it.keyNode).startsWith("__") || '/' in key(it.keyNode) } || depth >= 2)
                if (raw is MappingNode && !structured && raw.value.isNotEmpty()) visit(raw, path, depth + 1)
                else {
                    val node = if (direct) raw else if (structured) get(literal, path) ?: raw else get(effective, path) ?: get(literal, path) ?: raw
                    val help = (entry.keyNode.blockComments.orEmpty() + raw.inLineComments.orEmpty()).map { it.value.trim() }.filter { it.isNotBlank() }.joinToString("\n")
                    result += Field(path, text(node), type(node), help)
                }
            }
        }
        visit(catalog, "", 0)
        return result
    }

    companion object { const val MAX_TEXT = 2 * 1024 * 1024; private const val WRAPPER = "__wanxiang_value__" }
}
