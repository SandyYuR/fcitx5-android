package org.fcitx.directrime

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProfileArchiveTest {
    private fun zip(vararg files: Pair<String, String>): ByteArrayInputStream {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> files.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        return ByteArrayInputStream(output.toByteArray())
    }
    @Test fun rejectsTraversalAbsolutePathsAndOversizedProfilesWithoutChangingOldData() {
        val root = Files.createTempDirectory("rime-import-test").toFile()
        try {
            val user = File(root, "user").apply { mkdir() }
            File(user, "default.yaml").writeText("original")
            listOf("../escape", "/escape", "dir/../../escape", "dir\\escape").forEach { path ->
                val staging = File(root, "incoming")
                try { ProfileArchive.extract(zip("default.yaml" to "new", path to "bad"), staging); fail(path) }
                catch (_: IllegalArgumentException) { assertFalse(staging.exists()) }
            }
            try { ProfileArchive.extract(zip("default.yaml" to "12345"), File(root, "incoming"), 4); fail() }
            catch (_: IllegalArgumentException) { assertFalse(File(root, "incoming").exists()) }
            assertEquals("original", File(user, "default.yaml").readText())
            assertFalse(File(root, "escape").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun installsCompleteCopyAndRetainsPreviousExperimentProfile() {
        val root = Files.createTempDirectory("rime-import-test").toFile()
        try {
            val user = File(root, "user").apply { mkdir() }
            File(user, "default.yaml").writeText("old")
            val staging = File(root, "incoming")
            ProfileArchive.extract(zip("default.yaml" to "new", "build/test.bin" to "data"), staging)
            ProfileArchive.install(staging, user)
            assertEquals("new", File(user, "default.yaml").readText())
            assertEquals("data", File(user, "build/test.bin").readText())
            assertEquals("old", File(root, "previous/default.yaml").readText())
            ProfileArchive.restorePrevious(user)
            assertEquals("old", File(user, "default.yaml").readText())
            assertEquals("new", File(root, "previous/default.yaml").readText())
        } finally { root.deleteRecursively() }
    }
}
