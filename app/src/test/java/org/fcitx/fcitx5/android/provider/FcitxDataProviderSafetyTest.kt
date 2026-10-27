/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.provider

import android.app.Application
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract.Document
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
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
import java.io.FileNotFoundException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [SymlinkAwareOs::class])
class FcitxDataProviderSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun provider(root: File): FcitxDataProvider {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getExternalFilesDir(type: String?) = root
        }
        return FcitxDataProvider().apply {
            attachInfo(context, ProviderInfo().apply {
                authority = "review.documents"
                exported = true
                grantUriPermissions = true
                readPermission = "android.permission.MANAGE_DOCUMENTS"
                writePermission = readPermission
            })
        }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Unsafe path was accepted") } catch (_: FileNotFoundException) { }
    }

    @Test fun normalDocumentsWorkAndNamesCannotEscapeTheRoot() {
        val root = temporary.newFolder("files")
        val provider = provider(root)
        val dir = provider.createDocument("files", Document.MIME_TYPE_DIR, "folder")
        val child = provider.createDocument(dir, "text/plain", "note.txt")
        assertEquals("files/folder/note.txt", child)
        provider.openDocument(child, "r", null).close()
        assertTrue(provider.isChildDocument("files/folder", child))
        assertFalse(provider.isChildDocument("files/folder", "files/folder-copy"))
        listOf("../outside", "/outside", "a/b", "a\\b", ".", "..").forEach { name ->
            rejected { provider.createDocument(dir, "text/plain", name) }
            rejected { provider.renameDocument(child, name) }
        }
        rejected { provider.deleteDocument("files") }
        rejected { provider.openDocument("files/../../outside", "r", null) }
        provider.deleteDocument(dir)
        assertFalse(File(root, "folder").exists())
    }

    @Test fun symlinkEscapesAreNeitherListedNorOpenedOrCopied() {
        val root = temporary.newFolder("files")
        val outside = temporary.newFolder("private")
        val secret = File(outside, "secret.txt").apply { writeText("private data") }
        val folder = File(root, "folder").apply { mkdir() }
        Files.createSymbolicLink(File(folder, "link").toPath(), outside.toPath())
        val provider = provider(root)
        rejected { provider.openDocument("files/folder/link/secret.txt", "r", null) }
        rejected { provider.copyDocument("files/folder", "files") }
        provider.queryChildDocuments("files/folder", null, sortOrder = null).use { assertEquals(0, it.count) }
        provider.deleteDocument("files/folder")
        assertEquals("private data", secret.readText())
    }

    @Test fun failedMovesAndRenamesDoNotReturnInventedDocumentIds() {
        val root = temporary.newFolder("files")
        val provider = provider(root)
        rejected { provider.renameDocument("files/missing", "renamed") }
        rejected { provider.moveDocument("files/missing", "files", "files") }
        val dir = provider.createDocument("files", Document.MIME_TYPE_DIR, "folder")
        rejected { provider.copyDocument(dir, dir) }
        rejected { provider.moveDocument(dir, "files", dir) }
    }
}

// Robolectric's lstat delegates to stat and follows links. Model Android's actual lstat
// here so FileUtil.removeFile's no-follow deletion is exercised with real host symlinks.
@Implements(Os::class)
class SymlinkAwareOs {
    companion object {
        @JvmStatic @Implementation
        fun lstat(path: String): StructStat {
            val file = File(path)
            val mode = when {
                Files.isSymbolicLink(file.toPath()) -> OsConstants.S_IFLNK
                file.isDirectory -> OsConstants.S_IFDIR
                else -> OsConstants.S_IFREG
            }
            return StructStat(0, 0, mode, 1, 0, 0, 0, file.length(), 0, 0, 0, 0, 0)
        }
    }
}
