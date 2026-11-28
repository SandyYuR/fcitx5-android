/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Contracts for author-provided key order and swipe meanings that were previously left stale. */
class BundledThemeLayoutTest {
    private fun layout(name: String): JsonObject {
        val relative = "src/main/assets/bundled/keyboard_layouts/TextKeyboardLayout.$name.json"
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }
    private fun rows() = layout("水彩")["rime"]!!.jsonObject["default"]!!.jsonArray
    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content

    @Test fun watercolorBottomRowKeepsIndependentSymbolsBeforeSpaceAndLanguageAfterIt() {
        val bottom = rows()[3].jsonArray.map { it.jsonObject }
        assertEquals(7, bottom.size)
        assertEquals(listOf("keys.numpad", "keys.comma", "keys.symbols", "keys.space",
            "LanguageKey", "keys.period", "ReturnKey"),
            bottom.map { it.text("iconSlot") ?: it.text("type") })
        assertEquals("LayoutSwitchKey", bottom[2].text("type"))
        assertEquals("Symbol", bottom[2].text("subLabel"))
        assertEquals("SpaceKey", bottom[3].text("type"))
        assertEquals(1.0, bottom.sumOf { it.text("weight")!!.toDouble() }, 0.0001)
    }

    @Test fun watercolorUpHintsKeepTheCurrentClearUndoAndRedoMeanings() {
        val letters = rows().take(3).flatMap { it.jsonArray }.map { it.jsonObject }
            .filter { it.text("type") == "MacroKey" }
        letters.forEach { key ->
            assertNotNull(key.text("swipeUpLabel"))
            assertTrue(key["swipeUp"] is JsonObject)
            assertFalse(key.containsKey("altLabel"))
        }
        val third = rows()[2].jsonArray.map { it.jsonObject }.associateBy { it.text("label") }
        fun steps(letter: String) = third[letter]!!["swipeUp"]!!.jsonObject["macro"]!!.jsonArray
        assertEquals("清", third["b"]!!.text("swipeUpLabel"))
        assertEquals("selectAll", steps("b")[0].jsonObject.text("action"))
        assertEquals("BackSpace", steps("b")[1].jsonObject["keys"]!!.jsonArray[0].jsonObject.text("fcitx"))
        assertEquals("undo", steps("n")[0].jsonObject.text("action"))
        assertEquals("redo", steps("m")[0].jsonObject.text("action"))
    }

    @Test fun numericOuterColumnsMatchBothMainOperationRows() {
        for (name in listOf("水彩", "普蓝")) {
            val base = layout(name)["rime"]!!.jsonObject
            val main = base["default"]!!.jsonArray
            val numeric = base["__layer__:数字"]!!.jsonArray
            fun weight(key: kotlinx.serialization.json.JsonElement) = key.jsonObject.text("weight")!!.toDouble()
            val left = weight(main[2].jsonArray.first())
            val right = weight(main[2].jsonArray.last())
            assertEquals(0.15, left, 0.000001)
            assertEquals(0.15, right, 0.000001)
            assertEquals(left, weight(main[3].jsonArray.first()), 0.000001)
            assertEquals(right, weight(main[3].jsonArray.last()), 0.000001)
            numeric.forEachIndexed { index, entry ->
                val row = entry.jsonArray
                assertEquals("$name row $index left", left, weight(row.first()), 0.000001)
                assertEquals("$name row $index right", right, weight(row.last()), 0.000001)
                assertEquals(1.0, row.sumOf(::weight), 0.000001)
                val middle = row.drop(1).dropLast(1).map(::weight)
                if (index < 3) {
                    assertEquals(listOf(1.0, 1.0, 1.0), middle.map { it / middle.first() })
                } else {
                    assertEquals(2.0, middle[2] / middle.first(), 0.000001)
                    listOf(0, 1, 3, 4).forEach { assertEquals(middle.first(), middle[it], 0.000001) }
                }
            }
        }
    }


    @Test fun chaohuaEnglishComparisonsCommitTheirDisplayedSymbol() {
        val rows = layout("QM-朝花")["[公共] 英符键盘"]!!.jsonArray
        val keys = rows.flatMap { it.jsonArray }.map { it.jsonObject }
        for (symbol in listOf("<", ">")) {
            val key = keys.single { it.text("label") == symbol }
            val macro = key["tap"]!!.jsonObject["macro"]!!.jsonArray
            assertEquals(1, macro.size)
            assertEquals("text", macro.single().jsonObject.text("type"))
            assertEquals(symbol, macro.single().jsonObject.text("text"))
        }
    }

    @Test fun chaohuaLayerTargetsReferToExistingSharedEntries() {
        val root = layout("QM-朝花")
        var checked = 0
        fun visit(element: kotlinx.serialization.json.JsonElement) {
            when (element) {
                is JsonObject -> {
                    if (element.text("type") == "layer" && element.text("mode") != "back") {
                        val target = element.text("target")
                        assertTrue("Missing layer target: $target", target != null && root.containsKey(target))
                        checked++
                    }
                    element.values.forEach(::visit)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        assertTrue("The fixture must include layer navigation", checked > 0)
    }

    @Test fun fullEditingPanelsUseTheDedicatedReturnSlot() {
        for (name in listOf("水彩", "普蓝")) {
            val editor = layout(name)["editor"]!!.jsonArray
            val first = editor.last().jsonArray.first().jsonObject
            assertEquals("LayoutSwitchKey", first.text("type"))
            assertEquals("Text", first.text("subLabel"))
            assertEquals("text_editor.layout", first.text("iconSlot"))
        }
    }
}
