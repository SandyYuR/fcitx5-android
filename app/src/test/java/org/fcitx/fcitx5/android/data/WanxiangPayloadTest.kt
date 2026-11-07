/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WanxiangPayloadTest {
    @get:Rule val temp = TemporaryFolder()
    private fun zip(vararg entries: Pair<String, String>): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { out -> entries.forEach { (path, body) ->
            out.putNextEntry(ZipEntry(path)); out.write(body.toByteArray()); out.closeEntry()
        } }
    }

    @Test fun schemeArchiveExcludesUserStateAndUnrelatedFiles() {
        val payload = temp.newFolder()
        WanxiangPayload.extract(zip("wanxiang.schema.yaml" to "schema", "default.yaml" to "default",
            "lua/wanxiang/userdb.lua" to "code", "lua/module.lua" to "code", "AGENTS.md" to "instructions",
            "wanxiang.custom.yaml" to "patch", "sync/user/data" to "sync", "wanxiang.userdb/data" to "db",
            "custom_phrase.dict.yaml" to "personal", "build/compiled.yaml" to "compiled"), payload, false)
        assertEquals(setOf("wanxiang.schema.yaml", "default.yaml", "lua/module.lua", "lua/wanxiang/userdb.lua"),
            payload.walkTopDown().filter { it.isFile }.map { it.relativeTo(payload).invariantSeparatorsPath }.toSet())
    }

    @Test fun dictionaryArchivesWithAndWithoutDirectoryWrappersHaveTheSameDestination() {
        for (prefix in listOf("", "dicts/", "release/")) {
            val payload = temp.newFolder()
            WanxiangPayload.extract(zip("${prefix}zi.dict.yaml" to "dictionary"), payload, true)
            assertEquals("dictionary", File(payload, "dicts/zi.dict.yaml").readText())
            assertFalse(File(payload, "dicts/dicts").exists())
        }
    }

    @Test fun maliciousArchivePathsCannotEscapeOrBecomeAbsolute() {
        for (path in listOf("../escape.lua", "/escape.lua", "C:/escape.lua", "lua/../../escape.lua", "lua\\escape.lua")) {
            assertTrue(path, runCatching { WanxiangPayload.extract(zip(path to "bad"), temp.newFolder(), false) }.isFailure)
        }
    }

    @Test fun htmlDownloadsAndUnrelatedArchivesAreRejected() {
        val html = temp.newFile().apply { writeText("<html>download error</html>") }
        assertTrue(runCatching { WanxiangPayload.extract(html, temp.newFolder(), false) }.isFailure)
        assertTrue(runCatching { WanxiangPayload.extract(zip("random.yaml" to "x"), temp.newFolder(), false) }.isFailure)
    }

    @Test fun modelHeaderIsCheckedBeforeStaging() {
        val payload = temp.newFolder()
        val html = temp.newFile().apply { writeText("<html>not a grammar model</html>") }
        assertTrue(runCatching { WanxiangPayload.model(html, payload) }.isFailure)
        assertFalse(File(payload, "wanxiang-lts-zh-hans.gram").exists())
        val model = temp.newFile().apply { writeBytes("Rime::Grammar/1.0\u0000fixture".toByteArray()) }
        WanxiangPayload.model(model, payload)
        assertArrayEquals(model.readBytes(), File(payload, "wanxiang-lts-zh-hans.gram").readBytes())
    }
}
