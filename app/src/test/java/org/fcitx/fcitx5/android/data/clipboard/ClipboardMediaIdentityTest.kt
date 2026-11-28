/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import android.net.Uri
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import org.robolectric.shadows.ShadowContentResolver
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.nio.file.LinkOption

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, ClipboardFileProviderTestShadow::class],
    instrumentedPackages = ["androidx.core.content.FileProvider"])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClipboardMediaIdentityTest {
    @Test fun aMutableSourceRequiresMatchingBytesWhileUnchangedCopiesReuseTheSnapshot() = runBlocking {
        ClipboardManager.init(appContext)
        val folder = File(appContext.cacheDir, "media-identity-${UUID.randomUUID()}")
        folder.mkdirs()
        val source = File(folder, "image.png").apply { writeText("image-A") }
        val authority = "test.media.identity"
        val provider = MutableMediaProvider(source).apply {
            attachInfo(appContext, ProviderInfo().also { it.authority = authority })
        }
        val stagedAuthority = "${appContext.packageName}.fileprovider"
        val snapshots = ClipboardSnapshotProvider().apply {
            attachInfo(appContext, ProviderInfo().also { it.authority = stagedAuthority })
        }
        ShadowContentResolver.registerProviderInternal(authority, provider)
        ShadowContentResolver.registerProviderInternal(stagedAuthority, snapshots)
        try {
            val uri = "content://$authority/image.png"
            val first = ClipboardManager.importLocalEntry(uri, "image/png", 100L)!!
            val stagedPrefix = "content://$stagedAuthority/clipboard_shared/"
            assertTrue("Mutable media must be staged under its real clipboard root", first.text.startsWith(stagedPrefix))
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(appContext, "test.wrong.authority", source)
            }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(appContext, stagedAuthority, source)
            }
            val firstBytes = appContext.contentResolver.openInputStream(Uri.parse(first.text))!!.use { it.readBytes() }
            assertArrayEquals("image-A".toByteArray(), firstBytes)
            source.writeText("image-B")
            val changed = ClipboardManager.importLocalEntry(uri, "image/png", 200L)!!
            assertNotEquals("Changed source bytes must create a new row", first.id, changed.id)
            assertNotEquals(first.text, changed.text)
            assertArrayEquals(firstBytes, appContext.contentResolver.openInputStream(Uri.parse(first.text))!!.use { it.readBytes() })
            assertEquals("image-B", appContext.contentResolver.openInputStream(Uri.parse(changed.text))!!.bufferedReader().use { it.readText() })
            val same = ClipboardManager.importLocalEntry(uri, "image/png", 300L)!!
            assertEquals(changed.id, same.id)
            val alias = "content://$authority/alias.png"
            val sameAlias = ClipboardManager.importLocalEntry(alias, "image/png", 400L)!!
            assertEquals(changed.id, sameAlias.id)
            assertEquals(changed.id, ClipboardManager.importLocalEntry(changed.text, "image/png", 500L)!!.id)
        } finally {
            ShadowContentResolver.registerProviderInternal(authority, null)
            ShadowContentResolver.registerProviderInternal(stagedAuthority, null)
            ClipboardManager.closeDatabase()
            source.delete()
            folder.delete()
        }
    }

    private class ClipboardSnapshotProvider : ContentProvider() {
        private fun snapshot(uri: Uri): File {
            require(uri.scheme == "content" && uri.pathSegments.size == 2 &&
                uri.pathSegments[0] == "clipboard_shared")
            val name = uri.pathSegments[1]
            require(name.isNotEmpty() && name != "." && name != ".." &&
                '/' !in name && '\\' !in name)
            return ClipboardFileProviderTestShadow.requireSnapshot(
                context!!, uri.authority.orEmpty(), File(context!!.cacheDir, "clipboard_shared/$name"))
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = snapshot(uri).let { "image/png" }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?) =
            snapshot(uri).let { file ->
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                    addRow(arrayOf<Any>(file.name, file.length()))
                }
            }
        override fun canonicalize(uri: Uri): Uri = uri.also { snapshot(it) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            require(mode == "r")
            return ParcelFileDescriptor.open(snapshot(uri), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only fixture")
    }

    private class MutableMediaProvider(private val file: File) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any>("image.png", file.length()))
            }
        override fun canonicalize(uri: Uri) = Uri.parse("content://test.media.identity/image.png")
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only fixture")
    }

    @Test fun failedTreeLeafStagingRetainsTheOriginalDocumentIdentity() = runBlocking {
        ClipboardManager.init(appContext)
        val marker = UUID.randomUUID().toString()
        val uri = "content://com.android.externalstorage.documents/tree/primary%3A$marker/document/primary%3A$marker%2Fleaf.png"
        try {
            assertEquals("/storage/emulated/0/$marker/leaf.png", ClipboardManager.extractFilePathFromTreeUri(Uri.parse(uri)))
            val imported = ClipboardManager.importLocalEntry(uri, "image/png", 600L)!!
            assertEquals(uri, imported.text)
        } finally {
            ClipboardManager.closeDatabase()
        }
    }
}

// AndroidX's FileProvider root comparison assumes Android '/' separators. This local
// shadow adapts that boundary for the host JVM while retaining the real cache files.
@Implements(value = FileProvider::class, isInAndroidSdk = false)
class ClipboardFileProviderTestShadow {
    companion object {
        fun requireSnapshot(context: Context, authority: String, file: File): File {
            require(authority == "${context.packageName}.fileprovider")
            val root = File(context.cacheDir, "clipboard_shared").toPath().toRealPath()
            val path = file.toPath()
            val real = path.toRealPath()
            require(real.parent == root && !Files.isSymbolicLink(path) &&
                Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS))
            return real.toFile()
        }
        @JvmStatic @Implementation
        fun getUriForFile(context: Context, authority: String, file: File): Uri {
            val snapshot = requireSnapshot(context, authority, file)
            return Uri.Builder().scheme("content").authority(authority)
                .appendPath("clipboard_shared").appendPath(snapshot.name).build()
        }
    }
}
