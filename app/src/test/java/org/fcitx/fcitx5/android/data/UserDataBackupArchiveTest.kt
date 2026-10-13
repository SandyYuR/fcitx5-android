package org.fcitx.fcitx5.android.data

import org.fcitx.fcitx5.android.utils.extract
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class UserDataBackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun archiveContainsTheFinalFlushedDataBeforeTheWriterResumes() {
        val cache = temp.newFolder("cache")
        val source = temp.newFolder("source")
        val data = File(source, "words.userdb")
        data.writeText("unflushed")
        val archive = UserDataBackupArchive.prepare(cache,
            pause = { data.writeText("flushed-before-backup") },
            resume = { data.writeText("new-data-after-resume") }) {
            ZipOutputStream(it).use { zip -> BackupFileTree.write(source, "profile", zip) }
        }
        val restored = temp.newFolder("restored")
        ZipInputStream(archive.inputStream()).use { it.extract(restored) }
        assertEquals("flushed-before-backup", File(restored, "profile/words.userdb").readText())
        assertEquals("new-data-after-resume", data.readText())
        assertTrue(archive.length() > 0)
    }

    @Test fun aFailedBackupResumesTheEngineAndReturnsNoPartialArchive() {
        val cache = temp.newFolder("cache")
        var running = true
        try {
            UserDataBackupArchive.prepare(cache, pause = { running = false }, resume = { running = true }) {
                it.write("partial ZIP".toByteArray())
                throw IOException("injected disk failure")
            }
            fail("An incomplete archive must not be published")
        } catch (e: IOException) { assertEquals("injected disk failure", e.message) }
        assertTrue(running)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun aResumeFailureAlsoRemovesTheUnpublishedArchive() {
        val cache = temp.newFolder("cache")
        try {
            UserDataBackupArchive.prepare(cache, pause = { }, resume = { throw IOException("resume failed") }) {
                it.write("completed ZIP".toByteArray())
            }
            fail("A failed preparation must not return an archive")
        } catch (e: IOException) { assertEquals("resume failed", e.message) }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun writerFailureIsPreservedWhenResumingAlsoFails() {
        val cache = temp.newFolder("cache")
        try {
            UserDataBackupArchive.prepare(cache, pause = { }, resume = { throw IOException("resume failed") }) {
                throw IOException("writer failed")
            }
            fail("Preparation must fail")
        } catch (e: IOException) {
            assertEquals("writer failed", e.message)
            assertEquals("resume failed", e.suppressed.single().message)
        }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun restoredPickerStateCannotReadAnUnrelatedFile() {
        val cache = temp.newFolder("cache")
        val outside = temp.newFile("${UserDataBackupArchive.PREFIX}outside.zip")
        outside.writeText("unrelated")
        assertNull(UserDataBackupArchive.pending(outside.absolutePath, cache))
        assertEquals("unrelated", outside.readText())
        val ready = File.createTempFile(UserDataBackupArchive.PREFIX, ".zip", cache)
        assertEquals(ready, UserDataBackupArchive.pending(ready.absolutePath, cache))
    }
}
