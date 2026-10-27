/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.update

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.IOException

/** Pending rows are private; publish only after full size/checksum validation. */
@RequiresApi(29)
internal class DownloadPublisher(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val collection get() = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    private data class Row(val uri: Uri, val name: String, val pending: Boolean, val size: Long)
    private val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING,
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.SIZE)

    private fun ownedRows(uri: Uri, selection: String? = null, args: Array<String>? = null): List<Row> {
        if (uri.scheme != "content" || uri.authority != "media" ||
            uri.pathSegments.getOrNull(1) != "downloads") return emptyList()
        return resolver.query(uri, columns, selection, args, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val owner = cursor.getString(4)
                    val path = cursor.getString(2).orEmpty().trimEnd('/')
                    if (owner != context.packageName || path != Environment.DIRECTORY_DOWNLOADS) continue
                    add(Row(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1),
                        cursor.getInt(3) == 1, cursor.getLong(5)))
                }
            }
        }.orEmpty()
    }
    private fun pending(files: DownloadFiles) = ownedRows(collection,
        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 1",
        arrayOf(files.pendingName))

    private fun matches(row: Row, files: DownloadFiles, asset: ReleaseAsset, state: DownloadCheckpoint): Boolean {
        // MediaStore may add a suffix when another file already has the desired name.
        if (row.pending || (row.size > 0 && row.size != state.total) || state.actualSha256 == null) return false
        return resolver.openInputStream(row.uri)?.use {
            DownloadFiles.digest(it, state.total!!) == state.actualSha256
        } == true
    }

    fun publish(files: DownloadFiles, asset: ReleaseAsset, active: () -> Unit): Uri {
        require(asset.name.length in 1..255 && asset.name !in listOf(".", "..") &&
            asset.name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Invalid download filename" }
        var state = files.read()
        require(state.verified && state.actualSha256 != null) { "Download has not been verified" }
        for (saved in listOfNotNull(state.publishedUri, state.pendingUri).distinct()) {
            val row = ownedRows(Uri.parse(saved)).singleOrNull() ?: continue
            if (matches(row, files, asset, state)) {
                files.write(state.copy(pendingUri = null, publishedUri = row.uri.toString()))
                return row.uri
            }
        }
        val savedPending = state.pendingUri?.let { ownedRows(Uri.parse(it)).singleOrNull() }
            ?.takeIf { it.pending && it.name == files.pendingName }
        val found = pending(files)
        val uri = savedPending?.uri ?: found.firstOrNull()?.uri ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, files.pendingName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("Cannot create pending download")
        // Reclaim only duplicate pending rows owned by this exact work UUID.
        found.filter { it.uri != uri }.forEach { resolver.delete(it.uri, null, null) }
        state = state.copy(pendingUri = uri.toString(), publishedUri = null)
        files.write(state) // before copying or exposing the row
        active()
        files.requireSpace(files.part.length())
        resolver.openFileDescriptor(uri, "rwt")?.let { android.os.ParcelFileDescriptor.AutoCloseOutputStream(it) }?.use { out ->
            files.part.inputStream().use { input ->
                val bytes = ByteArray(32 * 1024)
                while (true) {
                    active()
                    val n = input.read(bytes); if (n < 0) break
                    out.write(bytes, 0, n)
                }
                active(); out.flush(); out.fd.sync()
            }
        } ?: throw IOException("Cannot open pending download")
        active()
        if (resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, asset.name)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) != 1) throw IOException("Cannot publish completed download")
        files.write(state.copy(pendingUri = null, publishedUri = uri.toString()))
        return uri
    }

    fun removePending(files: DownloadFiles) {
        val saved = runCatching { files.read().pendingUri }.getOrNull()
            ?.let { ownedRows(Uri.parse(it)) }.orEmpty()
        (saved + pending(files)).filter { it.pending && it.name == files.pendingName }.distinctBy { it.uri }
            .forEach { resolver.delete(it.uri, null, null) }
    }
}
