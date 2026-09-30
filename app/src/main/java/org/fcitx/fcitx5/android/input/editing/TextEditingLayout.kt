/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils

/** A parsed key from the user-editable `text_editor` layout. */
data class TextEditingLayoutKey(
    val keyDef: KeyDef,
    val tapAction: KeyAction?,
    val repeatAction: KeyAction?,
    val longPressAction: KeyAction?
) {
    val appearance: KeyDef.Appearance
        get() = keyDef.appearance
}

data class TextEditingLayout(val rows: List<List<TextEditingLayoutKey>>) {
    val isEmpty: Boolean get() = rows.all { it.isEmpty() }
}

/**
 * Reads and parses the same `text_editor` layout used by the keyboard layout editor.
 * [fromRows] is also used by the in-memory preview, so unsaved editor changes are reflected
 * by the Foxy editor with the exact same KeyDef parser as the real keyboard.
 */
object TextEditingLayoutLoader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(): TextEditingLayout? {
        val root = loadRootJson() ?: return null
        val rowsElement = layoutRowsElement(root[LAYOUT_NAME]) ?: return null
        return fromRows(LayoutJsonUtils.parseLayoutRows(rowsElement))
    }

    fun fromRows(rows: List<List<Map<String, Any?>>>): TextEditingLayout? {
        val parsedRows = rows.map { row ->
            row.mapNotNull { rawKey ->
                val keyObject = JsonObject(
                    rawKey.entries.associate { (key, value) ->
                        key to LayoutJsonUtils.convertToJsonProperty(value)
                    }
                )
                val keyJson = runCatching { LayoutJsonUtils.parseKeyJson(keyObject) }.getOrNull()
                    ?: return@mapNotNull null
                val keyDef = runCatching { LayoutJsonUtils.createKeyDef(keyJson) }.getOrNull()
                    ?: return@mapNotNull null
                val behaviors = keyDef.behaviors
                TextEditingLayoutKey(
                    keyDef = keyDef,
                    tapAction = behaviors.filterIsInstance<KeyDef.Behavior.Press>()
                        .firstOrNull()?.action ?: keyJson.tap,
                    repeatAction = behaviors.filterIsInstance<KeyDef.Behavior.Repeat>()
                        .firstOrNull()?.action,
                    longPressAction = behaviors.filterIsInstance<KeyDef.Behavior.LongPress>()
                        .firstOrNull()?.action ?: keyJson.longPress
                )
            }
        }.filter { it.isNotEmpty() }
        return TextEditingLayout(parsedRows).takeUnless { it.isEmpty }
    }

    private fun loadRootJson(): JsonObject? {
        val provider = ConfigProviders.provider
        provider.textKeyboardLayoutJson()?.let { return it }
        val file = provider.textKeyboardLayoutFile() ?: return null
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            val source = LayoutJsonUtils.removeJsonComments(file.readText())
            json.parseToJsonElement(source).jsonObject
        }.getOrNull()
    }

    private fun layoutRowsElement(element: JsonElement?): JsonArray? = when (element) {
        is JsonArray -> element
        is JsonObject -> (element["default"] as? JsonArray)
            ?: (element[""] as? JsonArray)
        else -> null
    }

    private const val LAYOUT_NAME = "text_editor"
}
