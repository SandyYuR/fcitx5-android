/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.fcitx.fcitx5.android.core.RawConfig
import java.io.File

/** The engine's profile names are identifiers; their paths may be outside the app. */
data class RimeDataDirectoryState(
    val current: String,
    val profiles: List<String>,
    val paths: Map<String, String>
) {
    fun portableConfig(): String = buildString {
        append("Current=").append(escapeIni(current)).append('\n')
        append("Profiles=").append(escapeIni(profiles.joinToString("\n"))).append('\n')
        append("ExternalPaths=\nExternalPathsV2=\n")
    }

    companion object {
        const val CONFIG_PATH = "config/conf/rime-data-dir.conf"

        fun externalPathConfig(paths: Map<String, String>): String {
            require(paths.keys.all(::isValidName) && paths.values.all { isAbsolutePath(it) && '\u0000' !in it }) {
                "Invalid external Rime directory paths."
            }
            fun hex(value: String) = value.toByteArray(Charsets.UTF_8)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val legacy = paths.filter { (name, path) -> '|' !in name && '\n' !in path && '\r' !in path }
                .map { (name, path) -> "$name|$path" }.joinToString("\n")
            val encoded = paths.map { (name, path) -> "${hex(name)}|${hex(path)}" }.joinToString("\n")
            return "ExternalPaths=${escapeIni(legacy)}\nExternalPathsV2=${escapeIni(encoded)}\n"
        }

        fun fromConfig(raw: RawConfig, internalRoot: File): RimeDataDirectoryState {
            val cfg = raw.findByName("cfg") ?: raw
            val current = cfg.findByName("Current")?.value.orEmpty().trim().ifEmpty { "rime" }
            val profiles = cfg.findByName("Profiles")?.value.orEmpty().lineSequence()
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
                .ifEmpty { listOf(current) }
            require(profiles.all(::isValidName) && current in profiles) {
                "Invalid Rime data directory state."
            }
            val encoded = cfg.findByName("PathsV2")?.value
            val legacy = cfg.findByName("Paths")?.value
            val paths = if (encoded == null && legacy == null) {
                // Compatibility with engines predating external profiles.
                profiles.associateWith { File(internalRoot, it).absolutePath }
            } else {
                decodePaths(encoded, legacy.orEmpty(), profiles).also {
                    require(it.keys.containsAll(profiles)) { "Incomplete Rime directory paths." }
                }
            }
            return RimeDataDirectoryState(current, profiles, paths)
        }

        fun decodePaths(encoded: String?, legacy: String, profiles: List<String>): Map<String, String> {
            val orderedNames = profiles.sortedByDescending { it.length }
            return (encoded ?: legacy).lineSequence().filter { it.isNotBlank() }.associate { line ->
                val pair = if (encoded != null) {
                    val sep = line.indexOf('|')
                    require(sep > 0) { "Invalid Rime directory paths." }
                    decodeHex(line.substring(0, sep)) to decodeHex(line.substring(sep + 1))
                } else {
                    val name = orderedNames.firstOrNull { line.startsWith("$it|") }
                        ?: throw IllegalArgumentException("Unknown Rime directory profile.")
                    name to line.substring(name.length + 1)
                }
                require(pair.first in profiles && pair.second.startsWith('/') && '\u0000' !in pair.second) {
                    "Invalid Rime directory paths."
                }
                pair
            }
        }

        fun isValidName(name: String): Boolean = name.isNotEmpty() &&
            name.toByteArray(Charsets.UTF_8).size <= 64 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

        internal fun isAbsolutePath(path: String): Boolean = path.startsWith('/') || File(path).isAbsolute

        private fun decodeHex(value: String): String {
            require(value.length % 2 == 0) { "Invalid encoded Rime directory path." }
            val bytes = ByteArray(value.length / 2) { index ->
                val high = value[index * 2].digitToIntOrNull(16)
                val low = value[index * 2 + 1].digitToIntOrNull(16)
                require(high != null && low != null) { "Invalid encoded Rime directory path." }
                ((high shl 4) or low).toByte()
            }
            return bytes.toString(Charsets.UTF_8)
        }

        private fun escapeIni(value: String): String = buildString {
            append('"')
            value.forEach { ch ->
                append(when (ch) {
                    '\\' -> "\\\\"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    '"' -> "\\\""
                    else -> ch.toString()
                })
            }
            append('"')
        }
    }
}
