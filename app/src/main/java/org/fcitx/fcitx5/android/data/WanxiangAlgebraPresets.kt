/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.yaml.snakeyaml.nodes.*

/** Choices come from the user's algebra file. Unknown references retain their relative order. */
internal class WanxiangAlgebraPresets(private val y: WanxiangYaml, private val library: MappingNode, file: String) {
    val namespace = when (file) {
        "wanxiang.schema.yaml" -> "base"
        "wanxiang_pro.schema.yaml" -> "pro"
        "wanxiang_lite.schema.yaml" -> "lite"
        "wanxiang_english.schema.yaml" -> "english"
        "wanxiang_mixedcode.schema.yaml" -> "mixed"
        "wanxiang_reverse.schema.yaml" -> "reverse"
        else -> ""
    }
    private fun keys(name: String) = (y.member(library, name) as? MappingNode)?.value.orEmpty().map { y.key(it.keyNode) }
    val schemes = keys(namespace).filter { it in keys("base") }
    val auxiliary = if (namespace == "pro") keys(namespace).filter { "辅助" in it } else emptyList()
    val strokes = if (namespace == "reverse") keys(namespace).filter { it.matches(Regex("[a-z]{5}")) } else emptyList()
    val extra = library.value.filter { it.valueNode is SequenceNode }.map { y.key(it.keyNode) }
    private fun reference(name: String, group: String = namespace) = "wanxiang_algebra:/$group/$name"
    private fun refs(node: MappingNode): List<Node> = when (val patch = y.member(node, "__patch")) {
        is SequenceNode -> patch.value
        null -> emptyList()
        else -> listOf(patch)
    }
    fun selected(node: MappingNode, options: List<String>, group: String = namespace): Int = options.indexOfFirst { name ->
        refs(node).any { (it as? ScalarNode)?.value == reference(name, group) }
    }
    fun select(node: MappingNode, name: String, aux: Boolean = false, stroke: Boolean = false): MappingNode {
        val options = if (stroke) strokes else if (aux) auxiliary else schemes
        require(name in options)
        val result = y.clone(node) as MappingNode
        val next = refs(result).toMutableList()
        val index = next.indexOfFirst { entry -> options.any { (entry as? ScalarNode)?.value == reference(it) } }
        val replacement = y.string(reference(name))
        if (index >= 0) {
            replacement.blockComments = next[index].blockComments
            replacement.inLineComments = next[index].inLineComments
            next[index] = replacement
            for (i in next.lastIndex downTo index + 1) {
                if (options.any { (next[i] as? ScalarNode)?.value == reference(it) }) next.removeAt(i)
            }
        } else {
            val position = if (aux || stroke) next.indexOfLast { entry -> schemes.any { (entry as? ScalarNode)?.value == reference(it) } }.let { if (it >= 0) it + 1 else next.size }
                else next.indexOfFirst { entry -> auxiliary.any { (entry as? ScalarNode)?.value == reference(it) } }.takeIf { it >= 0 } ?: next.size
            next.add(position.coerceIn(0, next.size), replacement)
        }
        y.put(result, "__patch", y.sequence(next))
        return result
    }
    fun selectedExtra(node: MappingNode) = extra.filter { name -> refs(node).any { (it as? ScalarNode)?.value == "wanxiang_algebra:/$name" } }.toSet()
    fun extras(node: MappingNode, selected: Set<String>): MappingNode {
        require(selected.all { it in extra })
        val result = y.clone(node) as MappingNode
        val old = refs(result)
        val next = old.filterNot { entry -> extra.any { (entry as? ScalarNode)?.value == "wanxiang_algebra:/$it" && it !in selected } }.toMutableList()
        selected.forEach { name ->
            val reference = "wanxiang_algebra:/$name"
            if (next.none { (it as? ScalarNode)?.value == reference }) {
                val rule = y.string(reference)
                if (name.startsWith("模糊音")) {
                    val beforeMain = next.indexOfFirst { entry -> schemes.any { (entry as? ScalarNode)?.value == reference(it) } }
                    next.add(if (beforeMain >= 0) beforeMain else next.size, rule)
                } else next.add(rule)
            }
        }
        y.put(result, "__patch", y.sequence(next))
        return result
    }
}
