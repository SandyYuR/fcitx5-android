/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.manager

import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.input.keyboard.LayoutVariant
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class SubModeSchemaLabelsTest {
    private val names = listOf("万象拼音", "万象九键")
    private val ids = listOf("wanxiang", "wanxiang_t9")

    private fun action(id: Int, name: String, label: String = name, longText: String = label,
                       menu: Array<Action>? = null, separator: Boolean = false) =
        Action(id, separator, false, false, name, "", label, longText, menu)

    private fun schemaMenu(vararg schemas: Action): Array<Action> = arrayOf(
        action(1, "rime", names.first(), menu = arrayOf(
            action(10, "ascii_mode", "西文"), *schemas,
            action(9, "separator", separator = true), action(11, "deploy", "重新部署")
        ))
    )

    private fun nameToIdMap(): Map<String, String> = SubModeManager.resolveSchemaNameToIdMap(
        schemaMenu(*ids.mapIndexed { index, id -> action(100 + index, id, names[index]) }.toTypedArray()),
        names.first()
    )

    private fun storedLabels(keys: Collection<String>): List<String> {
        val connection = Proxy.newProxyInstance(
            FcitxConnection::class.java.classLoader, arrayOf(FcitxConnection::class.java)
        ) { _, _, _ -> error("Reading stored layout labels must not call the engine") } as FcitxConnection
        val entries = keys.associateWith { emptyList<List<Map<String, Any?>>>() }
        return SubModeManager(connection, emptyArray(), entries).extractSubModeLabelsFromLayout("rime")
    }

    @Test fun directRimeIdsProduceOneEditableOptionPerStoredSchema() {
        // DirectRimeBackend repeats the display name in shortText/longText; name is the ID.
        val nameToId = nameToIdMap()
        assertEquals(names.zip(ids).toMap(), nameToId)
        val rows = mapOf("rime" to "base", "rime:${ids[0]}" to "full", "rime:${ids[1]}" to "nine")
        val layoutLabels = storedLabels(rows.keys)
        val labels = SubModeManager.mergeSubModeLabels(names, layoutLabels, nameToId)
        assertEquals(ids, labels)
        assertEquals(listOf(null) + ids, SubModeManager.buildSpinnerSelectionMap(labels))
        for ((index, label) in labels.withIndex()) {
            val stored = LayoutJsonUtils.resolveSubLayoutStorageLabel(rows.keys, "rime", label, nameToId[label])
            assertEquals(listOf("full", "nine")[index], rows["rime:$stored"])
        }
    }

    @Test fun currentSchemaAndRestoredSelectionResolveToTheSameId() {
        val nameToId = nameToIdMap()
        val labels = SubModeManager.mergeSubModeLabels(names, ids, nameToId)
        assertEquals(ids[1], SubModeManager.resolveSelectorLabel(names[1], labels, nameToId))
        assertEquals(ids[1], SubModeManager.resolveSelectorLabel(ids[1], labels, nameToId))
        assertNull(SubModeManager.resolveSelectorLabel(null, labels, nameToId))
        assertNull(SubModeManager.resolveSelectorLabel("missing", labels, nameToId))
    }

    @Test fun layoutsStoredUnderNamesKeepTheirExistingOptionsAndKeys() {
        val nameToId = nameToIdMap()
        val labels = SubModeManager.mergeSubModeLabels(names, names, nameToId)
        assertEquals(names, labels)
        assertEquals(names[0], SubModeManager.resolveSelectorLabel(names[0], labels, nameToId))
        val keys = names.map { "rime:$it" }
        assertEquals(names[0], LayoutJsonUtils.resolveSubLayoutStorageLabel(keys, "rime", names[0], ids[0]))
    }

    @Test fun anUnconfiguredSchemaRemainsAvailableForCreatingItsLayout() {
        val nameToId = nameToIdMap()
        val labels = SubModeManager.mergeSubModeLabels(names, listOf(ids[0]), nameToId)
        assertEquals(listOf(ids[0], names[1]), labels)
        assertEquals(names[1], SubModeManager.resolveSelectorLabel(names[1], labels, nameToId))
        assertEquals(ids[1], LayoutJsonUtils.resolveSubLayoutStorageLabel(
            listOf("rime", "rime:${ids[0]}"), "rime", names[1], nameToId[names[1]]
        ))
    }

    @Test fun aSplitOnlyIdLayoutAlsoSuppressesItsNameAlias() {
        val key = LayoutJsonUtils.entryKeyOf("rime", ids[0], LayoutVariant.Split)
        val layoutLabels = storedLabels(listOf("rime", key))
        val labels = SubModeManager.mergeSubModeLabels(listOf(names[0]), layoutLabels, nameToIdMap())
        assertEquals(listOf(ids[0]), labels)
        assertEquals(ids[0], SubModeManager.resolveSelectorLabel(names[0], labels, nameToIdMap()))
    }

    @Test fun missingMenuMetadataKeepsUnknownStoredLayoutsAccessible() {
        val labels = listOf(ids[0], "custom")
        assertEquals(labels, SubModeManager.mergeSubModeLabels(emptyList(), labels, emptyMap()))
        assertEquals("custom", SubModeManager.resolveSelectorLabel("custom", labels, emptyMap()))
    }

    @Test fun legacyMenuWithoutAnActionNameCanUseItsLongTextId() {
        val map = SubModeManager.resolveSchemaNameToIdMap(
            schemaMenu(action(100, "", names[0], longText = ids[0])), names[0]
        )
        assertEquals(mapOf(names[0] to ids[0]), map)
    }

    @Test fun idAndNameAliasesShareTheRuntimeIdPriority() {
        val labels = SubModeManager.mergeSubModeLabels(names, names + ids, nameToIdMap())
        assertEquals(ids, labels)
        assertEquals(ids[0], SubModeManager.resolveSelectorLabel(names[0], labels, nameToIdMap()))
    }
}
