/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IconThemeSlotCatalogTest {
    private val iconSlotRegex = Regex("\"iconSlot\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * 布局里点名了 `iconSlot`、内置主题里也有这枚图标，但槽位不在 [IconTheme.ALL_SLOTS] 里时，
     * 图标主题编辑器看不到它，导出/导入也会把它当成未知键。这里把内置布局与槽位表锁在一起。
     */
    @Test fun bundledLayoutsOnlyReferenceRegisteredIconSlots() {
        // 直接读仓库里的 assets：Robolectric 的旧 AssetManager 打不开中文资源名。
        val dir = File("src/main/assets/bundled/keyboard_layouts")
            .takeIf { it.isDirectory } ?: File("app/src/main/assets/bundled/keyboard_layouts")
        val layouts = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty().sortedBy { it.name }
        assertTrue("No bundled layouts found in " + dir.absolutePath, layouts.isNotEmpty())
        val known = IconTheme.ALL_SLOTS.toSet()
        val unknown = sortedMapOf<String, MutableSet<String>>()
        for (file in layouts) {
            val text = file.readText()
            iconSlotRegex.findAll(text).map { it.groupValues[1] }.forEach { slot ->
                if (slot !in known) unknown.getOrPut(file.name) { mutableSetOf() }.add(slot)
            }
        }
        assertTrue("Unregistered icon slots: " + unknown, unknown.isEmpty())
    }

    @Test fun textEditingSlotsAreEditable() {
        val slots = IconTheme.ALL_SLOTS.toSet()
        for (slot in listOf(
            "keys.comma", "keys.period", "text_editor.select_all", "text_editor.select",
            "text_editor.select_locked", "text_editor.cut", "text_editor.copy", "text_editor.paste",
            "text_editor.home", "text_editor.end", "text_editor.space", "text_editor.return",
            "text_editor.backspace", "text_editor.layout"
        )) {
            assertTrue(slot + " missing from ALL_SLOTS", slot in slots)
        }
    }

    @Test fun toolbarAndEditorAssetsArePresentInBothFoxyThemes() {
        val watercolor = bundledIconSlots("水彩")
        val prussian = bundledIconSlots("普蓝")
        val shared = listOf(
            "keys.capslock.none", "keys.capslock.once", "keys.capslock.lock",
            "keys.language", "keys.language_ascii",
            "toolbar.language_switch", "toolbar.language_switch_ascii",
            "toolbar.one_handed_keyboard", "toolbar.one_handed_keyboard_active",
            "toolbar.floating_toggle", "toolbar.floating_toggle_active",
            "toolbar.split_keyboard", "toolbar.split_keyboard_active",
            "toolbar.voice_input", "toolbar.voice_input_active",
            "toolbar.js_app.youdao_translate", "toolbar.js_app.bilibili_translate",
            "text_editor.home", "text_editor.end", "text_editor.space", "text_editor.return",
            "text_editor.backspace", "text_editor.layout"
        )
        for (slot in shared) {
            assertTrue("水彩 missing $slot", slot in watercolor)
            assertTrue("普蓝 missing $slot", slot in prussian)
        }
        for (slot in listOf("text_editor.select", "text_editor.select_locked")) {
            assertTrue("水彩 missing $slot", slot in watercolor)
        }
        for (slot in listOf(
            "keys.symbols", "keys.semicolon", "keys.semicolon_alt", "keys.slash",
            "keys.left_bracket", "keys.right_bracket"
        )) {
            assertTrue("水彩 missing $slot", slot in watercolor)
        }
        val formalNumpadSlots = listOf(
            "keys.numpad.add", "keys.numpad.subtract", "keys.numpad.multiply", "keys.numpad.divide",
            "keys.numpad.equal", "keys.numpad.decimal", "keys.numpad.at", "keys.numpad.percent",
            "keys.numpad.space", "keys.numpad.layout"
        )
        for (slot in formalNumpadSlots) {
            assertTrue("$slot missing from the slot catalog", slot in IconTheme.NUMPAD_KEY_SLOTS)
        }
        assertTrue("watercolor numpad icons were lost", formalNumpadSlots.all { it in watercolor })
        assertTrue("普蓝 must not invent watercolor letters", "keys.letter.a" !in prussian)
        assertTrue("普蓝 must not invent watercolor digits", "keys.numpad.0" !in prussian)
        assertTrue("watercolor letters were lost", "keys.letter.a" in watercolor)
        assertTrue("watercolor digits were lost", "keys.numpad.0" in watercolor)
    }

    @Test fun watercolorNavigationSlotsReuseMatchingColoredEditorIcons() {
        val directory = File("src/main/assets/bundled/icon_themes")
            .takeIf { it.isDirectory } ?: File("app/src/main/assets/bundled/icon_themes")
        val icons = Json.parseToJsonElement(File(directory, "水彩.json").readText()).jsonObject
            .getValue("icons").jsonObject
        val pairs = mapOf(
            "keys.home" to "text_editor.home", "keys.end" to "text_editor.end",
            "keys.pageup" to "keys.cursor_up", "keys.pagedown" to "keys.cursor_down"
        )
        pairs.forEach { (slot, source) ->
            assertTrue("missing navigation icon $slot", slot in icons)
            org.junit.Assert.assertEquals("$slot should retain the existing shape and palette", icons[source], icons[slot])
        }
    }

    private fun bundledIconSlots(themeName: String): Set<String> {
        val directory = File("src/main/assets/bundled/icon_themes")
            .takeIf { it.isDirectory } ?: File("app/src/main/assets/bundled/icon_themes")
        val root = Json.parseToJsonElement(File(directory, "$themeName.json").readText()).jsonObject
        return root.getValue("icons").jsonObject.keys
    }
}
