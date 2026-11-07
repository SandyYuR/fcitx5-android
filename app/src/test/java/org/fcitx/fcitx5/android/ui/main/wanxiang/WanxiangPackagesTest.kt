/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import org.junit.Assert.*
import org.junit.Test

class WanxiangPackagesTest {
    @Test fun allVariantsHaveIndependentPublishedAssetNames() {
        val names = WanxiangSelection.Scheme.entries.map { scheme ->
            WanxiangSelection(scheme = scheme).packages().map { it.name }
        }
        assertEquals(4, names.map { it[0] }.distinct().size)
        assertEquals(4, names.map { it[1] }.distinct().size)
        assertEquals("rime-wanxiang-zrm-fuzhu.zip", names[1][0])
        assertEquals("pro-zrm-fuzhu-dicts.zip", names[1][1])
    }

    @Test fun stableAndPreviewOnlyChangeTheSchemeChannel() {
        for (source in WanxiangSelection.Source.entries) {
            val stable = WanxiangSelection(source = source).packages()
            val preview = WanxiangSelection(source = source, channel = WanxiangSelection.Channel.PREVIEW).packages()
            assertNotEquals(stable[0].url, preview[0].url)
            assertEquals(stable.drop(1), preview.drop(1))
            assertTrue(stable[0].url.contains("/latest/download/"))
            assertTrue(preview[0].url.contains(if (source == WanxiangSelection.Source.GITHUB) "/dict-nightly/" else "/v1.0.0/"))
        }
    }

    @Test fun scopedUpdatesDownloadOnlyTheSelectedContent() {
        WanxiangSelection.Scope.entries.filter { it !in setOf(WanxiangSelection.Scope.ALL, WanxiangSelection.Scope.EXTRA) }.forEach { scope ->
            assertEquals(scope.name, WanxiangSelection(scope = scope).packages().single().kind.name)
        }
        assertTrue(runCatching { WanxiangSelection(auxiliary = "../untrusted").packages() }.isFailure)
    }

    @Test fun selectedExtraDictionariesUseTheControlledRepositoryAndOnlyApplicableScopes() {
        val select = WanxiangSelection(extraDictionaries = listOf("medical", "computer"))
        assertEquals(5, select.packages().size)
        val extra = select.copy(scope = WanxiangSelection.Scope.EXTRA).packages()
        assertEquals(listOf("medical.dict.yaml", "computer.dict.yaml"), extra.map { it.name })
        assertTrue(extra.all { it.kind == WanxiangPackage.Kind.EXTRA && it.url.startsWith("https://raw.githubusercontent.com/amzxyz/rime-wanxiang-data/wanxiang/dicts/") })
        assertEquals(1, select.copy(scope = WanxiangSelection.Scope.MODEL).packages().size)
        assertTrue(runCatching { select.copy(extraDictionaries = listOf("../../private")).packages() }.isFailure)
    }
}
