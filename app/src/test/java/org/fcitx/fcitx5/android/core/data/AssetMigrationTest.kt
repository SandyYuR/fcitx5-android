package org.fcitx.fcitx5.android.core.data

import android.app.Application
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.BuildConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class, shadows = [AssetFileOs::class])
class AssetMigrationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun write(root: File, path: String, text: String): File = File(root, path).apply {
        parentFile!!.mkdirs(); writeText(text)
    }
    private fun plan(old: DataDescriptor, files: Map<String, String>, links: Map<String, String> = emptyMap()): List<FileAction> {
        val hierarchy = DataHierarchy().apply { install(DataDescriptor("new", files, links), FileSource.Main) }
        return DataHierarchy.diff(old, hierarchy)
    }
    private fun migration(root: File) = AssetMigration(root) { "new asset".byteInputStream() }

    @Test fun symlinkToFileDoesNotOverwriteOrDeleteItsExternalTarget() {
        val root = temp.newFolder("root")
        val victim = temp.newFile("outside").apply { writeText("keep me") }
        File(root, "usr").mkdir()
        org.fcitx.fcitx5.android.testing.FilesystemFixtures.symbolicLink(File(root, "usr/item").toPath(), victim.toPath())
        val old = DataDescriptor("old", mapOf("usr" to ""), mapOf("usr/item" to victim.path))
        val actions = plan(old, mapOf("usr" to "", "usr/item" to "hash"))
        migration(root).apply(actions)
        assertEquals("keep me", victim.readText())
        assertFalse(Files.isSymbolicLink(File(root, "usr/item").toPath()))
        assertEquals("new asset", File(root, "usr/item").readText())
    }

    @Test fun regularFileCanBecomeADirectoryWithChildren() {
        val root = temp.newFolder("root")
        write(root, "usr/item", "old file")
        val actions = plan(DataDescriptor("old", mapOf("usr" to "", "usr/item" to "old hash")),
            mapOf("usr" to "", "usr/item" to "", "usr/item/child" to "hash"))
        migration(root).apply(actions)
        assertTrue(File(root, "usr/item").isDirectory)
        assertEquals("new asset", File(root, "usr/item/child").readText())
    }

    @Test fun directoryCanBecomeAFileWithoutFollowingChildLinks() {
        val root = temp.newFolder("root")
        val outside = temp.newFolder("outside")
        write(outside, "kept", "untouched")
        write(root, "usr/item/child", "old")
        org.fcitx.fcitx5.android.testing.FilesystemFixtures.symbolicLink(File(root, "usr/item/link").toPath(), outside.toPath())
        org.fcitx.fcitx5.android.testing.FilesystemFixtures.symbolicLink(File(root, "usr/item/dangling").toPath(), File(outside, "missing").toPath())
        val actions = plan(DataDescriptor("old", mapOf("usr" to "", "usr/item" to "", "usr/item/child" to "old")),
            mapOf("usr" to "", "usr/item" to "hash"))
        migration(root).apply(actions)
        assertEquals("untouched", File(outside, "kept").readText())
        assertEquals("new asset", File(root, "usr/item").readText())
    }

    @Test fun regularFileCanBecomeALinkAfterItsSourceIsCreated() {
        val root = temp.newFolder("root")
        write(root, "usr/item", "old")
        val actions = plan(DataDescriptor("old", mapOf("usr" to "", "usr/item" to "old")),
            mapOf("usr" to "", "usr/source" to "hash"), mapOf("usr/item" to "usr/source"))
        migration(root).apply(actions)
        assertTrue(Files.isSymbolicLink(File(root, "usr/item").toPath()))
        assertEquals("new asset", File(root, "usr/item").readText())
    }

    @Test fun emptyDirectoriesAreCreatedWhenTheirPreviousTypeWasAFile() {
        val root = temp.newFolder("root")
        write(root, "usr/empty", "old")
        migration(root).apply(plan(DataDescriptor("old", mapOf("usr" to "", "usr/empty" to "old")),
            mapOf("usr" to "", "usr/empty" to "")))
        assertTrue(File(root, "usr/empty").isDirectory)
        assertTrue(File(root, "usr/empty").listFiles()!!.isEmpty())
    }

    @Test fun aMissingOldDescriptorStillReplacesAStaleParentSymlinkSafely() {
        val root = temp.newFolder("root")
        val outside = temp.newFolder("outside")
        write(outside, "child", "keep")
        File(root, "usr").mkdir()
        org.fcitx.fcitx5.android.testing.FilesystemFixtures.symbolicLink(File(root, "usr/item").toPath(), outside.toPath())
        migration(root).apply(plan(DataDescriptor("old", emptyMap()),
            mapOf("usr" to "", "usr/item" to "", "usr/item/child" to "hash")))
        assertEquals("keep", File(outside, "child").readText())
        assertEquals("new asset", File(root, "usr/item/child").readText())
    }

    @Test fun failedAtomicCopyKeepsThePreviousFileAndCanBeRetried() {
        val root = temp.newFolder("root")
        val target = write(root, "usr/item", "old contents")
        val actions = plan(DataDescriptor("old", mapOf("usr" to "", "usr/item" to "old hash")),
            mapOf("usr" to "", "usr/item" to "new hash"))
        val broken = AssetMigration(root) {
            object : InputStream() { override fun read(): Int = throw IOException("injected read failure") }
        }
        assertTrue(runCatching { broken.apply(actions) }.isFailure)
        assertEquals("old contents", target.readText())
        migration(root).apply(actions)
        assertEquals("new asset", target.readText())
    }

    @Test fun interruptedDirectoryReplacementCanReplayWithoutDeletingThroughTheNewLink() {
        val root = temp.newFolder("root")
        write(root, "usr/source/child", "new retained source")
        org.fcitx.fcitx5.android.testing.FilesystemFixtures.symbolicLink(File(root, "usr/item").toPath(), File(root, "usr/source").toPath())
        val old = DataDescriptor("old", mapOf("usr" to "", "usr/item" to "", "usr/item/child" to "old"))
        val actions = plan(old, mapOf("usr" to "", "usr/source" to "", "usr/source/child" to "hash"),
            mapOf("usr/item" to "usr/source"))
        AssetMigration(root) { "new retained source".byteInputStream() }.apply(actions)
        assertEquals("new retained source", File(root, "usr/source/child").readText())
        assertTrue(Files.isSymbolicLink(File(root, "usr/item").toPath()))
    }

    @Test fun invalidPathsAreRejectedBeforeAnyActionRuns() {
        val root = temp.newFolder("root")
        val target = write(root, "usr/item", "keep")
        assertTrue(runCatching {
            migration(root).apply(listOf(FileAction.DeleteFile("usr/item"), FileAction.DeleteFile("../outside")))
        }.isFailure)
        assertEquals("keep", target.readText())
    }

    @Test fun currentPackagedAssetsMigrateAndTheNextSyncHasNoActions() {
        val assets = RuntimeEnvironment.getApplication().assets
        val descriptor = assets.open(BuildConfig.DATA_DESCRIPTOR_NAME).bufferedReader().use {
            Json.decodeFromString<DataDescriptor>(it.readText())
        }
        val hierarchy = DataHierarchy().apply { install(descriptor, FileSource.Main) }
        assertTrue("Bundled presets must remain APK-only assets", descriptor.files.keys.none {
            it == "bundled" || it.startsWith("bundled/")
        })
        val root = temp.newFolder("packaged")
        AssetMigration(root) { assets.open(it) }.apply(DataHierarchy.diff(DataDescriptor("old", emptyMap()), hierarchy))
        descriptor.files.forEach { (path, hash) ->
            if (hash.isBlank()) assertTrue(path, File(root, path).isDirectory)
            else assertTrue(path, File(root, path).isFile)
        }
        descriptor.symlinks.forEach { (path, source) ->
            assertTrue(Files.isSymbolicLink(File(root, path).toPath()))
            assertEquals(File(root, source).canonicalFile, File(root, path).canonicalFile)
        }
        assertTrue(DataHierarchy.diff(hierarchy.downToDataDescriptor(), hierarchy).isEmpty())
    }

    @Test fun anUndeclaredSymlinkParentIsRejectedInsteadOfWritingThroughIt() {
        val root = temp.newFolder("root")
        val outside = temp.newFolder("outside")
        val victim = write(outside, "child", "keep")
        org.fcitx.fcitx5.android.testing.FilesystemFixtures.symbolicLink(File(root, "link").toPath(), outside.toPath())
        assertTrue(runCatching {
            migration(root).apply(listOf(FileAction.CreateFile("link/child", FileSource.Main)))
        }.isFailure)
        assertEquals("keep", victim.readText())
    }
}

/** Real host symlinks with Android lstat's no-follow behavior, including missing entries. */
@Implements(Os::class)
class AssetFileOs {
    companion object {
        @JvmStatic @Implementation fun lstat(path: String): StructStat {
            val file = File(path)
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw ErrnoException("lstat", OsConstants.ENOENT)
            val mode = when {
                Files.isSymbolicLink(file.toPath()) -> OsConstants.S_IFLNK
                file.isDirectory -> OsConstants.S_IFDIR
                else -> OsConstants.S_IFREG
            }
            return StructStat(0, 0, mode, 1, 0, 0, 0, file.length(), 0, 0, 0, 0, 0)
        }
        @JvmStatic @Implementation fun symlink(oldPath: String, newPath: String) {
            org.fcitx.fcitx5.android.testing.FilesystemFixtures.symbolicLink(File(newPath).toPath(), File(oldPath).toPath())
        }
    }
}
