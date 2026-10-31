/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BoundedStorageTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun readsAcceptTheLimitAndRejectEvenOneMoreByte() {
        assertArrayEquals(byteArrayOf(1, 2), byteArrayOf(1, 2).inputStream().readBounded(2))
        assertTrue(runCatching { byteArrayOf(1, 2, 3).inputStream().readBounded(2) }.isFailure)
        assertArrayEquals(byteArrayOf(), byteArrayOf().inputStream().readBounded(0))
    }
    @Test fun writerFailureAndImportLimitPreserveThePreviousFile() {
        val file = temp.newFile().apply { writeText("original") }
        assertTrue(runCatching { FileUtil.writeAtomically(file) { it.write(1); throw IOException("full") } }.isFailure)
        assertEquals("original", file.readText())
        assertTrue(runCatching { FileUtil.writeAtomically(file) { ByteArray(3).inputStream().copyBoundedTo(it, 2) } }.isFailure)
        assertEquals("original", file.readText())
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }
    @Test fun aFailedReplacementDoesNotWriteDirectlyToTheTarget() {
        val target = temp.newFolder("target")
        File(target, "keep").writeText("original")
        assertTrue(runCatching { FileUtil.writeAtomically(target, "new") }.isFailure)
        assertEquals("original", File(target, "keep").readText())
    }
    @Test fun temporaryLinksCannotOverwriteAnotherFile() {
        val file = File(temp.root, "new")
        val secret = temp.newFile("secret").apply { writeText("original") }
        org.fcitx.fcitx5.android.testing.FilesystemFixtures.symbolicLink(File(temp.root, "new.tmp").toPath(), secret.toPath())
        assertTrue(runCatching { FileUtil.writeAtomically(file, "new") }.isFailure)
        assertEquals("original", secret.readText())
    }
    @Test fun concurrentWritersEachPublishACompleteFile() {
        val file = temp.newFile()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val writes = (1..20).map { value -> executor.submit { FileUtil.writeAtomically(file, "$value".repeat(1000)) } }
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue((1..20).any { file.readText() == "$it".repeat(1000) })
        } finally { executor.shutdownNow() }
    }
}
