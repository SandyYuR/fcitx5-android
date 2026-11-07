/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WanxiangDictionaryConverterTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun liteRemovesTonesPreservingUmlautsAndSyllabicReadings() {
        assertEquals("nv lv me me en en en", WanxiangDictionaryConverter.lite("nǚ lǜ ḿ m\u0300 ń ň ǹ"))
        val source = temp.newFile().apply { writeText("# dictionary\n---\nname: medical\nversion: '1'\n...\n女儿\tnǚ ér\t100\n") }
        val target = File(temp.root, "medical.lite.dict.yaml")
        WanxiangDictionaryConverter.convert(source, target, "medical.lite", WanxiangSelection.Scheme.LITE, emptyMap())
        assertTrue(target.readText().contains("name: medical.lite"))
        assertTrue(target.readText().contains("女儿\tnv er\t100"))
        assertTrue(source.readText().contains("nǚ ér"))
    }
    @Test fun litePreservesCharactersOutsideTheUpstreamToneMapping() {
        assertEquals("ê ñ å a\u0301", WanxiangDictionaryConverter.lite("ê ñ å a\u0301"))
    }

    @Test fun cancellingDictionaryConversionPreservesThePreviousFile() {
        val source = temp.newFile().apply { writeText("---\nname: medical\n...\n女儿\tnǚ ér\t100\n") }
        val target = temp.newFile("medical.lite.dict.yaml").apply { writeText("previous dictionary") }
        var checked = 0
        val result = runCatching {
            WanxiangDictionaryConverter.convert(source, target, "medical.lite", WanxiangSelection.Scheme.LITE, emptyMap()) {
                if (++checked == 4) throw CancellationException("cancelled")
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals("previous dictionary", target.readText())
        assertFalse(File(target.parentFile, target.name + ".tmp").exists())
    }

    @Test fun cancellingAuxiliaryReadingDoesNotReturnAPartialCharacterTable() {
        val table = temp.newFile().apply { writeText("中\tzhong;ab\n国\tguo;cd\n") }
        var checked = 0
        val result = runCatching {
            WanxiangDictionaryConverter.auxiliary(table) {
                if (++checked == 2) throw CancellationException("cancelled")
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
    @Test fun proUsesCharacterCodesAndPreservesAnUnalignableLine() {
        val aux = mapOf("中" to "aa", "国" to "bb", "𠀀" to "cc")
        assertEquals("zhong;aa guo;bb", WanxiangDictionaryConverter.pro("中国", "zhong;old guo[old", aux))
        assertEquals("yi;cc", WanxiangDictionaryConverter.pro("𠀀", "yi", aux))
        assertEquals("zhong;old", WanxiangDictionaryConverter.pro("中国", "zhong;old", aux))
    }
    @Test fun latinRunsCanConsumeSeveralSyllablesWithoutShiftingTheChineseAuxiliary() {
        val result = WanxiangDictionaryConverter.pro("IBM中国", "i b m zhong guo", mapOf("中" to "aa", "国" to "bb"))
        assertEquals("i; b; m; zhong;aa guo;bb", result)
    }
    @Test fun auxiliaryTableIsReadAsUnicodeCharactersAndInvalidDownloadsAreRejected() {
        val table = temp.newFile().apply { writeText("# table\n中\tzhong;ab\t100\n𠀀\tyi;cd\t50\n") }
        assertEquals(mapOf("中" to "ab", "𠀀" to "cd"), WanxiangDictionaryConverter.auxiliary(table))
        val html = temp.newFile().apply { writeText("<html>not a dictionary</html>") }
        val target = File(temp.root, "bad.dict.yaml")
        assertTrue(runCatching { WanxiangDictionaryConverter.convert(html, target, "bad", WanxiangSelection.Scheme.BASE, emptyMap()) }.isFailure)
        assertFalse(target.exists())
    }
}
