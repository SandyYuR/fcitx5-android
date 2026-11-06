/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.update

import android.app.Application
import android.app.DownloadManager
import android.database.MatrixCursor
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.MediaStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.Robolectric
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, shadows = [UpdateManagerFixture::class])
class UpdateDownloadStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var media: DownloadMediaFixture
    private lateinit var installed: File
    private var originalSource: String? = null
    @Before fun prepare() {
        File(context.noBackupFilesDir, "update-downloads.json").delete()
        media = DownloadMediaFixture().also { it.attachInfo(context, ProviderInfo().apply { authority = "media" }) }
        ShadowContentResolver.registerProviderInternal("media", media)
        UpdateManagerFixture.rows.clear()
        originalSource = context.applicationInfo.sourceDir
        installed = File(context.cacheDir, "installed-test.apk").apply { writeText("installed package bytes") }
        context.applicationInfo.sourceDir = installed.path
    }
    @After fun cleanup() {
        context.applicationInfo.sourceDir = originalSource
        media.rows.values.forEach { it.file.delete() }
        installed.delete()
        File(context.noBackupFilesDir, "update-downloads.json").delete()
    }
    private val next get() = ReleaseAsset("direct.apk", "https://example.invalid/new/direct.apk", 8, "ab".repeat(32))

    private fun record(bytes: String, name: String = "direct (1).apk", foreign: Boolean = false): Uri {
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put("_display_name", name); put("relative_path", "Download/"); put("is_pending", 0)
        })!!
        val row = media.rows.getValue(ContentUris.parseId(uri))
        row.file.writeText(bytes)
        if (foreign) row.values.put("owner_package_name", "unrelated.application")
        val hash = DownloadFiles.digest(row.file)
        UpdateDownloadStore.remember(context, UpdateDownloadStore.Record(
            ReleaseAsset("direct.apk", "https://example.invalid/old/direct.apk", row.file.length(), hash),
            uri = uri.toString(), sha256 = hash))
        return uri
    }

    @Test fun nextDownloadRemovesOnlyTheOwnedPackageWhoseBytesAreAlreadyInstalled() {
        val old = record(installed.readText())
        val notInstalled = record("installation cancelled", "direct (2).apk")
        val foreign = record(installed.readText(), "direct (3).apk", foreign = true)
        assertNull(UpdateDownloadStore.beforeDownload(context, next))
        assertFalse(media.rows.containsKey(ContentUris.parseId(old)))
        assertTrue(media.rows.containsKey(ContentUris.parseId(notInstalled)))
        assertTrue(media.rows.containsKey(ContentUris.parseId(foreign)))
    }

    @Test fun renamedMatchingPackageIsReusedByUriAndNotRemoved() {
        val uri = record("ready new package")
        val file = media.rows.getValue(ContentUris.parseId(uri)).file
        val asset = next.copy(sizeBytes = file.length(), sha256 = DownloadFiles.digest(file))
        val match = UpdateDownloadStore.beforeDownload(context, asset)
        assertEquals(uri, match?.uri)
        assertTrue(file.exists())
    }

    @Test fun changingARecordedFileDoesNotMakeItEligibleForDeletion() {
        val uri = record("downloaded but not installed")
        val file = media.rows.getValue(ContentUris.parseId(uri)).file
        file.writeText(installed.readText())
        UpdateDownloadStore.beforeDownload(context, next)
        assertTrue(file.exists())
    }

    @Test fun unknownOrUnavailableInstalledPackagePreservesAllDownloads() {
        val uri = record("installation cancelled")
        context.applicationInfo.sourceDir = null
        UpdateDownloadStore.beforeDownload(context, next)
        assertTrue(media.rows.containsKey(ContentUris.parseId(uri)))
    }

    @Test fun duplicateNamesSupportBothAndroidSpacingConventionsAndKeepExactExtension() {
        for (name in listOf("direct.apk", "direct(1).apk", "direct (2).apk")) assertTrue(downloadNameMatchesAsset(name, "direct.apk"))
        for (name in listOf("directX.apk", "direct (x).apk", "direct (1).apk.zip", "indirect.apk")) assertFalse(downloadNameMatchesAsset(name, "direct.apk"))
        val first = UpdateDownloadStore.destinationName("direct.apk")
        assertNotEquals(first, UpdateDownloadStore.destinationName("direct.apk"))
        assertTrue(first.startsWith("direct-") && first.endsWith(".apk"))
    }

    @Test fun preLedgerDownloadManagerFilesAreRemovedOnlyWhenTheirExactBytesAreInstalled() {
        val uri = record(installed.readText())
        File(context.noBackupFilesDir, "update-downloads.json").delete()
        UpdateManagerFixture.rows[41] = UpdateManagerFixture.Row("direct.apk", uri,
            media.rows.getValue(ContentUris.parseId(uri)).file, "https://example.invalid/previous.apk")
        UpdateDownloadStore.beforeDownload(context, next)
        assertFalse(UpdateManagerFixture.rows.containsKey(41))
        assertFalse(media.rows.containsKey(ContentUris.parseId(uri)))
    }

    @Test fun aDownloadManagersActualUriSurvivesGeneratedAndSuffixedFilenames() {
        val uri = record("new verified package", "direct-67a99f12.apk")
        val file = media.rows.getValue(ContentUris.parseId(uri)).file
        val asset = next.copy(sizeBytes = file.length(), sha256 = DownloadFiles.digest(file))
        File(context.noBackupFilesDir, "update-downloads.json").delete()
        UpdateManagerFixture.rows[55] = UpdateManagerFixture.Row(asset.name, uri, file, asset.browserDownloadUrl)
        UpdateDownloadStore.remember(context, UpdateDownloadStore.Record(asset, downloadId = 55))
        assertEquals(uri, UpdateDownloadStore.find(context, asset)?.uri)
        val activity = Robolectric.buildActivity(UpdateCheckActivity::class.java).get()
        ReflectionHelpers.setField(activity, "downloadManager", context.getSystemService(DownloadManager::class.java))
        val hit: Any? = ReflectionHelpers.callInstanceMethod(activity, "findLatestSuccessfulDownload",
            ReflectionHelpers.ClassParameter.from(ReleaseAsset::class.java, asset),
            ReflectionHelpers.ClassParameter.from(Long::class.javaObjectType, 55L))
        assertNotNull(hit)
        assertEquals(uri, ReflectionHelpers.getField<Uri>(hit, "uri"))
    }

    @Test fun absentServerDigestRequiresTheSameRecordedRelease() {
        val uri = record("new verified package")
        val file = media.rows.getValue(ContentUris.parseId(uri)).file
        val asset = next.copy(sizeBytes = file.length(), sha256 = null)
        val first = ReleaseInfo("nightly", "Nightly", "", listOf(asset), publishedAt = 1)
        UpdatePrefs.saveReleaseCache(context, first)
        UpdateDownloadStore.remember(context, UpdateDownloadStore.Record(asset, uri = uri.toString(),
            sha256 = DownloadFiles.digest(file), release = UpdateDownloadStore.releaseIdentity(context, asset)))
        assertEquals(uri, UpdateDownloadStore.find(context, asset)?.uri)
        UpdatePrefs.saveReleaseCache(context, first.copy(publishedAt = 2))
        assertNull(UpdateDownloadStore.find(context, asset))
        assertNull(UpdateDownloadStore.beforeDownload(context, asset))
        assertTrue(file.exists())
    }
}

