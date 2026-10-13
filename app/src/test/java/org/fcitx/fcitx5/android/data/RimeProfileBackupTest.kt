package org.fcitx.fcitx5.android.data

import org.fcitx.fcitx5.android.utils.extract
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RimeProfileBackupTest {
    @get:Rule val temp = TemporaryFolder()

    private fun write(root: File, path: String, text: String) = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    @Test fun completeBackupContainsActualExternalDataAndPortableConfiguration() {
        val app = temp.newFolder("app")
        val external = temp.newFolder("syncthing")
        write(app, "data/rime/default.custom.yaml", "internal schema")
        write(app, "data/remote/stale.userdb", "stale private data")
        val original = write(app, RimeDataDirectoryState.CONFIG_PATH, "ExternalPaths=remote|source-path\n")
        write(external, "my.schema.yaml", "external schema")
        write(external, "words.userdb/000001.ldb", "learned words")
        val state = RimeDataDirectoryState("remote", listOf("rime", "remote"),
            mapOf("rime" to File(app, "data/rime").absolutePath, "remote" to external.absolutePath))
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { RimeProfileBackup.write(app, state, it) }
        val restored = temp.newFolder("restored")
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { it.extract(restored) }
        assertEquals("external schema", File(restored, "external/data/remote/my.schema.yaml").readText())
        assertEquals("learned words", File(restored, "external/data/remote/words.userdb/000001.ldb").readText())
        assertFalse(File(restored, "external/data/remote/stale.userdb").exists())
        assertEquals(state.portableConfig(), File(restored, "external/${RimeDataDirectoryState.CONFIG_PATH}").readText())
        assertEquals(listOf("remote"), RimeProfileBackup.restoredExternalProfiles(restored))
        assertEquals("ExternalPaths=remote|source-path\n", original.readText())
        assertTrue(File(external, "my.schema.yaml").exists())
    }

    @Test(expected = IOException::class)
    fun unavailableExternalDirectoryCannotProduceACompleteBackup() {
        val app = temp.newFolder("app")
        val state = RimeDataDirectoryState("remote", listOf("remote"),
            mapOf("remote" to File(temp.root, "missing").absolutePath))
        ZipOutputStream(ByteArrayOutputStream()).use { RimeProfileBackup.write(app, state, it) }
    }

    @Test(expected = IOException::class)
    fun aFileChangingDuringBackupIsReportedAsFailure() {
        val source = temp.newFolder("changing")
        val file = File(source, "words.userdb")
        file.writeBytes(ByteArray(64 * 1024).also { java.util.Random(42).nextBytes(it) })
        var changed = false
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                super.write(bytes, offset, count)
                if (!changed && size() > 1024) {
                    changed = true
                    file.appendText("changed during backup")
                }
            }
        }
        ZipOutputStream(output).use { BackupFileTree.write(source, "profile", it) }
    }

    @Test fun timestampRefreshWithUnchangedContentsDoesNotInvalidateTheArchive() {
        val source = temp.newFolder("metadata-refresh")
        val file = File(source, "CURRENT")
        val bytes = ByteArray(64 * 1024).also { java.util.Random(17).nextBytes(it) }
        file.writeBytes(bytes)
        val older = file.lastModified() - 3_600_000
        var changed = false
        val output = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, count: Int) {
                super.write(buffer, offset, count)
                if (!changed && size() > 1024) {
                    changed = true
                    assertTrue(file.setLastModified(older))
                }
            }
        }
        ZipOutputStream(output).use { BackupFileTree.write(source, "profile", it) }
        assertTrue(changed)
        val restored = temp.newFolder("metadata-restored")
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { it.extract(restored) }
        assertArrayEquals(bytes, File(restored, "profile/CURRENT").readBytes())
    }

    @Test(expected = BackupFileChangedException::class)
    fun sameSizeContentChangesStillFailAfterTimestampVerification() {
        val source = temp.newFolder("same-size-change")
        val file = File(source, "CURRENT")
        val bytes = ByteArray(64 * 1024).also { java.util.Random(19).nextBytes(it) }
        file.writeBytes(bytes)
        val modified = file.lastModified() + 10_000
        var changed = false
        val output = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, count: Int) {
                super.write(buffer, offset, count)
                if (!changed && size() > 1024) {
                    changed = true
                    file.writeBytes(bytes.clone().also { it[0] = (it[0].toInt() xor 255).toByte() })
                    assertTrue(file.setLastModified(modified))
                }
            }
        }
        ZipOutputStream(output).use { BackupFileTree.write(source, "profile", it) }
    }

    @Test(expected = IOException::class)
    fun oldPointerOnlyBackupIsRejectedBeforeRestore() {
        val extracted = temp.newFolder("extracted")
        write(extracted, "external/${RimeDataDirectoryState.CONFIG_PATH}", "ExternalPaths=\"remote|/storage/source-phone\"\n")
        RimeProfileBackup.restoredExternalProfiles(extracted)
    }

    @Test fun ordinaryOldBackupRemainsCompatible() {
        val extracted = temp.newFolder("extracted")
        write(extracted, "external/${RimeDataDirectoryState.CONFIG_PATH}", "Current=rime\nProfiles=rime\n")
        assertTrue(RimeProfileBackup.restoredExternalProfiles(extracted).isEmpty())
    }

    @Test fun versionOnePortableBackupHasNoOriginalPathHint() {
        val extracted = temp.newFolder("extracted")
        write(extracted, "external/data/remote/words", "old-backup-words")
        write(extracted, RimeProfileBackup.MANIFEST_NAME, "{\"version\":1,\"externalProfiles\":[\"remote\"]}")
        val manifest = RimeProfileBackup.readManifest(extracted)
        assertEquals(listOf("remote"), manifest.externalProfiles)
        assertTrue(manifest.originalPaths.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aVersionTwoBackupMustRecordAnAbsolutePathForEachExternalProfile() {
        val extracted = temp.newFolder("extracted")
        write(extracted, "external/data/remote/words", "words")
        write(extracted, RimeProfileBackup.MANIFEST_NAME,
            "{\"version\":2,\"externalProfiles\":[\"remote\"],\"originalPaths\":{\"remote\":\"../outside\"}}")
        RimeProfileBackup.readManifest(extracted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun maliciousProfileManifestCannotSelectAnOutsideRestoreDestination() {
        val extracted = temp.newFolder("extracted")
        write(extracted, RimeProfileBackup.MANIFEST_NAME, "{\"externalProfiles\":[\"../outside\"]}")
        RimeProfileBackup.restoredExternalProfiles(extracted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteProfilePayloadIsRejected() {
        val extracted = temp.newFolder("extracted")
        write(extracted, RimeProfileBackup.MANIFEST_NAME, "{\"externalProfiles\":[\"remote\"]}")
        RimeProfileBackup.restoredExternalProfiles(extracted)
    }
}
