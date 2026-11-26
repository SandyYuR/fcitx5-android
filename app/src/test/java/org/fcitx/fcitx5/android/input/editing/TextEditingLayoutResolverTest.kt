/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextEditingLayoutResolverTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val profile = json(
        """
        {
          "rime": {
            "default": [[{"click":"a"}]],
            "__layer__:editor": [[{"click":"e"}]]
          },
          "text_editor": [[{"click":"t"}]],
          "custom": [[{"click":"x"}]]
        }
        """.trimIndent()
    )

    @Test
    fun automaticPrefersEditorLayerInPreferredBase() {
        assertEquals(
            "rime:__layer__:editor",
            TextEditingLayoutResolver.resolve(profile, "", listOf("rime"))
        )
    }

    @Test
    fun explicitArbitraryProfileLayoutWins() {
        assertEquals(
            "custom",
            TextEditingLayoutResolver.resolve(profile, "custom", listOf("rime"))
        )
    }

    @Test
    fun explicitEditorLayerWins() {
        assertEquals(
            "rime:__layer__:editor",
            TextEditingLayoutResolver.resolve(profile, "rime:__layer__:editor", listOf("rime"))
        )
    }

    @Test
    fun stalePreferenceFallsBackToAutomatic() {
        assertEquals(
            "rime:__layer__:editor",
            TextEditingLayoutResolver.resolve(profile, "old:__layer__:editor", listOf("rime"))
        )
    }

    @Test
    fun stalePreferenceFallsBackToBuiltinWhenNoEditorLayoutExists() {
        val regularOnly = json("""{"rime":{"default":[[{"click":"a"}]]}}""")
        assertNull(TextEditingLayoutResolver.resolve(regularOnly, "removed", listOf("rime")))
    }

    @Test
    fun builtinPreferenceDisablesAutomaticDetection() {
        assertNull(
            TextEditingLayoutResolver.resolve(
                profile,
                TextEditingLayoutResolver.BUILTIN,
                listOf("rime")
            )
        )
    }

    @Test
    fun emptyExplicitLayoutFallsBackToAutomatic() {
        val profileWithEmptyEditorLayer = json(
            """{"rime":{"default":[[{"click":"a"}]],"__layer__:editor":[]},"textedit":[[{"click":"t"}]]}"""
        )
        assertEquals(
            "textedit",
            TextEditingLayoutResolver.resolve(
                profileWithEmptyEditorLayer,
                "rime:__layer__:editor",
                listOf("rime")
            )
        )
    }

    @Test
    fun automaticDetectionIgnoresStandaloneLayoutsWithoutRows() {
        val emptyEditorLayout = json("""{"textedit":[],"other":[[{"click":"a"}]]}""")
        assertNull(TextEditingLayoutResolver.resolve(emptyEditorLayout, "", listOf("rime")))
    }

    @Test
    fun onlyTextEditorDoesNotAutoSelect() {
        val onlyTextEditor = json("""{"text_editor":[[{"click":"a"}]]}""")
        assertNull(TextEditingLayoutResolver.resolve(onlyTextEditor, "", listOf("rime")))
        assertEquals(
            "text_editor",
            TextEditingLayoutResolver.resolve(onlyTextEditor, "text_editor", listOf("rime"))
        )

        val reservedNames = json(
            """{"text_editor":{"default":[[{"click":"a"}]],"__layer__:editor":[[{"click":"e"}]]},"rime":{"__layer__:text_editor":[[{"click":"t"}]]}}"""
        )
        assertNull(TextEditingLayoutResolver.resolve(reservedNames, "", listOf("rime")))
        assertNull(
            TextEditingLayoutResolver.autoDetect(
                listOf("text_editor", "rime:__layer__:text_editor"),
                listOf("rime")
            )
        )
    }

    @Test
    fun explicitVariantSubmodeWorksButVariantOnlyBaseIsNotAutoDetected() {
        val variantOnly = json("""{"rime":{"__variant__:split":[[{"click":"a"}]]}}""")
        assertNull(TextEditingLayoutResolver.resolve(variantOnly, "", listOf("rime")))
        assertEquals(
            "rime:__variant__:split",
            TextEditingLayoutResolver.resolve(
                variantOnly,
                "rime:__variant__:split",
                listOf("rime")
            )
        )
    }

    @Test
    fun autoDetectFromParsedProfileEntries() {
        val entries = listOf("rime", "rime:__layer__:editor", "text_editor", "custom")
        assertEquals(
            "rime:__layer__:editor",
            TextEditingLayoutResolver.autoDetect(entries, listOf("rime"))
        )
    }
}
