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
    val customFile: File
) {
    val current: RimeInputType?
        get() = types.firstOrNull { it.option == reset }
            ?: enabled.firstNotNullOfOrNull { option -> types.firstOrNull { it.option == option } }

    fun summary(): String = current?.label ?: enabled.joinToString("、")
}

/**
 * Reads the evo-only `input_types` block and edits only `input_types/enabled` and
 * `input_types/reset` inside the scheme's custom patch.
 *
 * The patch is edited line by line on purpose: the file belongs to the user and may carry
 * comments and unrelated overrides (page size, candidates), which a YAML round-trip would
 * reflow. The rewritten text is parsed and checked before it is written atomically.
 */
internal object RimeInputTypesRepository {
    private const val SCHEMA_FILE = "wanxiang.schema.yaml"
    private const val CUSTOM_FILE = "wanxiang.custom.yaml"

    fun read(user: File): RimeInputTypes? {
        if (!user.isDirectory) return null
        val yaml = WanxiangYaml()
        val schema = findSchema(user, yaml) ?: return null
        var enabled = schema.enabled
        var reset = schema.reset
        val customFile = File(user, CUSTOM_FILE)
        if (customFile.isFile) {
            val patch = runCatching { patch(yaml, customFile) }.getOrNull()
            if (patch != null) {
                stringList(scoped(yaml, patch, "input_types/enabled"))
                    ?.takeIf { it.isNotEmpty() }?.let { enabled = it }
                (scoped(yaml, patch, "input_types/reset") as? ScalarNode)?.value?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { reset = it }
            }
        }
        val known = schema.types.map { it.option }.toSet()
        enabled = enabled.filter { it in known }
        if (reset !in known) reset = null
        if (enabled.isEmpty()) enabled = listOfNotNull(reset)
        return RimeInputTypes(schema.types, enabled, reset, customFile)
    }

    /** Rewrites both keys so that exactly one type is built and active. */
    fun select(user: File, option: String): RimeInputTypes {
        val state = read(user) ?: error("当前方案未声明输入类型")
        require(state.types.any { it.option == option }) { "当前方案不支持该输入类型：$option" }
        val file = File(user, CUSTOM_FILE)
        val original = if (file.isFile) WanxiangAdvancedFiles.read(file) else ""
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val updated = rewrite(original.replace("\r\n", "\n"), option)
        validate(updated, option)
        val output = if (newline == "\r\n") updated.replace("\n", "\r\n") else updated
        FileUtil.writeAtomically(file, output)
        return read(user) ?: error("写入后无法读取输入类型")
    }

    private data class SchemaTypes(
        val types: List<RimeInputType>,
        val enabled: List<String>,
        val reset: String?
    )

    private fun findSchema(user: File, yaml: WanxiangYaml): SchemaTypes? {
        val candidates = buildList {
            add(File(user, SCHEMA_FILE))
            addAll(
                user.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".schema.yaml") }
                    .sortedBy { it.name }
            )
        }.distinctBy { it.absolutePath }
        for (file in candidates) {
            if (!file.isFile) continue
            val root = runCatching { yaml.document(WanxiangAdvancedFiles.read(file)) }.getOrNull()
                ?: continue
            val group = yaml.get(root, "input_types") as? MappingNode ?: continue
            val types = parseTypes(yaml, yaml.member(group, "options") as? SequenceNode)
            if (types.isEmpty()) continue
            return SchemaTypes(
                types,
                stringList(yaml.member(group, "enabled")).orEmpty(),
                (yaml.member(group, "reset") as? ScalarNode)?.value?.trim()?.takeIf { it.isNotEmpty() }
            )
        }
        return null
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

    private fun rewrite(text: String, option: String): String {
        val lines = text.split("\n").toMutableList()
        val patchIndex = lines.indexOfFirst { it.trimEnd() == "patch:" }
        if (patchIndex < 0) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            if (lines.isNotEmpty()) lines.add("")
            lines.add("patch:")
            lines.add("  input_types/enabled: [$option]")
            lines.add("  input_types/reset: $option")
            return lines.joinToString("\n") + "\n"
        }
        var end = lines.size
        for (i in patchIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (line.trimStart().startsWith("#")) continue
            if (!line.startsWith(" ")) {
                end = i
                break
            }
        }
        val nestedIndex = (patchIndex + 1 until end).firstOrNull { lines[it].trimEnd() == "  input_types:" || lines[it].trimEnd().endsWith(" input_types:") }
        if (nestedIndex != null) {
            val indent = lines[nestedIndex].takeWhile { c -> c == ' ' }
            var nestedEnd = end
            for (i in nestedIndex + 1 until end) {
                val line = lines[i]
                if (line.isBlank() || line.trimStart().startsWith("#")) continue
                if (line.takeWhile { c -> c == ' ' }.length <= indent.length) {
                    nestedEnd = i
                    break
                }
            }
            var enabledIndex = -1
            var resetIndex = -1
            var childIndent = indent + "  "
            for (i in nestedIndex + 1 until nestedEnd) {
                val trimmed = lines[i].trimStart()
                when {
                    trimmed.startsWith("enabled:") -> {
                        enabledIndex = i
                        childIndent = lines[i].takeWhile { c -> c == ' ' }
                    }
                    trimmed.startsWith("reset:") -> {
                        resetIndex = i
                        childIndent = lines[i].takeWhile { c -> c == ' ' }
                    }
                }
            }
            if (enabledIndex >= 0) lines[enabledIndex] = replaceValue(lines[enabledIndex], "enabled", "[$option]")
            if (resetIndex >= 0) lines[resetIndex] = replaceValue(lines[resetIndex], "reset", option)
            if (enabledIndex < 0 || resetIndex < 0) {
                val insertion = mutableListOf<String>()
                if (enabledIndex < 0) insertion.add(childIndent + "enabled: [$option]")
                if (resetIndex < 0) insertion.add(childIndent + "reset: $option")
                lines.addAll(nestedIndex + 1, insertion)
            }
            return lines.joinToString("\n")
        }
        var enabledIndex = -1
        var resetIndex = -1
        var indent = "  "
        for (i in patchIndex + 1 until end) {
            val trimmed = lines[i].trimStart()
            when {
                trimmed.startsWith("input_types/enabled:") -> {
                    enabledIndex = i
                    indent = lines[i].takeWhile { c -> c == ' ' }
                }
                trimmed.startsWith("input_types/reset:") -> {
                    resetIndex = i
                    if (indent == "  ") indent = lines[i].takeWhile { c -> c == ' ' }
                }
            }
        }
        if (enabledIndex >= 0) {
            lines[enabledIndex] = replaceValue(lines[enabledIndex], "input_types/enabled", "[$option]")
        }
        if (resetIndex >= 0) {
            lines[resetIndex] = replaceValue(lines[resetIndex], "input_types/reset", option)
        }
        if (enabledIndex < 0 || resetIndex < 0) {
            val insertion = mutableListOf<String>()
            if (enabledIndex < 0) insertion.add(indent + "input_types/enabled: [$option]")
            if (resetIndex < 0) insertion.add(indent + "input_types/reset: $option")
            lines.addAll(patchIndex + 1, insertion)
        }
        return lines.joinToString("\n")
    }

    private fun replaceValue(line: String, key: String, value: String): String {
        val indent = line.takeWhile { it == ' ' }
        val colon = line.indexOf(':', indent.length)
        require(colon > 0) { "无法解析输入类型配置行：$line" }
        val comment = commentOf(line.substring(colon + 1))
        return "$indent$key: $value$comment"
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
