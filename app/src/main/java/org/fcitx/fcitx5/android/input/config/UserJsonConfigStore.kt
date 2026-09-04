/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.File

object UserJsonConfigStore {

    data class JsonSnapshot<T>(
        val value: T,
        val lastModified: Long,
        val file: File?
    )

    /** Details of the most recent config parse failure, for surfacing in the UI. */
    data class ReadFailure(val fileName: String, val cause: Throwable)

    /**
     * The most recent parse failure, or null if the last read succeeded. A failed read
     * returns null and the caller falls back to a built-in default, which used to be
     * completely silent; keeping the reason here lets callers report it.
     */
    @Volatile
    @PublishedApi
    internal var lastFailure: ReadFailure? = null

    val lastReadFailure: ReadFailure?
        get() = lastFailure

    fun clearLastReadFailure() {
        lastFailure = null
    }

    @PublishedApi
    internal val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @PublishedApi
    internal fun cleanJson(content: String, stripLineComments: Boolean): String {
        if (!stripLineComments) return content
        return stripLineComments(content)
    }

    /**
     * Remove `//` line comments from a JSON document.
     *
     * Quote-aware: a `//` inside a string literal (a URL, for instance) is left alone, and
     * escaped quotes do not confuse the string tracking. Line-based so it also handles a
     * trailing comment on the last line, which a `//.*?\n` regex would miss.
     */
    fun stripLineComments(content: String): String =
        content.lineSequence().joinToString("\n") { line ->
            var inString = false
            var index = 0
            while (index < line.length) {
                val c = line[index]
                when {
                    c == '\\' && inString -> index++ // skip the escaped character
                    c == '"' -> inString = !inString
                    !inString && c == '/' && index + 1 < line.length && line[index + 1] == '/' ->
                        return@joinToString line.substring(0, index)
                }
                index++
            }
            line
        }

    inline fun <reified T> readJson(
        file: File?,
        stripLineComments: Boolean = false
    ): JsonSnapshot<T>? {
        if (file == null || !file.exists()) return null
        return try {
            val content = cleanJson(file.readText(), stripLineComments)
            val decoded = parser.decodeFromString<T>(content)
            lastFailure = null
            JsonSnapshot(decoded, file.lastModified(), file)
        } catch (exception: Exception) {
            // Record the failure so callers can tell the user their config could not be
            // parsed, instead of silently falling back to the built-in layout.
            lastFailure = ReadFailure(file.name, exception)
            Timber.w(exception, "Failed to read JSON config: ${file.name}")
            null
        }
    }

    /**
     * Read JSON from memory [JsonObject] instead of file.
     * This avoids disk I/O for temporary/preview data.
     *
     * @param json The in-memory JSON object
     * @param stripLineComments Whether to strip line comments (not applicable for in-memory JSON)
     * @return JsonSnapshot with a synthetic lastModified time
     */
    inline fun <reified T> readJson(
        json: JsonObject?,
        stripLineComments: Boolean = false
    ): JsonSnapshot<T>? {
        if (json == null) return null
        return try {
            val decoded = parser.decodeFromJsonElement<T>(json)
            JsonSnapshot(decoded, System.nanoTime(), null)
        } catch (exception: Exception) {
            Timber.w(exception, "Failed to decode in-memory JSON config")
            null
        }
    }

    fun readFontsetPathMapSnapshot(): Result<JsonSnapshot<Map<String, List<String>>>?> = runCatching {
        val file = UserConfigFiles.fontsetJson()
            ?.takeIf { it.exists() }
            ?: return@runCatching null
        val content = cleanJson(file.readText(), stripLineComments = true)
        val json = parser.parseToJsonElement(content)
        val jsonObject = parser.decodeFromJsonElement<JsonObject>(json)
        val parsed = jsonObject.toMap().mapValues { (_, value) ->
            value.jsonPrimitive.content
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        JsonSnapshot(
            value = parsed,
            lastModified = file.lastModified(),
            file = file
        )
    }

    fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> = runCatching {
        val file = UserConfigFiles.fontsetJson()
            ?: error("Cannot resolve fontset.json path")
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            pathMap.forEach { (key, values) ->
                if (values.isNotEmpty()) {
                    put(key, JsonPrimitive(values.joinToString(",")))
                }
            }
        }
        file.writeText(parser.encodeToJsonElement(json).toString() + "\n")
        file
    }
}
