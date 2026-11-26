/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.editing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.ConfigProvider
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class TextEditingLayoutLoaderTest {
    private fun jsonObject(source: String): JsonObject =
        Json.parseToJsonElement(source).jsonObject

    private fun macroKey(
        column: Number,
        columnSpan: Int = 1,
        rowSpan: Int = 1,
        type: String = "MacroKey"
    ): Map<String, Any?> = mapOf(
        "type" to type,
        "label" to "key",
        "tap" to mapOf("macro" to listOf(mapOf("type" to "edit", "action" to "copy"))),
        "column" to column,
        "columnSpan" to columnSpan,
        "rowSpan" to rowSpan
    )

    private fun macroKeyWithoutColumn(): Map<String, Any?> = macroKey(0).toMutableMap().apply {
        remove("column")
    }

    private fun withLayoutProvider(layout: JsonObject, block: () -> Unit) {
        val previous = ConfigProviders.provider
        ConfigProviders.provider = object : ConfigProvider {
            override fun textKeyboardLayoutFile(): File? = null
            override fun textKeyboardLayoutJson(): JsonObject = layout
            override fun popupPresetFile(): File? = null
            override fun fontsetFile(): File? = null
            override fun buttonsLayoutConfigFile(): File? = null
            override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> =
                Result.failure(UnsupportedOperationException("unused by layout loader tests"))
        }
        try {
            block()
        } finally {
            ConfigProviders.provider = previous
        }
    }

    @Test
    fun malformedGridGeometryAndUnsupportedTypesRejectTheWholeLayout() {
        val cases = listOf(
            "overlap" to listOf(
                listOf(macroKey(column = 0, columnSpan = 2, rowSpan = 2)),
                listOf(macroKey(column = 1))
            ),
            "negative column" to listOf(listOf(macroKey(column = -1))),
            "row overflow" to listOf(listOf(macroKey(column = 0, rowSpan = 2))),
            "missing column" to listOf(listOf(macroKey(column = 0), macroKeyWithoutColumn())),
            "NaN column" to listOf(listOf(macroKey(column = Double.NaN))),
            "unsupported key type" to listOf(listOf(macroKey(column = 0, type = "UnsupportedKey")))
        )
        cases.forEach { (name, rows) ->
            assertNull(name, TextEditingLayoutLoader.fromRows(rows))
        }
    }

    @Test
    fun invalidExplicitPreferenceFallsBackToAutomaticallyDetectedEditorLayout() {
        val root = jsonObject("""
            {"rime":{"editor":[[{
              "type":"MacroKey","label":"automatic",
              "tap":{"macro":[{"type":"edit","action":"copy"}]}
            }]]}}
        """)

        val layout = TextEditingLayoutLoader.fromJson(root, "not-a-layout", listOf("rime"))
        assertNotNull(layout)
        assertEquals(1, requireNotNull(layout).rows.flatten().size)
    }

    @Test
    fun invalidAutomaticGridCandidateDoesNotSuppressValidSiblingEditorLayer() {
        val root = jsonObject("""
            {"rime":{
              "editor":[[
                {"type":"MacroKey","label":"invalid","tap":{"macro":[{"type":"edit","action":"copy"}]},"column":0,"columnSpan":2},
                {"type":"MacroKey","label":"overlap","tap":{"macro":[{"type":"edit","action":"copy"}]},"column":1}
              ]],
              "__layer__:文本编辑":[[{
                "type":"MacroKey","label":"valid","tap":{"macro":[{"type":"edit","action":"copy"}]}
              }]]
            }}
        """)

        val key = requireNotNull(TextEditingLayoutLoader.fromJson(root, "", listOf("rime")))
            .rows.single().single()
        assertEquals("valid", (key.appearance as KeyDef.Appearance.AltText).displayText)
    }

    @Test
    fun invalidPreferredGridCandidateFallsThroughToNextPreferredBase() {
        val root = jsonObject("""
            {
              "rime":{"editor":[[{
                "type":"MacroKey","label":"invalid","tap":{"macro":[{"type":"edit","action":"copy"}]},"column":0,"rowSpan":2
              }]]},
              "default":{"editor":[[{
                "type":"MacroKey","label":"valid","tap":{"macro":[{"type":"edit","action":"paste"}]}
              }]]}
            }
        """)

        val key = requireNotNull(TextEditingLayoutLoader.fromJson(root, "rime:editor", listOf("rime", "default")))
            .rows.single().single()
        assertEquals("valid", (key.appearance as KeyDef.Appearance.AltText).displayText)
        assertEquals("paste", ((key.tapAction as org.fcitx.fcitx5.android.input.keyboard.MacroAction)
            .steps.single() as org.fcitx.fcitx5.android.input.keyboard.MacroStep.Edit).action)
    }

    @Test
    fun builtinPreferenceSelectsNoConfiguredLayout() {
        val root = jsonObject("""
            {"rime":{"editor":[[{
              "type":"MacroKey","label":"automatic",
              "tap":{"macro":[{"type":"edit","action":"copy"}]}
            }]]}}
        """)

        assertNull(TextEditingLayoutLoader.fromJson(root, TextEditingLayoutResolver.BUILTIN, listOf("rime")))
    }

    @Test
    fun legacyTextEditorEntryStaysAvailableOnlyAsTheFoxySwipeBar() {
        val root = jsonObject("""
            {"text_editor":[[{
              "type":"MacroKey","label":"legacy swipe bar",
              "tap":{"macro":[{"type":"edit","action":"copy"}]}
            }]]}
        """)
        val preference = AppPrefs.getInstance().textEditing.layoutOverride
        val previousPreference = preference.getValue()
        try {
            preference.setValue("")
            withLayoutProvider(root) {
                assertNull(TextEditingLayoutLoader.load(preferredBases = listOf("rime")))
                val swipeBar = TextEditingLayoutLoader.loadSwipeBar()
                assertNotNull(swipeBar)
                assertEquals(1, requireNotNull(swipeBar).rows.flatten().size)
                assertNotNull(swipeBar.rows.single().single().tapAction)
            }
        } finally {
            preference.setValue(previousPreference)
        }
    }
}
