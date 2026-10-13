/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.utils

import android.content.ContextWrapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.input.keyboard.AuxBarConfig
import org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition
import org.fcitx.fcitx5.android.input.keyboard.LayoutVariant
import org.fcitx.fcitx5.android.input.keyboard.LayoutVariantResolver
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubLayoutStorageLabelTest {
    private val label = "万象九键"
    private val id = "wanxiang_t9"

    private fun resolve(keys: List<String>, display: String? = label, schemaId: String? = id) =
        LayoutJsonUtils.resolveSubLayoutStorageLabel(keys, "rime", display, schemaId)

    @Test
    fun newSubLayoutUsesIdDespiteItsDisplayedName() {
        assertEquals(id, resolve(listOf("rime")))
        assertEquals(id, resolve(listOf("rime"), display = "新显示名称"))
    }

    @Test
    fun existingIdIsKeptWhenTheDisplayNameChanges() {
        assertEquals(id, resolve(listOf("rime", "rime:$id"), display = "新显示名称"))
    }

    @Test
    fun existingNameIsKeptEvenWhenTheIdIsKnown() {
        assertEquals(label, resolve(listOf("rime", "rime:$label")))
    }

    @Test
    fun splitOnlySubLayoutAlsoPreservesItsExistingName() {
        assertEquals(label, resolve(listOf("rime", "rime:$label:__variant__:split")))
        assertEquals(id, resolve(listOf("rime", "rime:$id:__variant__:split")))
    }

    @Test
    fun bothNamesUseTheRuntimeIdPriority() {
        assertEquals(id, resolve(listOf("rime", "rime:$label", "rime:$id")))
    }

    @Test
    fun anotherLayoutCannotChangeTheStorageLabel() {
        assertEquals(id, resolve(listOf("rime", "other:$label")))
    }

    @Test
    fun defaultAndUnavailableIdAreHandledWithoutGuessing() {
        assertNull(resolve(listOf("rime"), display = null))
        assertNull(resolve(listOf("rime"), display = " "))
        assertEquals(label, resolve(listOf("rime"), schemaId = null))
        assertEquals(label, resolve(listOf("rime:$label"), schemaId = " "))
        assertEquals(id, resolve(listOf("rime"), display = " $label ", schemaId = " $id "))
    }

    private fun rows(character: String) = mutableListOf(mutableListOf(mutableMapOf<String, Any?>(
        "type" to "AlphabetKey", "main" to character
    )))

    @Test
    fun newOrdinaryAndSplitLayoutsShareTheIdObject() {
        val manager = LayoutDataManager(ContextWrapper(null))
        manager.entries["rime"] = rows("B")
        val storage = LayoutJsonUtils.resolveSubLayoutStorageLabel(manager.entries.keys, "rime", label, id)!!
        assertTrue(manager.addSubModeLayout("rime", storage))
        assertEquals("rime:$id:__variant__:split", manager.addVariantLayout("rime", storage, LayoutVariant.Split))
        val json = Json.parseToJsonElement(manager.exportCurrentJsonCompact()) as JsonObject
        val layout = json["rime"] as JsonObject
        assertEquals(setOf("default", "__variant__:split"), (layout[id] as JsonObject).keys)
        assertFalse(layout.containsKey(label))
    }

    @Test
    fun creatingSplitCopiesTheExistingIdLayoutAndKeepsItsAuxBar() {
        val manager = LayoutDataManager(ContextWrapper(null))
        manager.entries["rime"] = rows("B")
        manager.entries["rime:$id"] = rows("T")
        val config = AuxBarConfig(AuxBarPosition.Left, 15f)
        manager.setLayoutAuxBarConfig("rime:$id", config)

        val storage = LayoutJsonUtils.resolveSubLayoutStorageLabel(manager.entries.keys, "rime", label, id)
        val splitKey = manager.addVariantLayout("rime", storage, LayoutVariant.Split)!!
        assertEquals("rime:$id:__variant__:split", splitKey)
        assertEquals("T", manager.entries[splitKey]!!.single().single()["main"])
        manager.entries[splitKey]!!.single().single()["main"] = "S"
        assertEquals("T", manager.entries["rime:$id"]!!.single().single()["main"])
        assertEquals(config, manager.getLayoutAuxBarConfig(splitKey))

        val json = Json.parseToJsonElement(manager.exportCurrentJsonCompact()) as JsonObject
        val layout = json["rime"] as JsonObject
        val schema = layout[id] as JsonObject
        assertTrue(schema.containsKey("__variant__:split"))
        assertFalse(layout.containsKey(label))
        assertEquals(schema, LayoutVariantResolver.resolveAuxBarElement(layout, LayoutVariant.Split, listOf(id, label)))
    }

    @Test
    fun creatingSplitOnAnExistingNameDoesNotCreateAnIdSibling() {
        val manager = LayoutDataManager(ContextWrapper(null))
        manager.entries["rime"] = rows("B")
        manager.entries["rime:$label"] = rows("T")
        val storage = LayoutJsonUtils.resolveSubLayoutStorageLabel(manager.entries.keys, "rime", label, id)
        val splitKey = manager.addVariantLayout("rime", storage, LayoutVariant.Split)!!
        assertEquals("rime:$label:__variant__:split", splitKey)
        assertEquals("T", manager.entries[splitKey]!!.single().single()["main"])
        val json = Json.parseToJsonElement(manager.exportCurrentJsonCompact()) as JsonObject
        val layout = json["rime"] as JsonObject
        assertTrue((layout[label] as JsonObject).containsKey("__variant__:split"))
        assertFalse(layout.containsKey(id))
    }
}
