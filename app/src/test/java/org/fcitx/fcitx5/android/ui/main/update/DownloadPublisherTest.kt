package org.fcitx.fcitx5.android.ui.main.update

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.util.UUID

internal class DownloadMediaFixture : ContentProvider() {
    data class Item(val id: Long, val values: ContentValues, val file: File)
    val rows = linkedMapOf<Long, Item>()
    var insertions = 0
    var crashAfterPublish = false
    var suffixFinalName = false
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = (++insertions).toLong()
        val copy = ContentValues(values!!).apply { put("owner_package_name", context!!.packageName) }
        rows[id] = Item(id, copy, File(context!!.cacheDir, "media-fixture-${UUID.randomUUID()}").apply { createNewFile() })
        return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
        val columns = projection!!.toList()
        val id = uri.lastPathSegment?.toLongOrNull()
        val selected = if (id != null) listOfNotNull(rows[id]) else rows.values.filter {
            args == null || (it.values.getAsString("_display_name") == args[0] && it.values.getAsInteger("is_pending") == 1)
        }
        return MatrixCursor(columns.toTypedArray()).apply {
            selected.forEach { row -> addRow(columns.map {
                when (it) { "_id" -> row.id; "_size" -> row.file.length(); else -> row.values[it] }
            }) }
        }
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        val row = rows[ContentUris.parseId(uri)] ?: return 0
        row.values.putAll(values!!)
        if (suffixFinalName && values.getAsInteger("is_pending") == 0)
            row.values.put("_display_name", "fixture (1).bin")
        if (crashAfterPublish && values.getAsInteger("is_pending") == 0) {
            crashAfterPublish = false; throw IOException("injected interruption after MediaStore commit")
        }
        return 1
    }
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        val row = rows.remove(ContentUris.parseId(uri)) ?: return 0
        row.file.delete(); return 1
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(rows[ContentUris.parseId(uri)]!!.file, ParcelFileDescriptor.parseMode(mode))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class DownloadPublisherTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var media: DownloadMediaFixture
    @Before fun setup() {
        media = DownloadMediaFixture().also { it.attachInfo(context, ProviderInfo().apply { authority = "media" }) }
        ShadowContentResolver.registerProviderInternal("media", media)
    }
    private fun verified(size: Int = 100): DownloadFiles {
        val files = DownloadFiles(File(context.noBackupFilesDir, "publisher-fixture"), UUID.randomUUID())
        files.prepare(); files.part.writeBytes(ByteArray(size) { 65 })
        files.write(DownloadCheckpoint(total = size.toLong(), verified = true, actualSha256 = DownloadFiles.digest(files.part)))
        return files
    }
    private val asset get() = ReleaseAsset("fixture.bin", "https://fixture.invalid/f", 100, null)

    @Test fun onlyVerifiedCompleteBytesBecomePublicAndCleanupKeepsThem() {
        val files = verified()
        val uri = DownloadPublisher(context).publish(files, asset) { }
        val row = media.rows.getValue(ContentUris.parseId(uri))
        assertEquals(0, row.values.getAsInteger("is_pending"))
        assertEquals(files.read().actualSha256, DownloadFiles.digest(row.file))
        DownloadPublisher(context).removePending(files); files.removePrivateContent()
        assertTrue(media.rows.containsKey(ContentUris.parseId(uri))); assertTrue(row.file.isFile)
    }
    @Test fun interruptionAfterPublicationReusesTheSameRowEvenWhenMediaStoreRenamesIt() {
        val files = verified(); media.crashAfterPublish = true; media.suffixFinalName = true
        try { DownloadPublisher(context).publish(files, asset) { }; fail("Must interrupt") } catch (_: IOException) { }
        assertNotNull(files.read().pendingUri)
        val reopened = DownloadFiles(files.directory.parentFile, UUID.fromString(files.directory.name))
        val uri = DownloadPublisher(context).publish(reopened, asset) { }
        assertEquals(1, media.insertions); assertEquals(uri.toString(), reopened.read().publishedUri)
    }
    @Test fun aPendingRowInsertedBeforeCheckpointWriteIsRecoveredByWorkIdentity() {
        val files = verified()
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put("_display_name", files.pendingName); put("relative_path", "Download/"); put("is_pending", 1)
        })
        DownloadPublisher(context).publish(files, asset) { }
        assertEquals(1, media.insertions)
    }
    @Test fun foreignPendingAndPublishedRowsAreNeverDeletedOrOverwritten() {
        val files = verified()
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put("_display_name", files.pendingName); put("relative_path", "Download/"); put("is_pending", 1)
        })!!
        val foreign = media.rows.getValue(ContentUris.parseId(uri))
        foreign.values.put("owner_package_name", "other.package"); foreign.file.writeText("foreign")
        files.write(files.read().copy(pendingUri = uri.toString()))
        val own = DownloadPublisher(context).publish(files, asset) { }
        assertNotEquals(uri, own)
        DownloadPublisher(context).removePending(files)
        assertEquals("foreign", foreign.file.readText()); assertEquals(2, media.rows.size)
    }
    @Test fun cancelledPublicationDeletesOnlyItsPendingRow() {
        val files = verified(200_000)
        var checks = 0
        try {
            DownloadPublisher(context).publish(files, asset.copy(sizeBytes = 200_000)) {
                if (++checks == 3) throw kotlinx.coroutines.CancellationException("fixture")
            }
            fail("Must cancel")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(1, media.rows.size)
        assertEquals(1, media.rows.values.single().values.getAsInteger("is_pending"))
        DownloadPublisher(context).removePending(files)
        assertTrue(media.rows.isEmpty())
    }
    @Test fun unverifiedFilesCannotCreateAMediaRow() {
        val files = verified(); files.write(files.read().copy(verified = false))
        try { DownloadPublisher(context).publish(files, asset) { }; fail("Must reject") } catch (_: IllegalArgumentException) { }
        assertTrue(media.rows.isEmpty())
    }
}
