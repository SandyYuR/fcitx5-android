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

class RimeProfileRestorePlanTest {
    @get:Rule val temp = TemporaryFolder()
    private fun write(root: File, path: String, text: String) = File(root, path).apply {
        parentFile!!.mkdirs(); writeText(text)
    }
    private fun hex(value: String) = value.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class Backup(val extracted: File, val source: File, val manifest: RimeProfileBackup.Manifest)

    private fun backup(): Backup {
        val app = temp.newFolder("source-app")
        val source = temp.newFolder("source-external")
        write(source, "my.schema.yaml", "backed-up-schema")
        write(source, "build/my.table.bin", "compiled-dictionary")
        write(source, "words.userdb/000001.ldb", "learned-words")
        val bytes = ByteArrayOutputStream()
        val state = RimeDataDirectoryState("remote", listOf("remote"), mapOf("remote" to source.absolutePath))
        ZipOutputStream(bytes).use { RimeProfileBackup.write(app, state, it) }
        val extracted = temp.newFolder("extracted")
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.extract(extracted) }
        return Backup(extracted, source, RimeProfileBackup.readManifest(extracted))
    }

    private fun restore(backup: Backup, app: File, target: File?) {
        val plan = RimeProfileRestorePlan(mapOf("remote" to target))
        plan.validate(backup.manifest, listOf(app))
        UserDataRestoreTransaction().use { transaction ->
            transaction.stage(app) { plan.copyApplicationData(backup.extracted, it) }
            plan.stageExternalProfiles(backup.extracted, transaction)
            transaction.commit()
        }
    }

    private fun assertPayload(root: File) {
        assertEquals("backed-up-schema", File(root, "my.schema.yaml").readText())
        assertEquals("compiled-dictionary", File(root, "build/my.table.bin").readText())
        assertEquals("learned-words", File(root, "words.userdb/000001.ldb").readText())
    }

    @Test fun originalDirectoryIsRecordedAndCanBeFullyRestored() {
        val backup = backup()
        assertEquals(2, backup.manifest.version)
        assertEquals(backup.source.canonicalPath, backup.manifest.originalPaths.getValue("remote"))
        write(backup.source, "my.schema.yaml", "edited-after-backup")
        write(backup.source, "obsolete.userdb", "must-be-removed")
        val app = temp.newFolder("target-app")
        restore(backup, app, File(backup.manifest.originalPaths.getValue("remote")))
        assertPayload(backup.source)
        assertFalse(File(backup.source, "obsolete.userdb").exists())
        assertFalse(File(app, "data/remote").exists())
        assertTrue(File(app, RimeDataDirectoryState.CONFIG_PATH).readText()
            .contains("${hex("remote")}|${hex(backup.source.canonicalPath)}"))
    }

    @Test fun anotherExternalDirectoryIsReplacedWithoutChangingTheOriginal() {
        val backup = backup()
        write(backup.source, "my.schema.yaml", "source-must-stay")
        val other = temp.newFolder("other")
        write(other, "stale.file", "old-target")
        val app = temp.newFolder("target-app")
        restore(backup, app, other)
        assertPayload(other)
        assertFalse(File(other, "stale.file").exists())
        assertEquals("source-must-stay", File(backup.source, "my.schema.yaml").readText())
        assertFalse(File(app, "data/remote").exists())
        val config = File(app, RimeDataDirectoryState.CONFIG_PATH).readText()
        assertTrue(config.contains("${hex("remote")}|${hex(other.canonicalPath)}"))
        assertFalse(config.contains(hex(backup.source.canonicalPath)))
    }

    @Test fun anAliasedApplicationRootDoesNotBlockOriginalExternalDirectoryReplacement() {
        val backup = backup()
        write(backup.source, "my.schema.yaml", "edited-after-backup")
        write(backup.source, "obsolete.userdb", "must-be-removed")
        val app = temp.newFolder("target-app")
        val sibling = temp.newFolder("sibling")
        restore(backup, File(sibling, "../target-app"), backup.source)
        assertPayload(backup.source)
        assertFalse(File(backup.source, "obsolete.userdb").exists())
        assertTrue(File(app, RimeDataDirectoryState.CONFIG_PATH).isFile)
        assertFalse(File(app, "data/remote").exists())
    }

    @Test fun applicationDirectoryIsAnExplicitChoiceAndLeavesTheExternalSourceUntouched() {
        val backup = backup()
        write(backup.source, "my.schema.yaml", "source-must-stay")
        val app = temp.newFolder("target-app")
        write(app, "data/remote/stale.userdb", "stale-private-data")
        restore(backup, app, null)
        assertPayload(File(app, "data/remote"))
        assertFalse(File(app, "data/remote/stale.userdb").exists())
        assertEquals("source-must-stay", File(backup.source, "my.schema.yaml").readText())
        val config = File(app, RimeDataDirectoryState.CONFIG_PATH).readText()
        assertTrue(config.contains("ExternalPathsV2=\"\""))
        assertFalse(config.contains(hex(backup.source.canonicalPath)))
    }

    @Test fun differentProfilesCanUseDifferentRestoreModes() {
        val extracted = temp.newFolder("extracted")
        write(extracted, "external/data/one/words", "one")
        write(extracted, "external/data/two/words", "two")
        write(extracted, "external/${RimeDataDirectoryState.CONFIG_PATH}", "Current=one\nProfiles=\"one\\ntwo\"\n")
        val app = temp.newFolder("app")
        val external = temp.newFolder("external")
        val plan = RimeProfileRestorePlan(mapOf("one" to external, "two" to null))
        plan.validate(RimeProfileBackup.Manifest(externalProfiles = listOf("one", "two")), listOf(app))
        UserDataRestoreTransaction().use {
            it.stage(app) { staged -> plan.copyApplicationData(extracted, staged) }
            plan.stageExternalProfiles(extracted, it)
            it.commit()
        }
        assertEquals("one", File(external, "words").readText())
        assertEquals("two", File(app, "data/two/words").readText())
        assertFalse(File(app, "data/one").exists())
        assertTrue(File(app, RimeDataDirectoryState.CONFIG_PATH).readText().contains("Current=one"))
    }

    @Test fun failedExternalReplacementRollsBackTheApplicationAndEarlierExternalTargets() {
        val extracted = temp.newFolder("extracted")
        write(extracted, "external/data/one/words", "new-one")
        write(extracted, "external/data/two/words", "new-two")
        val app = temp.newFolder("app")
        val one = temp.newFolder("one")
        val two = temp.newFolder("two")
        write(app, "original", "original-app")
        write(one, "words", "original-one")
        write(two, "words", "original-two")
        val plan = RimeProfileRestorePlan(mapOf("one" to one, "two" to two))
        try {
            UserDataRestoreTransaction { from, to ->
                if (from.name.startsWith(".two.import-")) false else from.renameTo(to)
            }.use {
                it.stage(app) { staged -> plan.copyApplicationData(extracted, staged) }
                plan.stageExternalProfiles(extracted, it)
                assertEquals("original-one", File(one, "words").readText())
                it.commit()
            }
            fail("Replacement should fail")
        } catch (_: IOException) { }
        assertEquals("original-app", File(app, "original").readText())
        assertFalse(File(app, RimeDataDirectoryState.CONFIG_PATH).exists())
        assertEquals("original-one", File(one, "words").readText())
        assertEquals("original-two", File(two, "words").readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun everyExternalProfileRequiresAnExplicitChoice() {
        RimeProfileRestorePlan(emptyMap()).validate(RimeProfileBackup.Manifest(externalProfiles = listOf("remote")), emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun externalTargetCannotContainApplicationData() {
        val shared = temp.newFolder("shared")
        val app = File(shared, "app").apply { mkdir() }
        RimeProfileRestorePlan(mapOf("remote" to shared))
            .validate(RimeProfileBackup.Manifest(externalProfiles = listOf("remote")), listOf(app))
    }

    @Test(expected = IllegalArgumentException::class)
    fun externalTargetCannotBeInsideApplicationData() {
        val app = temp.newFolder("app")
        RimeProfileRestorePlan(mapOf("remote" to File(app, "data/remote")))
            .validate(RimeProfileBackup.Manifest(externalProfiles = listOf("remote")), listOf(app))
    }

    @Test(expected = IllegalArgumentException::class)
    fun externalTargetsCannotOverlap() {
        val external = temp.newFolder("external")
        RimeProfileRestorePlan(mapOf("one" to external, "two" to File(external, "child")))
            .validate(RimeProfileBackup.Manifest(externalProfiles = listOf("one", "two")), emptyList())
    }

    @Test(expected = IOException::class)
    fun aFileCannotBeSelectedAsARestoreDirectory() {
        val file = temp.newFile("file")
        RimeProfileRestorePlan(mapOf("remote" to file))
            .validate(RimeProfileBackup.Manifest(externalProfiles = listOf("remote")), emptyList())
    }
}
