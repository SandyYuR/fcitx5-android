/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.*
import org.junit.Test
import org.yaml.snakeyaml.nodes.*

class WanxiangAlgebraPresetsTest {
    private val y = WanxiangYaml()
    private val library = y.document("base: {全拼: [], 自然码: []}\npro: {全拼: [], 自然码: [], 直接辅助: [], 间接辅助: []}\n模糊音_nl: []\n14键: []\n")
    private val presets = WanxiangAlgebraPresets(y, library, "wanxiang_pro.schema.yaml")
    private fun refs(node: MappingNode) = (y.member(node, "__patch") as SequenceNode).value.map { (it as ScalarNode).value }

    @Test fun schemeChoicesComeFromTheInstalledAlgebraAndKeepUnknownExtensions() {
        val node = y.document("__include: local:/base\n__patch: [local:/before, wanxiang_algebra:/pro/全拼, local:/middle, wanxiang_algebra:/pro/直接辅助, local:/after]\n")
        val result = presets.select(node, "自然码")
        assertEquals(listOf("local:/before", "wanxiang_algebra:/pro/自然码", "local:/middle", "wanxiang_algebra:/pro/直接辅助", "local:/after"), refs(result))
        assertEquals("local:/base", (y.member(result, "__include") as ScalarNode).value)
        assertEquals(listOf("全拼", "自然码"), presets.schemes)
    }
    @Test fun auxiliaryChangesDoNotReplaceTheSpellingScheme() {
        val node = y.document("__patch: [wanxiang_algebra:/pro/自然码, wanxiang_algebra:/pro/直接辅助]\n")
        assertEquals(listOf("wanxiang_algebra:/pro/自然码", "wanxiang_algebra:/pro/间接辅助"), refs(presets.select(node, "间接辅助", true)))
    }
    @Test fun extraRulesPreserveUnknownReferencesAndCanBeRemovedAgain() {
        val original = y.document("__patch: [local:/extension, wanxiang_algebra:/pro/全拼]\n")
        val changed = presets.extras(original, setOf("模糊音_nl", "14键"))
        assertTrue("local:/extension" in refs(changed))
        assertEquals(refs(original), refs(presets.extras(changed, emptySet())))
    }
    @Test fun aSingleSchemeSelectionRemovesDuplicateKnownSchemesButRetainsCustomRules() {
        val node = y.document("__patch: [wanxiang_algebra:/pro/全拼, local:/rule, wanxiang_algebra:/pro/自然码]\n")
        assertEquals(listOf("wanxiang_algebra:/pro/自然码", "local:/rule"), refs(presets.select(node, "自然码")))
    }
    @Test fun reverseStrokeKeysAreDiscoveredAndChangedIndependentlyOfPinyin() {
        val reverse = WanxiangAlgebraPresets(y, y.document("base: {全拼: []}\nreverse: {全拼: [], hspzn: [], hupvd: [], hslzy: []}\n"), "wanxiang_reverse.schema.yaml")
        val node = y.document("__patch: [wanxiang_algebra:/reverse/全拼, wanxiang_algebra:/reverse/hspzn, local:/rule]\n")
        assertEquals(listOf("hspzn", "hupvd", "hslzy"), reverse.strokes)
        assertEquals(listOf("wanxiang_algebra:/reverse/全拼", "wanxiang_algebra:/reverse/hupvd", "local:/rule"), refs(reverse.select(node, "hupvd", stroke = true)))
    }
}
