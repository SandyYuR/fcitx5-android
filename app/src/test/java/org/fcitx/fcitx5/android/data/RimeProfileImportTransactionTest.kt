/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RimeProfileImportTransactionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun zip(file: File, vararg entries: Pair<String, String>) {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        file.writeBytes(bytes)
    }

    private fun fixture(timestamp: Long = 100): Pair<RimeProfileImportTransaction, File> {
        val imports = temporary.newFolder("imports-$timestamp")
        val parent = temporary.newFolder("profiles-$timestamp")
        val archive = File(imports, "profile.zip")
        zip(archive, "default.yaml" to "schema_list: []\n", "custom.dict.yaml" to "entries")
        return RimeProfileImportTransaction.prepare(
            archive.absolutePath,
            imports,
            parent,
            emptySet(),
            timestamp,
        ) to parent
    }

    @Test fun prepareConfinesArchivesAndChoosesAnUnusedProfileName() {
        val imports = temporary.newFolder("imports")
        val parent = temporary.newFolder("profiles")
        val archive = File(imports, "profile.zip")
        zip(archive, "default.yaml" to "schema_list: []\n")
        File(parent, "import-10").mkdir()
        File(parent, ".import-11.importing").mkdir()

        val transaction = RimeProfileImportTransaction.prepare(
            archive.absolutePath,
            imports,
            parent,
            setOf("import-12"),
            timestamp = 10,
        )

        assertEquals("import-13", transaction.name)
        assertEquals(File(parent, "import-13").canonicalFile, transaction.target.canonicalFile)
        val outside = File(temporary.root, "outside.zip").apply { writeBytes(archive.readBytes()) }
        assertThrows(IllegalArgumentException::class.java) {
            RimeProfileImportTransaction.prepare(outside.absolutePath, imports, parent, emptySet(), 20)
        }
    }

    @Test fun installPublishesOnlyTheCompleteExtractedDirectory() {
        val (transaction, parent) = fixture()

        val target = transaction.install()

        assertEquals("schema_list: []\n", File(target, "default.yaml").readText())
        assertEquals("entries", File(target, "custom.dict.yaml").readText())
        assertFalse(File(parent, ".import-100.importing").exists())
    }

    @Test fun invalidArchiveLeavesNeitherStagingNorTarget() {
        val imports = temporary.newFolder("unsafe-imports")
        val parent = temporary.newFolder("unsafe-profiles")
        val archive = File(imports, "profile.zip")
        zip(archive, "../escape" to "bad")
        val transaction = RimeProfileImportTransaction.prepare(
            archive.absolutePath, imports, parent, emptySet(), timestamp = 200
        )

        assertThrows(IllegalArgumentException::class.java) { transaction.install() }

        assertFalse(File(parent, ".import-200.importing").exists())
        assertFalse(File(parent, "import-200").exists())
    }

    @Test fun failureCleanupRemovesOnlyAnUnregisteredInstall() {
        val (unregistered, _) = fixture(300)
        val unregisteredTarget = unregistered.install()
        unregistered.recoverAfterFailure()
        assertFalse(unregisteredTarget.exists())

        val (registered, _) = fixture(400)
        val registeredTarget = registered.install()
        registered.markRegistered()
        registered.recoverAfterFailure()
        assertTrue(registeredTarget.isDirectory)
        assertTrue(File(registeredTarget, "default.yaml").isFile)
    }
}
