/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the runtime layout parser.
 *
 * These paths run while the keyboard is being built, so a malformed key must degrade to
 * "skip this key" rather than throwing: an exception here used to propagate out of
 * BaseKeyboard's initializer and crash on every attempt to show the keyboard.
 */
class LayoutJsonUtilsTest {

    private fun row(json: String): JsonArray =
        Json.parseToJsonElement(json) as JsonArray

    /** A1: a row containing non-object elements must not throw. */
    @Test
    fun malformedRowElementsAreSkipped() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [
                  "a",
                  42,
                  null,
                  ["nested"],
                  {"type": "AlphabetKey", "main": "q", "alt": "1"}
                ]
                """.trimIndent()
            )
        )
        assertEquals("only the well-formed key survives", 1, parsed.size)
        assertEquals("AlphabetKey", parsed[0].type)
        assertEquals("q", parsed[0].main)
    }

    /** A1: an entirely malformed row yields an empty list rather than an exception. */
    @Test
    fun rowOfOnlyGarbageYieldsEmptyList() {
        assertTrue(LayoutJsonUtils.parseKeyJsonArray(row("""["a", 1, null]""")).isEmpty())
    }

    /** A1: a key object without a type is dropped (parseKeyJson returns null). */
    @Test
    fun keyWithoutTypeIsSkipped() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(row("""[{"main": "q"}]"""))
        assertTrue(parsed.isEmpty())
    }

    /** LanguageKey filtering must still work while skipping malformed entries. */
    @Test
    fun languageKeyIsFilteredWhenDisabled() {
        val json = """[{"type": "LanguageKey"}, {"type": "AlphabetKey", "main": "q", "alt": "1"}]"""
        assertEquals(2, LayoutJsonUtils.parseKeyJsonArray(row(json), showLangSwitch = true).size)
        assertEquals(1, LayoutJsonUtils.parseKeyJsonArray(row(json), showLangSwitch = false).size)
    }

    /** A2: a MacroKey without a tap action is skipped instead of throwing. */
    @Test
    fun macroKeyWithoutTapIsSkipped() {
        val keys = LayoutJsonUtils.parseKeyJsonArray(
            row("""[{"type": "MacroKey", "label": "M"}]""")
        )
        assertEquals(1, keys.size)
        assertNull("createKeyDef reports the key as unusable", LayoutJsonUtils.createKeyDef(keys[0]))
    }

    /** A2: a MacroKey with a tap action still resolves. */
    @Test
    fun macroKeyWithTapIsCreated() {
        val keys = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "MacroKey",
                  "label": "M",
                  "tap": {"macro": [{"type": "text", "text": "hi"}]}
                }]
                """.trimIndent()
            )
        )
        assertEquals(1, keys.size)
        val def = LayoutJsonUtils.createKeyDef(keys[0])
        assertTrue("a MacroKey with tap produces a KeyDef", def != null)
    }

    /** A2: a malformed macro step is dropped, and does not take the whole key with it. */
    @Test
    fun malformedMacroStepIsDropped() {
        val keys = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [{
                  "type": "MacroKey",
                  "label": "M",
                  "tap": {"macro": [{"type": "nonsense"}, {"type": "text", "text": "hi"}]}
                }]
                """.trimIndent()
            )
        )
        assertEquals(1, keys.size)
        assertEquals("only the valid step remains", 1, keys[0].tap?.steps?.size)
    }

    /** C23: "main": null must not become the literal string "null". */
    @Test
    fun jsonNullDoesNotBecomeLiteralNullString() {
        val keys = LayoutJsonUtils.parseKeyJsonArray(
            row("""[{"type": "AlphabetKey", "main": null, "alt": null, "label": null}]""")
        )
        assertEquals(1, keys.size)
        assertNull(keys[0].main)
        assertNull(keys[0].alt)
        assertNull(keys[0].label)
    }

    /** C23: a genuine string value is still read. */
    @Test
    fun stringValuesArePreserved() {
        val keys = LayoutJsonUtils.parseKeyJsonArray(
            row("""[{"type": "AlphabetKey", "main": "q", "alt": "1", "label": "Q"}]""")
        )
        assertEquals("q", keys[0].main)
        assertEquals("1", keys[0].alt)
        assertEquals("Q", keys[0].label)
    }

    /** parseLayoutRows (editor path) skips the same malformed elements. */
    @Test
    fun parseLayoutRowsSkipsMalformedElements() {
        val rows = LayoutJsonUtils.parseLayoutRows(
            Json.parseToJsonElement(
                """[["a", {"type": "AlphabetKey", "main": "q", "alt": "1"}, null]]"""
            ) as JsonArray
        )
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].size)
        assertEquals("AlphabetKey", rows[0][0]["type"])
    }

    /** Sanity check that the fixture helper really produces objects. */
    @Test
    fun wellFormedRowParsesEveryKey() {
        val parsed = LayoutJsonUtils.parseKeyJsonArray(
            row(
                """
                [
                  {"type": "AlphabetKey", "main": "q", "alt": "1"},
                  {"type": "AlphabetKey", "main": "w", "alt": "2"}
                ]
                """.trimIndent()
            )
        )
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.type == "AlphabetKey" })
    }
}