@Implements(DownloadManager::class)
class UpdateManagerFixture {
    data class Row(val title: String, val uri: Uri, val file: File, val url: String)
    companion object { val rows = linkedMapOf<Long, Row>() }
    @Implementation fun query(query: DownloadManager.Query): android.database.Cursor {
        val ids: LongArray? = ReflectionHelpers.getField(query, "mIds")
        val names = arrayOf(DownloadManager.COLUMN_ID, DownloadManager.COLUMN_TITLE, DownloadManager.COLUMN_STATUS,
            DownloadManager.COLUMN_TOTAL_SIZE_BYTES, DownloadManager.COLUMN_URI, DownloadManager.COLUMN_LOCAL_URI,
            DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
        return MatrixCursor(names).apply {
            rows.filterKeys { ids == null || it in ids }.forEach { (id, row) ->
                addRow(arrayOf<Any?>(id, row.title, DownloadManager.STATUS_SUCCESSFUL, row.file.length(), row.url,
                    row.uri.toString(), row.file.lastModified()))
            }
        }
    }
    @Implementation fun getUriForDownloadedFile(id: Long): Uri? = rows[id]?.uri
    @Implementation fun openDownloadedFile(id: Long): android.os.ParcelFileDescriptor =
        android.os.ParcelFileDescriptor.open(rows.getValue(id).file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    @Implementation fun remove(vararg ids: Long): Int {
        var count = 0
        for (id in ids) rows.remove(id)?.let {
            RuntimeEnvironment.getApplication().contentResolver.delete(it.uri, null, null)
            count++
        }
        return count
    }
}
