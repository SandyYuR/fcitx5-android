/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import org.fcitx.fcitx5.android.data.WanxiangAdvancedFiles
import org.fcitx.fcitx5.android.data.WanxiangYaml
import org.fcitx.fcitx5.android.utils.FileUtil
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.File

/** One selectable input type declared by the schema's `input_types/options`. */
internal data class RimeInputType(val option: String, val label: String)

/**
 * Input types declared by the active scheme, plus the patch that currently overrides them.
 *
 * The list is never stored by the app: it is read from the loaded scheme each time so that a
 * scheme update (or a different working directory) is reflected without migration.
 */
internal data class RimeInputTypes(
    val types: List<RimeInputType>,
    val enabled: List<String>,
    val reset: String?,
    val schemaId: String,
    val customFile: File
) {
    val current: RimeInputType?
        get() = types.firstOrNull { it.option == reset }
            ?: enabled.firstNotNullOfOrNull { option -> types.firstOrNull { it.option == option } }

    fun summary(): String = current?.label ?: enabled.joinToString("、")
}

/**
 * Reads the evo-only `input_types` block and edits only `input_types/enabled` and
 * `input_types/reset` inside the active scheme's custom patch.
 *
 * [activeSchema] is the schema id the engine would activate (the settings page reads it from the
 * running engine). Binding to it prevents a scheme that happens to sit in the same directory from
 * being read or, worse, rewritten. Only when the caller cannot provide an id does a sorted
 * discovery fall back to the first schema that declares `input_types`.
 *
 * The patch is edited line by line on purpose: the file belongs to the user and may carry
 * comments and unrelated overrides (page size, candidates), which a YAML round-trip would
 * reflow. The rewritten text is parsed and checked before it is written atomically.
 */
internal object RimeInputTypesRepository {
    private const val SCHEMA_SUFFIX = ".schema.yaml"
    private const val CUSTOM_SUFFIX = ".custom.yaml"
    private val SAFE_SCHEMA_ID = Regex("[A-Za-z0-9._-]+")

    fun read(user: File, activeSchema: String? = null): RimeInputTypes? {
        if (!user.isDirectory) return null
        val yaml = WanxiangYaml()
        val schema = findSchema(user, activeSchema, yaml) ?: return null
        val group = effectiveGroup(yaml, schema.group, schema.customFile) ?: return null
        val types = parseTypes(yaml, yaml.member(group, "options") as? SequenceNode)
        if (types.isEmpty()) return null
        val enabledNode = yaml.member(group, "enabled")
        // librime treats a missing enabled list as "all declared options"; only an explicit
        // empty list means nothing can be activated.
        var enabled = if (enabledNode == null) types.map { it.option }
        else stringList(enabledNode).orEmpty()
        var reset = (yaml.member(group, "reset") as? ScalarNode)?.value?.trim()
            ?.takeIf { it.isNotEmpty() }
        val known = types.map { it.option }.toSet()
        enabled = enabled.filter { it in known }.distinct()
        // InputTypesConfig::Load rejects a reset that is not enabled, so it is not active.
        if (reset !in known || reset !in enabled) reset = null
        return RimeInputTypes(types, enabled, reset, schema.id, schema.customFile)
    }

    /** Rewrites both keys so that exactly one type is built and active. */
    fun select(user: File, option: String, activeSchema: String? = null): RimeInputTypes {
        val state = read(user, activeSchema) ?: error("当前方案未声明输入类型")
        require(state.types.any { it.option == option }) { "当前方案不支持该输入类型：$option" }
        val file = state.customFile
        val original = if (file.isFile) WanxiangAdvancedFiles.read(file) else ""
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val updated = rewrite(original.replace("\r\n", "\n"), option)
        validate(updated, option)
        val output = if (newline == "\r\n") updated.replace("\n", "\r\n") else updated
        FileUtil.writeAtomically(file, output)
        return read(user, activeSchema) ?: error("写入后无法读取输入类型")
    }

