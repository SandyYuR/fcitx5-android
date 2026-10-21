/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.core.RawConfig

/** Reads existing profiles without a native config library; only quoted values are escaped. */
internal object IniCodec {
    private val escapes = mapOf('\\' to '\\', '"' to '"', 'n' to '\n', 'f' to '\u000c', 'r' to '\r', 't' to '\t', 'v' to '\u000b')
    private val reverse = escapes.entries.associate { it.value to it.key }
    private val whitespace = " \t\r\n\u000b\u000c"

    fun decode(source: String): RawConfig {
        val root = RawConfig()
        var group = ""
        source.lineSequence().forEach { raw ->
            val line = raw.trim { it in whitespace }
            when {
                line.isEmpty() || line.startsWith('#') -> Unit
                line.startsWith('[') && line.endsWith(']') -> {
                    group = line.substring(1, line.length - 1)
                    var node = root
                    group.split('/').filter { it.isNotEmpty() }.forEach { node = node.getOrCreate(it) }
                }
                '=' in line -> {
                    val split = line.indexOf('=')
                    val value = unquote(line.substring(split + 1)) ?: return@forEach
                    val path = (if (group.isEmpty()) "" else "$group/") + line.substring(0, split)
                    var node = root
                    path.split('/').filter { it.isNotEmpty() }.forEach { node = node.getOrCreate(it) }
                    node.value = value
                }
            }
        }
        return root
    }

    private fun unquote(value: String): String? {
        if (value.length < 2 || value.first() != '"' || value.last() != '"') return value
        val result = StringBuilder()
        var i = 1
        while (i < value.length - 1) {
            val char = value[i++]
            if (char == '\\') {
                if (i >= value.length - 1) return null
                val escaped = value[i++]
                result.append(escapes[escaped] ?: escaped)
            } else if (char == '"') return null else result.append(char)
        }
        return result.toString()
    }

    fun encode(root: RawConfig): String = buildString {
        fun quoted(value: String): String {
            if (value.none { it in whitespace || it == '"' || it == '\\' }) return value
            return buildString {
                append('"')
                value.forEach { c -> reverse[c]?.let { append('\\'); append(it) } ?: append(c) }
                append('"')
            }
        }
        fun visit(node: RawConfig, path: String) {
            val children = node.subItems.orEmpty()
            val values = children.filter { it.subItems.isNullOrEmpty() || it.value.isNotEmpty() }
            if (values.isNotEmpty()) {
                if (path.isNotEmpty()) append('[').append(path).append("]\n")
                values.forEach {
                    if (it.comment.isNotEmpty() && '\n' !in it.comment) append("# ").append(it.comment).append('\n')
                    append(it.name).append('=').append(quoted(it.value)).append('\n')
                }
                append('\n')
            }
            children.filter { !it.subItems.isNullOrEmpty() }.forEach {
                visit(it, if (path.isEmpty()) it.name else "$path/${it.name}")
            }
        }
        visit(root, "")
    }
}
