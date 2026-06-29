/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import timber.log.Timber

suspend fun ClipboardEntry.loadThumbnailBitmap(context: Context): Bitmap? {
    if (!isUriEntry() || !type.startsWith("image/")) return null
    val originalUri = runCatching { Uri.parse(text) }.getOrNull() ?: run {
        Timber.w("loadThumbnailBitmap: failed to parse URI from entry.text")
        return null
    }
    Timber.d("loadThumbnailBitmap: attempting to load from URI: $originalUri")

    var uri = originalUri
    if (isExternalStorageProviderTreeUri(originalUri)) {
        val filePath = extractFilePathFromTreeUri(originalUri)
        if (filePath != null) {
            uri = Uri.parse("file://$filePath")
            Timber.d("loadThumbnailBitmap: converted tree URI to file path: $uri")
        }
    }

    return withContext(Dispatchers.IO) {
        repeat(3) { attempt ->
            val bitmap = runCatching {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    Timber.w("loadThumbnailBitmap: bounds invalid (${bounds.outWidth}x${bounds.outHeight})")
                    return@runCatching null
                }
                val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 192, 192)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                resolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }.getOrNull()
            if (bitmap != null) {
                Timber.d("loadThumbnailBitmap: successfully loaded bitmap ${bitmap.width}x${bitmap.height}")
                return@withContext bitmap
            }
            Timber.w("loadThumbnailBitmap: attempt ${attempt + 1} failed, retrying...")
            if (attempt < 2) {
                kotlinx.coroutines.delay(100L shl attempt)
            }
        }
        Timber.e("loadThumbnailBitmap: all 3 attempts failed for URI: $uri")
        null
    }
}

private fun isExternalStorageProviderTreeUri(uri: Uri): Boolean {
    return uri.authority == "com.android.externalstorage.documents" &&
            uri.path?.startsWith("/tree/") == true
}

private fun extractFilePathFromTreeUri(treeUri: Uri): String? {
    val documentId = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (e: Exception) {
        Timber.w("Failed to get document ID from tree URI: $treeUri")
        return null
    }
    val parts = documentId.split(":", limit = 2)
    if (parts.size != 2) return null
    val (volume, relativePath) = parts[0] to parts[1]
    return when {
        volume.equals("primary", ignoreCase = true) -> {
            if (relativePath.isBlank()) {
                "/storage/emulated/0"
            } else {
                "/storage/emulated/0/$relativePath"
            }
        }
        else -> "/storage/$volume/$relativePath"
    }
}

private fun calculateInSampleSize(
    width: Int,
    height: Int,
    reqWidth: Int,
    reqHeight: Int
): Int {
    var sampleSize = 1
    var currentWidth = width
    var currentHeight = height
    while (currentHeight / 2 >= reqHeight && currentWidth / 2 >= reqWidth) {
        currentHeight /= 2
        currentWidth /= 2
        sampleSize *= 2
    }
    return sampleSize
}
