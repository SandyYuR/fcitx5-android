/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRimeStatusMenuTest {
    private val labels = DirectRimeStatusMenu.Labels("英文模式", "中文模式")
    private val schemas = listOf("wanxiang" to "万象", "luna" to "朙月")
    private val options = listOf(
        listOf("emoji", "表情关 → 表情开", "0", ""),
        listOf("tone_hint", "有声调", "0", "comment"),
        listOf("comment_off", "注释关", "1", "comment"),
        listOf("toneless_hint", "无声调", "0", "comment"),
        listOf("raw_input", "原编码", "0", "raw"),
        listOf("raw_off", "编码关", "0", "raw"),
    )

    @Test fun buildsStableSchemaAndOptionMenuStructure() {
        val actions = DirectRimeStatusMenu.build(
            DirectRimeStatusMenu.State("wanxiang", "万象", ascii = false),
            schemas,
            options,
            labels,
        )

        assertEquals(listOf("rime", "emoji", "select-tone_hint", "select-raw_input"), actions.map { it.name })
        val root = actions[0]
        assertEquals(DirectRimeStatusMenu.ROOT_ID, root.id)
        assertEquals("万象", root.shortText)
        val menu = requireNotNull(root.menu)
        assertEquals(listOf(10, 100, 101, 9, 11, 12), menu.map { it.id })
        assertEquals(listOf("ascii_mode", "wanxiang", "luna", "separator", "deploy", "sync"), menu.map { it.name })
        assertEquals("英文模式", menu[0].shortText)
        assertFalse(menu[0].isChecked)
        assertTrue(menu[1].isChecked)
        assertFalse(menu[2].isChecked)
        assertTrue(menu[3].isSeparator)
        assertEquals("重新部署", menu[4].shortText)
        assertEquals("同步用户词典", menu[5].shortText)

        val toggle = actions[1]
        assertEquals(1000, toggle.id)
        assertEquals("表情关 → 表情开", toggle.shortText)
        assertFalse(toggle.isCheckable)

        val comment = actions[2]
        assertEquals(-1001, comment.id)
        assertEquals("注释关", comment.shortText)
        val commentMenu = requireNotNull(comment.menu)
        assertEquals(listOf(1001, 1002, 1003), commentMenu.map { it.id })
        assertEquals(listOf(false, true, false), commentMenu.map { it.isChecked })

        val raw = actions[3]
        assertEquals(-1004, raw.id)
        assertEquals("原编码", raw.shortText)
        val rawMenu = requireNotNull(raw.menu)
        assertEquals(listOf(1004, 1005), rawMenu.map { it.id })
        assertEquals(listOf(false, false), rawMenu.map { it.isChecked })
    }

    @Test fun asciiMenuShowsTheTargetModeAndFallsBackForUnnamedChineseSchema() {
        val ascii = DirectRimeStatusMenu.build(
            DirectRimeStatusMenu.State("wanxiang", "万象", ascii = true),
            schemas,
            emptyList(),
            labels,
        )[0]
        assertEquals("英文模式", ascii.shortText)
        val asciiMenu = requireNotNull(ascii.menu)
        assertEquals("中文模式", asciiMenu[0].shortText)
        assertTrue(asciiMenu[0].isChecked)

        val unnamed = DirectRimeStatusMenu.build(
            DirectRimeStatusMenu.State("unknown", "", ascii = false),
            emptyList(),
            emptyList(),
            labels,
        )[0]
        assertEquals("Rime", unnamed.shortText)
    }

    @Test fun resolvesOwnerCommandsWithoutOwningNativeState() {
        assertEquals(
            DirectRimeStatusMenu.OwnerCommand.ToggleMode,
            DirectRimeStatusMenu.resolveOwnerCommand(10, schemas, options),
        )
        assertEquals(
            DirectRimeStatusMenu.OwnerCommand.SelectSchema("luna"),
            DirectRimeStatusMenu.resolveOwnerCommand(101, schemas, options),
        )
        assertEquals(
            DirectRimeStatusMenu.OwnerCommand.SetOptions(
                listOf(DirectRimeStatusMenu.OptionChange("emoji", true))
            ),
            DirectRimeStatusMenu.resolveOwnerCommand(1000, schemas, options),
        )
        assertEquals(
            DirectRimeStatusMenu.OwnerCommand.SetOptions(
                listOf(
                    DirectRimeStatusMenu.OptionChange("comment_off", false),
                    DirectRimeStatusMenu.OptionChange("toneless_hint", false),
                    DirectRimeStatusMenu.OptionChange("tone_hint", true),
                )
            ),
            DirectRimeStatusMenu.resolveOwnerCommand(1001, schemas, options),
        )
        assertEquals(
            DirectRimeStatusMenu.OwnerCommand.SetOptions(
                listOf(DirectRimeStatusMenu.OptionChange("comment_off", false))
            ),
            DirectRimeStatusMenu.resolveOwnerCommand(
                1000,
                schemas,
                listOf(listOf("comment_off", "注释关", "1", "")),
            ),
        )
    }

    @Test fun structuralAndOutOfRangeIdsDoNotResolveToOwnerCommands() {
        for (id in listOf(1, 9, 11, 12, -1001, 99, 102, 999, 1006)) {
            assertNull("id=$id", DirectRimeStatusMenu.resolveOwnerCommand(id, schemas, options))
        }
        assertEquals(0, DirectRimeStatusMenu.schemaIndex(100, schemas.size))
        assertEquals(1, DirectRimeStatusMenu.schemaIndex(101, schemas.size))
        assertNull(DirectRimeStatusMenu.schemaIndex(99, schemas.size))
        assertNull(DirectRimeStatusMenu.schemaIndex(102, schemas.size))
    }
}
