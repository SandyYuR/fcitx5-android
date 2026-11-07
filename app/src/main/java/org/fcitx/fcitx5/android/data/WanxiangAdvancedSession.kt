/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.Serializable
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangTarget
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.File

/** A detached draft. No live file changes until the engine accepts a verified save plan. */
internal class WanxiangAdvancedSession(val target: WanxiangTarget, val direct: Boolean) {
    val yaml = WanxiangYaml()
    data class Document(val snapshot: WanxiangAdvancedFiles.Snapshot, val source: MappingNode, val custom: MappingNode, val effective: MappingNode)
    @Serializable data class Edit(val file: String, val path: String, val value: String? = null, val reset: Boolean = false)
    @Serializable data class Draft(val target: WanxiangTarget, val direct: Boolean, val snapshots: List<WanxiangAdvancedFiles.Snapshot>,
        val edits: List<Edit>, val extraExpected: Map<String, String?> = emptyMap(), val dictionaries: Map<String, String> = emptyMap())
    val documents = linkedMapOf<String, Document>()
    val edits = linkedMapOf<Pair<String, String>, Edit>()
    val expected = linkedMapOf<String, String?>()
    private val dictionaries = linkedMapOf<String, String>()
    private val extraExpected = linkedMapOf<String, String?>()
    val dirty get() = edits.isNotEmpty()
    val mainFiles get() = listOf("wanxiang.schema.yaml", "wanxiang_pro.schema.yaml", "wanxiang_lite.schema.yaml", "wanxiang_t9.schema.yaml", "wanxiang_t9i.schema.yaml")
        .filter { it in documents }

    fun add(snapshot: WanxiangAdvancedFiles.Snapshot) {
        require(WanxiangAdvancedFiles.source(snapshot.file))
        snapshot.expected.forEach { (name, hash) ->
            if (expected.containsKey(name) && expected[name] != hash) throw WanxiangEditConflict("配置文件已发生变化，请重新加载后继续编辑：$name")
        }
        val source = yaml.document(snapshot.source)
        val custom = yaml.document(snapshot.custom)
        val effective = snapshot.effective?.let(yaml::document) ?: yaml.literalView(source, custom)
        documents[snapshot.file] = Document(snapshot, source, custom, effective)
        expected.putAll(snapshot.expected)
    }

    fun baseline(file: String, path: String): Node? {
        val doc = documents.getValue(file)
        val sourceNode = yaml.get(doc.source, path)
        val directive = sourceNode is MappingNode && sourceNode.value.any { yaml.key(it.keyNode).startsWith("__") }
        return yaml.get(if (direct || WanxiangAdvancedFiles.patchFile(file) == null) doc.source else if (directive) yaml.literalView(doc.source, doc.custom) else doc.effective, path)
    }
    fun value(file: String, path: String): Node? {
        var value = baseline(file, path)
        var copied = false
        edits.values.filter { it.file == file }.forEach { edit ->
            val node = if (edit.reset) {
                if (direct || WanxiangAdvancedFiles.patchFile(file) == null) null else yaml.get(documents.getValue(file).source, edit.path)
            } else yaml.parse(edit.value!!)
            when {
                edit.path == path -> { value = node; copied = !edit.reset }
                path.startsWith("${edit.path}/") -> { value = node?.let { yaml.get(it, path.removePrefix("${edit.path}/")) }; copied = !edit.reset }
                edit.path.startsWith("$path/") -> {
                    val holder = yaml.mapping()
                    yaml.put(holder, "value", if (copied) value ?: yaml.mapping() else value?.let(yaml::clone) ?: yaml.mapping())
                    yaml.set(holder, "value/" + edit.path.removePrefix("$path/"), node?.let(yaml::clone))
                    value = yaml.member(holder, "value"); copied = true
                }
            }
        }
        return value
    }
    fun text(file: String, path: String, fallback: String = "") = value(file, path)?.let(yaml::text) ?: fallback

