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
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils

/** A single touch target, including keys spanning several editor rows. */
data class TextEditingGridCell(val row: Int, val column: Int, val columnSpan: Int, val rowSpan: Int)

data class TextEditingLayoutKey(
    val keyDef: KeyDef,
    val tapAction: KeyAction?,
    val repeatAction: KeyAction?,
    val longPressAction: KeyAction?,
    val row: Int = 0,
    val index: Int = 0,
    val gridCell: TextEditingGridCell? = null,
    val swipeBehavior: KeyDef.Behavior.Swipe? = null
) {
    val appearance: KeyDef.Appearance get() = keyDef.appearance
}

data class TextEditingLayout(val rows: List<List<TextEditingLayoutKey>>) {
    val isEmpty: Boolean get() = rows.all { it.isEmpty() }
    val columnCount: Int get() = rows.flatten().maxOfOrNull {
        it.gridCell?.let { cell -> cell.column + cell.columnSpan } ?: 0
    } ?: 0
}

/** Shared parser for the runtime panel and the unsaved layout editor preview. */
object TextEditingLayoutLoader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val supportedTypes = setOf(
        "AlphabetKey", "CapsKey", "LayoutSwitchKey", "CommaKey", "LanguageKey", "SpaceKey",
        "SymbolKey", "ReturnKey", "BackspaceKey", "NumPadKey", "MiniSpaceKey", "MacroKey"
    )

    fun load(preferredBases: List<String> = listOf("rime", "default")): TextEditingLayout? {
        val root = loadRootJson() ?: return null
        return fromJson(root, AppPrefs.getInstance().textEditing.layoutOverride.getValue(), preferredBases)
    }

    fun fromJson(root: JsonObject, preference: String, preferredBases: List<String>): TextEditingLayout? {
        if (preference.trim() == TextEditingLayoutResolver.BUILTIN) return null
        val selected = TextEditingLayoutResolver.resolve(root, preference, preferredBases) {
            parseEntry(root, it) != null
        }
        return parseEntry(root, selected)
    }

    /** The swipe pad keeps its independent, backwards compatible bottom bar. */
    fun loadSwipeBar(): TextEditingLayout? = loadRootJson()?.let { parseEntry(it, "text_editor") }

    private fun parseEntry(root: JsonObject, key: String?): TextEditingLayout? {
        key ?: return null
        val base = key.substringBefore(':')
        val sub = key.substringAfter(':', "")
        val element = if (sub.isEmpty()) root[base] else (root[base] as? JsonObject)?.get(sub)
        val rows = layoutRowsElement(element) ?: return null
        return runCatching { fromRows(LayoutJsonUtils.parseLayoutRows(rows)) }.getOrNull()
    }

    fun fromRows(rows: List<List<Map<String, Any?>>>): TextEditingLayout? {
        if (rows.isEmpty() || rows.size > 32 || rows.sumOf { it.size } > 512) return null
        val grid = rows.flatten().any { "column" in it || "columnSpan" in it || "rowSpan" in it }
        val occupied = mutableSetOf<Pair<Int, Int>>()
        val parsedRows = rows.mapIndexed { rowIndex, row ->
            row.mapIndexedNotNull { keyIndex, rawKey ->
                val cell = if (grid) {
                    fun integer(name: String, default: Int? = null): Int? {
                        val raw = rawKey[name] ?: return default
                        val number = (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull() ?: return null
                        return number.takeIf { it.isFinite() && it == it.toInt().toDouble() }?.toInt()
                    }
                    val column = integer("column") ?: return null
                    val columns = integer("columnSpan", 1) ?: return null
                    val height = integer("rowSpan", 1) ?: return null
                    if (column !in 0..127 || columns !in 1..128 || column + columns > 128 ||
                        height !in 1..32 || rowIndex + height > rows.size) return null
                    for (r in rowIndex until rowIndex + height) {
                        for (c in column until column + columns) if (!occupied.add(r to c)) return null
                    }
                    TextEditingGridCell(rowIndex, column, columns, height)
                } else null
                val keyObject = JsonObject(rawKey.mapValues { LayoutJsonUtils.convertToJsonProperty(it.value) })
                val keyJson = runCatching { LayoutJsonUtils.parseKeyJson(keyObject) }.getOrNull()
                if (keyJson == null || keyJson.type !in supportedTypes) {
                    if (grid) return null else return@mapIndexedNotNull null
                }
                val keyDef = runCatching { LayoutJsonUtils.createKeyDef(keyJson) }.getOrNull()
                if (keyDef == null) { if (grid) return null else return@mapIndexedNotNull null }
                val behaviors = keyDef.behaviors
                val tap = behaviors.filterIsInstance<KeyDef.Behavior.Press>().firstOrNull()?.action ?: keyJson.tap
                val navigation = (tap as? KeyAction.SymAction)?.sym?.keyCode in setOf(
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN
                )
                TextEditingLayoutKey(
                    keyDef,
                    tap,
                    behaviors.filterIsInstance<KeyDef.Behavior.Repeat>().firstOrNull()?.action ?: tap.takeIf { navigation },
                    behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().firstOrNull()?.action ?: keyJson.longPress,
                    rowIndex,
                    keyIndex,
                    cell,
                    behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().firstOrNull()
                )
            }
        }
        return TextEditingLayout(parsedRows).takeUnless { it.isEmpty }
    }

    private fun loadRootJson(): JsonObject? {
        val provider = ConfigProviders.provider
        provider.textKeyboardLayoutJson()?.let { return it }
        val file = provider.textKeyboardLayoutFile() ?: return null
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            json.parseToJsonElement(LayoutJsonUtils.removeJsonComments(file.readText())).jsonObject
        }.getOrNull()
    }

    private fun layoutRowsElement(element: JsonElement?): JsonArray? = when (element) {
        is JsonArray -> element
        is JsonObject -> layoutRowsElement(element["default"] ?: element[""])
        else -> null
    }
}
