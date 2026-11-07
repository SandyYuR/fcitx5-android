/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.app.Application
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = Application::class)
class RimeSchemePayloadTest {
    private fun root() = File(RuntimeEnvironment.getApplication().cacheDir, UUID.randomUUID().toString()).apply { check(mkdirs()) }
    private fun task() = RimeSchemeTask(name = "方案", source = "https://example.test/release?id=1", fileName = "scheme.zip")
    private fun zip(vararg files: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out -> files.forEach { (name, value) ->
            out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray()); out.closeEntry()
        } }
    }.toByteArray()
    private fun stage(root: File, bytes: ByteArray, task: RimeSchemeTask = task(), limit: Long = RimeSchemePayload.MAX_BYTES): File {
        val input = File(root, "source").apply { writeBytes(bytes) }
        val output = File(root, "payload")
        RimeSchemePayload.stage(input, task, File(root, "scratch"), output, limit)
        assertFalse(File(root, "scratch").exists())
        return output
    }

    @Test fun sourceAndDestinationValidationDoesNotAcceptEscapesOrRuntimeState() {
        task().copy(source = "HTTPS://example.test/release?asset=zip").validated()
        assertTrue(task().copy(source = "HTTPS://example.test/release").remote)
        task().copy(source = "content://provider/document/a", fileName = "资源.zip").validated()
        for (path in listOf("../outside", "/absolute", "a/../b", "a\\b", "a:c", "build", "sync", "x.userdb", ".hidden")) {
            assertThrows(IllegalArgumentException::class.java) { task().copy(destinations = listOf(path)).validated() }
        }
        assertThrows(IllegalArgumentException::class.java) { task().copy(excludes = "[").validated() }
        assertThrows(IllegalArgumentException::class.java) { task().copy(source = "https://user:secret@example.test/archive").validated() }
        assertThrows(IllegalArgumentException::class.java) { task().copy(fileName = "user.yaml").validated() }
    }

    @Test fun archiveDetectionUsesBytesAndPreservesSchemeSubdirectoriesAndExclusions() {
        val payload = stage(root(), zip("ice-main/ice.schema.yaml" to "schema", "ice-main/cn_dicts/base.dict.yaml" to "dict",
            "ice-main/lua/filter.lua" to "lua", "ice-main/default.custom.yaml" to "private override",
            "ice-main/user.yaml" to "runtime", "ice-main/ice.userdb/123" to "database", "ice-main/build/schema.bin" to "build"))
        assertEquals(setOf("ice.schema.yaml", "cn_dicts/base.dict.yaml", "lua/filter.lua"),
            payload.walkTopDown().filter(File::isFile).map { it.relativeTo(payload).invariantSeparatorsPath }.toSet())
    }

    @Test fun callersCanRetainTheWrapperAndExplicitlyChooseResourceOverrides() {
        val payload = stage(root(), zip("wrapper/default.custom.yaml" to "config", "wrapper/installation.yaml" to "state"),
            task().copy(stripRoot = false, excludes = ""))
        assertEquals("config", File(payload, "wrapper/default.custom.yaml").readText())
        assertFalse(File(payload, "wrapper/installation.yaml").exists())
    }

    @Test fun excludingMetadataMustNotTurnARealLuaDirectoryIntoAnArchiveWrapper() {
        val payload = stage(root(), zip(".gitignore" to "ignored", "lua/filter.lua" to "module"))
        assertEquals("module", File(payload, "lua/filter.lua").readText())
        assertFalse(File(payload, "filter.lua").exists())
    }

    @Test fun rawFilesAndMultipleRelativeTargetsUseTheRequestedName() {
        val payload = stage(root(), "module".toByteArray(), task().copy(unpack = false, fileName = "filter.lua", destinations = listOf("lua", "extras/lua")))
        assertEquals("module", File(payload, "lua/filter.lua").readText())
        assertEquals("module", File(payload, "extras/lua/filter.lua").readText())
    }

    @Test fun traversalDuplicatePathsAndExpansionLimitsFailBeforeAnyLiveWrite() {
        for (files in listOf(arrayOf("../escape.yaml" to "x"), arrayOf("root/../../escape.yaml" to "x"),
            arrayOf("A.yaml" to "one", "a.yaml" to "two"))) {
            val dir = root()
            assertThrows(IllegalArgumentException::class.java) { stage(dir, zip(*files)) }
            assertFalse(File(dir, "scratch").exists())
            assertFalse(File(dir, "escape.yaml").exists())
        }
        val dir = root()
        assertThrows(IllegalArgumentException::class.java) {
            stage(dir, zip(".ignored/blob" to "x".repeat(8192)), limit = 1024)
        }
        assertFalse(File(dir, "scratch").exists())
    }

    @Test fun tarGzipBzipAndXzWorkWithoutCallingDeviceShellTools() {
        val tar = ByteArrayOutputStream().also { bytes -> TarArchiveOutputStream(bytes).use { out ->
            val content = "dictionary".toByteArray()
            out.putArchiveEntry(TarArchiveEntry("release/cn_dicts/data.dict.yaml").apply { size = content.size.toLong() })
            out.write(content); out.closeArchiveEntry()
        } }.toByteArray()
        val compressed = listOf(tar,
            ByteArrayOutputStream().also { GZIPOutputStream(it).use { out -> out.write(tar) } }.toByteArray(),
            ByteArrayOutputStream().also { BZip2CompressorOutputStream(it).use { out -> out.write(tar) } }.toByteArray(),
            ByteArrayOutputStream().also { XZCompressorOutputStream(it).use { out -> out.write(tar) } }.toByteArray())
        compressed.forEach { bytes -> assertEquals("dictionary", File(stage(root(), bytes), "cn_dicts/data.dict.yaml").readText()) }
    }

    @Test fun tarLinksAreRejectedAndNoExternalFileIsTouched() {
        val bytes = ByteArrayOutputStream().also { stream -> TarArchiveOutputStream(stream).use { out ->
            out.putArchiveEntry(TarArchiveEntry("link", TarConstants.LF_SYMLINK).apply { linkName = "../../outside" })
            out.closeArchiveEntry()
        } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { stage(root(), bytes) }
    }

    @Test fun paxLongNamesAreSupportedButOversizedMetadataIsRejectedBeforeAllocation() {
        val name = "release/" + "long".repeat(30) + "/dictionary.dict.yaml"
        val bytes = ByteArrayOutputStream().also { stream -> TarArchiveOutputStream(stream).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            out.putArchiveEntry(TarArchiveEntry(name).apply { size = 1 })
            out.write(1); out.closeArchiveEntry()
        } }.toByteArray()
        assertEquals(1L, File(stage(root(), bytes), name.removePrefix("release/")).length())
        val header = ByteArray(512)
        TarArchiveEntry("pax", TarConstants.LF_PAX_EXTENDED_HEADER_LC).apply { size = 1024 * 1024 }.writeEntryHeader(header)
        val error = assertThrows(IllegalArgumentException::class.java) { stage(root(), header) }
        assertTrue(error.message.orEmpty().contains("元数据"))
    }

    @Test fun genericTransactionsRestoreResourcesAfterDeploymentFailureAndRecoverOldJournals() {
        val dir = root()
        val user = File(dir, "user").apply { mkdirs() }
        File(user, "cn_dicts").mkdirs()
        File(user, "cn_dicts/old.dict.yaml").writeText("old")
        val payload = stage(dir, zip("repo/cn_dicts/old.dict.yaml" to "new", "repo/en_dicts/new.dict.yaml" to "new"))
        assertThrows(IllegalStateException::class.java) {
            WanxiangUpdateTransaction.install(user, payload, { error("deployment failed") }, {}, generic = true)
        }
        assertEquals("old", File(user, "cn_dicts/old.dict.yaml").readText())
        assertFalse(File(user, "en_dicts/new.dict.yaml").exists())
        WanxiangUpdateTransaction.recover(user)
        WanxiangUpdateTransaction.install(user, payload, {}, {}, generic = true)
        assertEquals("new", File(user, "en_dicts/new.dict.yaml").readText())
        assertFalse(File(user, ".wanxiang-update").exists())
    }
}