    fun change(file: String, path: String, node: Node) {
        require(file in documents)
        yaml.path(path)
        require(path != "schema/schema_id") { "方案标识由文件确定，不能在此修改" }
        if (path == "schema") require(yaml.equivalent((node as? MappingNode)?.let { yaml.member(it, "schema_id") }, yaml.get(documents.getValue(file).source, "schema/schema_id")))
        if (path == "menu/page_size") require(node is ScalarNode && node.value.toIntOrNull() in 1..10) { "候选词数量须为 1–10" }
        if (path == "schema_list") require(node is SequenceNode && node.value.isNotEmpty() && node.value.all {
            it is MappingNode && (yaml.member(it, "schema") as? ScalarNode)?.value?.isNotBlank() == true
        }) { "至少启用一个有效方案" }
        val overlaps = edits.values.any { it.file == file && it.path != path && (it.path.startsWith("$path/") || path.startsWith("${it.path}/")) }
        if (!overlaps && yaml.equivalent(node, baseline(file, path))) edits.remove(file to path)
        else { edits.remove(file to path); edits[file to path] = Edit(file, path, yaml.dump(node)) }
    }

    fun reset(file: String, path: String) {
        documents.getValue(file)
        yaml.path(path)
        if (direct || WanxiangAdvancedFiles.patchFile(file) == null) {
            require(path != "schema/schema_id")
            edits[file to path] = Edit(file, path, reset = true)
        } else {
            val next = LinkedHashMap(edits)
            next.entries.removeAll { (key, _) -> key.first == file && (key.second == path || key.second.startsWith("$path/")) }
            val withoutReset = output(file, next.values)
            next[file to path] = Edit(file, path, reset = true)
            // Validate complex parent patches before changing the draft. Cancelling a
            // new field with no saved override must leave the session clean.
            if (yaml.equivalent(withoutReset, output(file, next.values))) next.remove(file to path)
            edits.clear()
            edits.putAll(next)
        }
    }

    fun fields(file: String): List<WanxiangYaml.Field> {
        val doc = documents.getValue(file)
        val base = yaml.fields(doc.source, doc.effective, doc.custom, direct || WanxiangAdvancedFiles.patchFile(file) == null)
        val known = base.map { it.path }.toSet()
        return base.map { field ->
            if (edits.values.none { it.file == file && (it.path == field.path || it.path.startsWith("${field.path}/") || field.path.startsWith("${it.path}/")) }) return@map field
            val node = value(file, field.path)
            field.copy(value = node?.let(yaml::text).orEmpty(), type = node?.let(yaml::type) ?: field.type)
        } + edits.values.filter { it.file == file && it.path !in known }.map {
            val node = value(file, it.path) ?: yaml.string("")
            WanxiangYaml.Field(it.path, yaml.text(node), yaml.type(node), "")
        }
    }

    private fun output(file: String, pending: Collection<Edit> = edits.values): MappingNode {
        val doc = documents.getValue(file)
        val writeSource = direct || WanxiangAdvancedFiles.patchFile(file) == null
        val result = yaml.clone(if (writeSource) doc.source else doc.custom) as MappingNode
        val patch = if (!writeSource) {
            val existing = yaml.member(result, "patch")
            require(existing == null || existing is MappingNode) { "当前补丁使用序列，不能安全合并为字段补丁" }
            (existing as? MappingNode ?: yaml.mapping().also { yaml.put(result, "patch", it) })
        } else null
        pending.filter { it.file == file }.forEach { edit ->
            val node = if (edit.reset) null else yaml.parse(edit.value!!)
            if (writeSource) yaml.set(result, edit.path, node)
            else if (edit.reset) yaml.removePatchOverride(patch!!, edit.path, doc.source)
            else {
                // Rime orders patch keys itself. A later parent edit must remove
                // older child patches, rather than relying on YAML entry order.
                patch!!.value.removeAll { entry ->
                    val key = yaml.key(entry.keyNode)
                    val path = yaml.patchPath(key)
                    key != edit.path && (path == edit.path || path.startsWith("${edit.path}/"))
                }
                val source = yaml.get(doc.source, edit.path)
                val overlap = patch.value.any { entry ->
                    val key = yaml.key(entry.keyNode)
                    val path = yaml.patchPath(key)
                    key != edit.path && (key.startsWith("__") || path.startsWith("${edit.path}/") || edit.path.startsWith("$path/"))
                }
                val sameAsSource = !overlap && node != null && source != null && yaml.equivalent(node, source) &&
                    !edit.path.endsWith("/__patch") && !edit.path.endsWith("/__include")
                yaml.put(patch, edit.path, if (sameAsSource) null else node)
                // The user's latest change follows overlapping inherited patch paths.
                val index = patch.value.indexOfFirst { yaml.key(it.keyNode) == edit.path }
                if (index >= 0) patch.value.add(patch.value.removeAt(index))
            }
        }
        return result
    }

