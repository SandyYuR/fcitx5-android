package org.fcitx.fcitx5.android.data

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

/** Read the currently registered directories; write only private temporary archives, never restore. */
class UserDataBackupSnapshotTest {
    @Test fun currentProfilesCanBeBackedUpAndTheEngineResumes(): Unit = runBlocking {
        // The user's external directory can be large; limit readiness waits, not the
        // entire snapshot. Keep ownership even when cancellation occurs after copying.
        var ownedArchive: File? = null
        try {
            val archive = try {
                withContext(NonCancellable) { UserDataBackupManager.prepare().also { ownedArchive = it } }
            } catch (e: BackupFileChangedException) {
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("changed_file_category", e.category)
                    putLong("before_length", e.beforeLength)
                    putLong("after_length", e.afterLength)
                    putLong("before_modified", e.beforeModified)
                    putLong("after_modified", e.afterModified)
                })
                throw e
            }
            assertTrue(archive.length() > 0)
            val entries = mutableSetOf<String>()
            var manifest: RimeProfileBackup.Manifest? = null
            withContext(Dispatchers.IO) {
                ZipInputStream(archive.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries += entry.name
                        if (entry.name == RimeProfileBackup.MANIFEST_NAME) {
                            manifest = Json.decodeFromString(zip.readBytes().toString(Charsets.UTF_8))
                        }
                        zip.closeEntry()
                    }
                }
            }
            assertTrue(entries.contains("metadata.json"))
            assertNotNull(manifest)
            val profiles = manifest!!.externalProfiles
            profiles.forEach { name -> assertTrue(entries.contains("external/data/$name/")) }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putInt("verified_external_profiles", profiles.size)
                putLong("verified_archive_bytes", archive.length())
            })
            val name = "BackupReadyCheck-${UUID.randomUUID()}"
            val connection = FcitxDaemon.connect(name)
            try { withTimeout(120_000) { connection.runOnReady { } } } finally { FcitxDaemon.disconnect(name) }
        } finally { withContext(NonCancellable + Dispatchers.IO) { ownedArchive?.delete() } }
    }

    @Test fun writeFailureResumesTheEngineAndRemovesTheTemporaryOutput(): Unit = runBlocking {
        withTimeout(120_000) {
            val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val testDir = File(cache, "backup-failure-test-${UUID.randomUUID()}").apply { check(mkdir()) }
            val name = "BackupFailureCheck-${UUID.randomUUID()}"
            val connection = FcitxDaemon.connect(name)
            try {
                connection.runOnReady { save() }
                try {
                    withContext(Dispatchers.IO) {
                        UserDataBackupArchive.prepare(testDir,
                            pause = { FcitxDaemon.beginUserDataBackup() },
                            resume = { FcitxDaemon.finishUserDataBackup() }) {
                            it.write("partial backup".toByteArray())
                            throw IOException("injected backup write failure")
                        }
                    }
                    fail("Backup must fail")
                } catch (e: IOException) { assertEquals("injected backup write failure", e.message) }
                assertTrue(testDir.listFiles()!!.isEmpty())
                connection.runOnReady { }
            } finally {
                FcitxDaemon.disconnect(name)
                check(testDir.parentFile == cache)
                testDir.deleteRecursively()
            }
        }
    }
}
