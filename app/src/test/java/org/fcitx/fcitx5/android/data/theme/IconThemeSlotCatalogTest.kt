/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

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
        for (slot in listOf("keys.comma", "keys.period", "text_editor.select_all",
            "text_editor.cut", "text_editor.copy", "text_editor.paste")) {
            assertTrue(slot + " missing from ALL_SLOTS", slot in slots)
        }
    }
}