    fun changes(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        edits.values.map { it.file }.distinct().forEach { name ->
            val destination = if (direct) name else WanxiangAdvancedFiles.patchFile(name) ?: name
            val next = output(name)
            val original = if (destination == name) documents.getValue(name).source else documents.getValue(name).custom
            if (!yaml.equivalent(original, next)) result[destination] = yaml.dump(next).also(yaml::document)
        }
        dictionaries.forEach { (name, data) ->
            val dictionary = name.removeSuffix(".dict.yaml")
            if (edits.values.any { !it.reset && (yaml.parse(it.value!!) as? ScalarNode)?.value == dictionary }) result[name] = data
        }
        return result
    }
    fun plan() = WanxiangAdvancedFiles.Plan(target.profile, target.path, expected + extraExpected)
    fun draft() = Draft(target, direct, documents.values.map { it.snapshot }, edits.values.toList(), extraExpected, dictionaries)

    /** Preserve the original dictionary and body; a new name never overwrites an existing dictionary. */
    fun prepareDictionary(oldName: String, newName: String) {
        require(WanxiangAdvancedFiles.writable("$newName.dict.yaml")) { "无效词库名称" }
        val root = File(target.path).canonicalFile
        val destination = WanxiangPayload.child(root, "$newName.dict.yaml")
        if (destination.exists()) {
            require(destination.isFile) { "词库路径不是文件" }
            return
        }
        if (destination.name in dictionaries) return
        require(WanxiangAdvancedFiles.writable("$oldName.dict.yaml")) { "无效词库名称" }
        val source = WanxiangPayload.child(root, "$oldName.dict.yaml")
        val text = dictionaries[source.name] ?: run {
            require(source.isFile) { "找不到原词库：${source.name}，请先下载词库" }
            require(source.isFile && source.length() <= WanxiangYaml.MAX_TEXT) { "词库过大，无法在高级设置中复制" }
            extraExpected[source.name] = WanxiangAdvancedFiles.hash(source)
            WanxiangAdvancedFiles.read(source)
        }
        val marker = Regex("(?m)^\\.\\.\\.[ \\t]*\\r?$").find(text) ?: error("词库缺少 YAML 头结束标记")
        val header = yaml.document(text.substring(0, marker.range.first))
        val body = text.substring(marker.range.last + 1).removePrefix("\n")
        yaml.put(header, "name", yaml.string(newName))
        dictionaries[destination.name] = yaml.dump(header).trimEnd().removeSuffix("...").trimEnd() + "\n...\n" + body
        extraExpected[destination.name] = null
    }

    companion object {
        fun restore(draft: Draft) = WanxiangAdvancedSession(draft.target, draft.direct).apply {
            draft.snapshots.forEach(::add)
            draft.edits.forEach { edit -> require(edit.file in documents); yaml.path(edit.path); edits[edit.file to edit.path] = edit }
            extraExpected.putAll(draft.extraExpected)
            dictionaries.putAll(draft.dictionaries)
        }
    }
}
