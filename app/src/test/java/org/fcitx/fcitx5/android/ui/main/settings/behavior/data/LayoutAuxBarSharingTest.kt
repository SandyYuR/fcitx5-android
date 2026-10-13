/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import android.content.ContextWrapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.fcitx.fcitx5.android.input.keyboard.AuxBarConfig
import org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编辑器在普通/分体两种入口下必须读写同一份辅助栏，且保存后不能丢失。 */
class LayoutAuxBarSharingTest {
    // 只调用内存数据和 JSON 导出，不使用 Android Context 的行为。
    private fun manager() = LayoutDataManager(ContextWrapper(null))

    private fun rows() = mutableListOf(mutableListOf(mutableMapOf<String, Any?>(
        "type" to "AlphabetKey", "main" to "Q"
    )))

    @Test
    fun editingSplitAuxBarUpdatesTheSharedSettingsAndSavesThem() {
        val manager = manager()
        val owner = "rime:万象九键"
        val split = "$owner:__variant__:split"
        manager.entries["rime"] = rows()
        manager.entries[owner] = rows()
        manager.entries[split] = rows()
        manager.setLayoutAuxBarConfig(owner, AuxBarConfig(AuxBarPosition.Left, 15f))
        manager.setLayoutAuxBarKeys(owner, listOf(mapOf("type" to "MacroKey", "label" to "，")))

        assertEquals(manager.getLayoutAuxBarConfig(owner), manager.getLayoutAuxBarConfig(split))
        assertEquals(manager.getLayoutAuxBarKeys(owner), manager.getLayoutAuxBarKeys(split))
        assertSame(manager.getLayoutAuxBarKeysRef(owner), manager.getLayoutAuxBarKeysRef(split))

        manager.setLayoutAuxBarConfig(split, AuxBarConfig(AuxBarPosition.Right, 20f))
        manager.getLayoutAuxBarKeysRef(split).add(mutableMapOf("type" to "MacroKey", "label" to "。"))
        assertEquals(AuxBarConfig(AuxBarPosition.Right, 20f), manager.getLayoutAuxBarConfig(owner))
        assertEquals(2, manager.getLayoutAuxBarKeys(owner).size)
        assertFalse(manager.layoutAuxBarConfigs.containsKey(split))
        assertFalse(manager.layoutAuxBarKeys.containsKey(split))

        val json = Json.parseToJsonElement(manager.exportCurrentJsonCompact()) as JsonObject
        val schema = (json["rime"] as JsonObject)["万象九键"] as JsonObject
        val aux = (schema["__meta__"] as JsonObject)["aux_bar"] as JsonObject
        assertEquals("right", (aux["position"] as JsonPrimitive).content)
        assertEquals(2, (aux["keys"] as JsonArray).size)
        assertTrue(schema["__variant__:split"] is JsonArray)
    }

    @Test
    fun clearingSplitAuxBarDoesNotChangeOtherSubLayouts() {
        val manager = manager()
        val base = AuxBarConfig(AuxBarPosition.Top, 10f)
        val other = AuxBarConfig(AuxBarPosition.Right, 20f)
        manager.setLayoutAuxBarConfig("rime:__variant__:split", base)
        manager.setLayoutAuxBarConfig("rime:万象拼音", other)
        manager.setLayoutAuxBarConfig("rime:万象九键", AuxBarConfig(AuxBarPosition.Left, 15f))
        manager.setLayoutAuxBarKeys("rime:万象九键:__variant__:split", listOf(mapOf("label" to "，")))
        manager.setLayoutAuxBarConfig("rime:万象九键:__variant__:split", null)
        manager.setLayoutAuxBarKeys("rime:万象九键:__variant__:split", emptyList())

        assertNull(manager.getLayoutAuxBarConfig("rime:万象九键"))
        assertTrue(manager.getLayoutAuxBarKeys("rime:万象九键").isEmpty())
        assertEquals(base, manager.getLayoutAuxBarConfig("rime"))
        assertEquals(other, manager.getLayoutAuxBarConfig("rime:万象拼音:__variant__:split"))
    }
}
