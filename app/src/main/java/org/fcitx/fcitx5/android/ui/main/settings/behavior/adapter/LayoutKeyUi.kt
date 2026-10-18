/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter

/** Visual hints only; never normalizes or writes the user's key data. */
internal object LayoutKeyUi {
    private val identityFields = mapOf(
        "AlphabetKey" to setOf("type", "main", "alt"),
        "PlaceholderKey" to setOf("type", "main", "alt"),
        "LayoutSwitchKey" to setOf("type", "label", "subLabel"),
        "SymbolKey" to setOf("type", "label"),
        "NumPadKey" to setOf("type", "label"),
        "MacroKey" to setOf("type", "label", "tap")
    )
    private val wideKeys = setOf("CapsKey", "LayoutSwitchKey", "ReturnKey", "BackspaceKey", "MiniSpaceKey")

    fun hasCustomizedProperties(key: Map<String, Any?>): Boolean {
        val type = key["type"] as? String
        val required = identityFields[type] ?: setOf("type")
        val defaultWeight = when (type) { "SpaceKey" -> 0f; in wideKeys -> 0.15f; else -> 0.1f }
        return key.any { (name, value) ->
            when {
                name in required || value == null -> false
                name == "weight" || name == "percentWidth" -> {
                    val weight = (value as? Number)?.toFloat() ?: value.toString().toFloatOrNull()
                    weight != null && kotlin.math.abs(weight - defaultWeight) > 0.00001f
                }
                name == "rowHeightPercent" -> value.toString().toFloatOrNull()?.let { it != 100f } ?: false
                // An explicit displayText equal to main still suppresses Shift/Caps rewriting.
                value is String -> value.isNotEmpty()
                value is Boolean -> value
                value is Map<*, *> -> value.isNotEmpty()
                value is Collection<*> -> value.isNotEmpty()
                else -> true
            }
        }
    }
}