    private data class SchemaTypes(
        val id: String,
        val group: MappingNode,
        val customFile: File
    )

    private fun findSchema(user: File, activeSchema: String?, yaml: WanxiangYaml): SchemaTypes? {
        val active = activeSchema?.trim().orEmpty()
        val files = if (active.isNotEmpty()) {
            if (!SAFE_SCHEMA_ID.matches(active)) return null
            listOf(File(user, active + SCHEMA_SUFFIX))
        } else {
            user.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(SCHEMA_SUFFIX) }
                .sortedBy { it.name }
        }
        for (file in files.distinctBy { it.absolutePath }) {
            if (!file.isFile) continue
            val schemaId = file.name.removeSuffix(SCHEMA_SUFFIX)
            if (!SAFE_SCHEMA_ID.matches(schemaId)) continue
            val root = runCatching { yaml.document(WanxiangAdvancedFiles.read(file)) }.getOrNull()
                ?: continue
            val group = yaml.get(root, "input_types") as? MappingNode ?: continue
            if (parseTypes(yaml, yaml.member(group, "options") as? SequenceNode).isEmpty()) continue
            return SchemaTypes(schemaId, group, File(user, schemaId + CUSTOM_SUFFIX))
        }
        return null
    }

    /**
     * Applies the custom patch with Rime's compiler semantics: patch keys run in ConfigMap
     * (lexicographic) order, a whole-node patch replaces the group instead of merging it, and
     * only /+ merges a map or appends a list. [WanxiangYaml.literalView] already implements
     * those rules, so reuse it over a wrapper holding just the input_types group.
     */
    private fun effectiveGroup(yaml: WanxiangYaml, source: MappingNode, customFile: File): MappingNode? {
        if (!customFile.isFile) return source
        val custom = runCatching { patch(yaml, customFile) }.getOrNull() ?: return null
        val base = yaml.mapping()
        yaml.put(base, "input_types", yaml.clone(source))
        val patched = yaml.mapping()
        yaml.put(patched, "patch", yaml.clone(custom))
        return yaml.member(yaml.literalView(base, patched), "input_types") as? MappingNode
    }

    private fun parseTypes(yaml: WanxiangYaml, sequence: SequenceNode?): List<RimeInputType> =
        sequence?.value.orEmpty().mapNotNull { node ->
            val entry = node as? MappingNode ?: return@mapNotNull null
            val option = (yaml.member(entry, "option") as? ScalarNode)?.value?.trim().orEmpty()
            if (option.isEmpty()) return@mapNotNull null
            val label = (yaml.member(entry, "label") as? ScalarNode)?.value?.trim()
                ?.takeIf { it.isNotEmpty() } ?: option
            RimeInputType(option, label)
        }.distinctBy { it.option }

    private fun patch(yaml: WanxiangYaml, file: File): MappingNode =
        patch(yaml, WanxiangAdvancedFiles.read(file))

    private fun patch(yaml: WanxiangYaml, text: String): MappingNode {
        val root = yaml.document(text)
        return yaml.member(root, "patch") as? MappingNode ?: error("补丁文件缺少 patch 节点")
    }

    /** Accepts both `input_types/enabled` and the nested `input_types: { enabled: ... }` form. */
    private fun scoped(yaml: WanxiangYaml, patch: MappingNode, path: String): Node? =
        yaml.member(patch, path) ?: yaml.get(patch, path)

    private fun stringList(node: Node?): List<String>? = when (node) {
        null -> null
        is ScalarNode -> node.value.trim().takeIf { it.isNotEmpty() }?.let(::listOf)
        is SequenceNode -> node.value.mapNotNull {
            (it as? ScalarNode)?.value?.trim()?.takeIf(String::isNotEmpty)
        }
        else -> null
    }

    private class PlannedEdit {
        val replace = mutableMapOf<Int, String>()
        val remove = mutableSetOf<Int>()
        val insert = mutableMapOf<Int, MutableList<String>>()
    }

    /**
     * Rewrites only the two keys while preserving comments, key order, indentation and line
     * endings. Both the dotted (`input_types/enabled`) and the nested form are supported,
     * including block sequences; anything the editor cannot rewrite safely is rejected before
     * the file is touched.
     */
    private fun rewrite(text: String, option: String): String {
        val lines = text.split("\n").toMutableList()
        val patchIndex = lines.indexOfFirst(::isPatchHeader)
        if (patchIndex < 0) {
            require(lines.none { it.trimStart().startsWith("patch:") }) {
                "补丁文件的 patch 节点形式不受支持"
            }
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            val output = mutableListOf<String>()
            output.addAll(lines)
            if (output.isNotEmpty()) output.add("")
            output.add("patch:")
            output.add("  input_types/enabled: [$option]")
            output.add("  input_types/reset: $option")
            return output.joinToString("\n") + "\n"
        }
        val patchIndent = indentOf(lines[patchIndex])
        val patchEnd = blockEnd(lines, patchIndex, patchIndent, lines.size)
        val childIndent = firstChildIndent(lines, patchIndex + 1, patchEnd, patchIndent)
        val nested = (patchIndex + 1 until patchEnd).filter { i ->
            indentOf(lines[i]) == childIndent.length && isKeyLine(lines[i], "input_types")
        }
        val dotted = (patchIndex + 1 until patchEnd).filter { i ->
            isKeyLine(lines[i], "input_types/enabled") || isKeyLine(lines[i], "input_types/reset")
        }
        require(nested.size <= 1) { "补丁包含重复的 input_types 节点" }
        require(dotted.isEmpty() || nested.isEmpty()) {
            "补丁同时使用了 input_types 与 input_types/enabled 两种写法"
        }
        val edit = PlannedEdit()
        if (nested.isNotEmpty()) {
            val header = nested.single()
            val nestedIndent = indentOf(lines[header])
            val nestedEnd = blockEnd(lines, header, nestedIndent, patchEnd)
            val targetIndent = firstChildIndent(lines, header + 1, nestedEnd, nestedIndent)
            val enabled = uniqueKey(lines, header + 1 until nestedEnd, "enabled")
            val reset = uniqueKey(lines, header + 1 until nestedEnd, "reset")
            plan(edit, lines, enabled, "enabled", "[$option]", targetIndent, header + 1, nestedEnd)
            plan(edit, lines, reset, "reset", option, targetIndent, header + 1, nestedEnd)
        } else {
            val enabled = uniqueKey(lines, patchIndex + 1 until patchEnd, "input_types/enabled")
            val reset = uniqueKey(lines, patchIndex + 1 until patchEnd, "input_types/reset")
            plan(edit, lines, enabled, "input_types/enabled", "[$option]", childIndent, patchIndex + 1, patchEnd)
            plan(edit, lines, reset, "input_types/reset", option, childIndent, patchIndex + 1, patchEnd)
        }
        val output = mutableListOf<String>()
        for (i in lines.indices) {
            edit.insert[i]?.let(output::addAll)
            if (i in edit.remove) continue
            output.add(edit.replace[i] ?: lines[i])
        }
        edit.insert[lines.size]?.let(output::addAll)
        return output.joinToString("\n")
    }

    private fun plan(
        edit: PlannedEdit,
        lines: MutableList<String>,
        index: Int,
        key: String,
        value: String,
        indent: String,
        insertAt: Int,
        limit: Int
    ) {
        if (index < 0) {
            edit.insert.getOrPut(insertAt) { mutableListOf() }.add("$indent$key: $value")
            return
        }
        val line = lines[index]
        val keyIndent = indentOf(line)
        val comment = commentOf(line.substringAfter(':', ""))
        edit.replace[index] = "${" ".repeat(keyIndent)}$key: $value$comment"
        val inline = line.substringAfter(':', "").substringBefore('#').trim()
        if (inline.isNotEmpty()) return
        val end = blockEnd(lines, index, keyIndent, limit)
        for (i in index + 1 until end) {
            val item = lines[i]
            if (item.isBlank() || item.trimStart().startsWith("#")) continue
            require(item.trimStart() == "-" || item.trimStart().startsWith("- ")) {
                "输入类型配置形式不受支持：$key"
            }
            edit.remove.add(i)
        }
    }

    private fun uniqueKey(lines: List<String>, range: IntRange, key: String): Int {
        val matches = range.filter { isKeyLine(lines[it], key) }
        require(matches.size <= 1) { "补丁包含重复的输入类型配置：$key" }
        return matches.firstOrNull() ?: -1
    }

    private fun isPatchHeader(line: String): Boolean {
        val trimmed = line.trimEnd()
        if (!trimmed.startsWith("patch:")) return false
        val rest = trimmed.substring("patch:".length).trim()
        return rest.isEmpty() || rest.startsWith("#")
    }

    private fun isKeyLine(line: String, key: String): Boolean {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith(key)) return false
        return trimmed.substring(key.length).trimStart().startsWith(":")
    }

    private fun indentOf(line: String): Int = line.takeWhile { it == ' ' }.length

    private fun blockEnd(lines: List<String>, keyIndex: Int, keyIndent: Int, limit: Int): Int {
        var i = keyIndex + 1
        while (i < limit) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                i++
                continue
            }
            val indent = indentOf(line)
            // YAML allows a block sequence to sit at the same indentation as its key
            // ("enabled:" followed by "- zrm"). Keep those items inside the block; a regular
            // sibling key at the same indentation still terminates it.
            if (indent < keyIndent) break
            if (indent == keyIndent && !line.trimStart().startsWith("-")) break
            i++
        }
        return i
    }

    private fun firstChildIndent(lines: List<String>, from: Int, to: Int, parentIndent: Int): String {
        for (i in from until to) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val indent = indentOf(line)
            if (indent > parentIndent) return " ".repeat(indent)
        }
        return " ".repeat(parentIndent + 2)
    }

    /** Keeps the trailing comment (and its spacing) of an edited line. */
    private fun commentOf(value: String): String {
        val trimmed = value.trimStart()
        if (trimmed.startsWith("#")) return " $trimmed"
        val marker = Regex("\\s#").find(value) ?: return ""
        return value.substring(marker.range.first)
    }

    private fun validate(text: String, option: String) {
        val yaml = WanxiangYaml()
        val patch = patch(yaml, text)
        require(stringList(scoped(yaml, patch, "input_types/enabled")) == listOf(option)) {
            "补丁未写入所选输入类型"
        }
        val reset = (scoped(yaml, patch, "input_types/reset") as? ScalarNode)?.value?.trim()
        require(reset == option) { "补丁未写入默认输入类型" }
    }
}

/** Recognizes the evo engine variants whose scheme declares `input_types`. */
internal object RimeEvoEngines {
    private const val EVO_PACKAGE = "org.rime.plugin.sandyyur.librime_evo"

    fun isEvo(state: RimeEngineState): Boolean = when (state.active.kind) {
        RimeEngineKind.BUILTIN -> false
        RimeEngineKind.PLUGIN -> {
            val plugin = state.plugins.firstOrNull { it.packageName == state.active.id }
            if (plugin == null) {
                state.active.id == EVO_PACKAGE
            } else {
                plugin.packageName == EVO_PACKAGE || looksEvo(
                    plugin.displayName,
                    plugin.providerId,
                    plugin.soname,
                    plugin.runtimeInfo.librimeVersion,
                    plugin.staticInfo.sourceName
                )
            }
        }
        RimeEngineKind.IMPORTED -> {
            val imported = state.imported.firstOrNull { it.id == state.active.id }
            imported != null && looksEvo(
                imported.displayName,
                imported.storedFileName,
                imported.runtimeInfo.librimeVersion,
                imported.staticInfo.sourceName
            )
        }
    }

    private fun looksEvo(vararg values: String?): Boolean =
        values.any { it?.contains("evo", ignoreCase = true) == true }
}
