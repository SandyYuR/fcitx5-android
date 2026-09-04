/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.util.Locale

data class ClipboardSharedContent(
    val uri: Uri,
    val mimeType: String
)

data class ClipboardSourceDeletionTarget(
    val rawUri: String,
    val rootUri: String
)

object ClipboardUriStore {
    private const val CACHE_DIR = "clipboard_shared"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val OPEN_RETRY_COUNT = 5
    private const val OPEN_RETRY_DELAY_MS = 120L

    /** How many staged clipboard files to keep. */
    private const val MAX_CACHED_FILES = 32

    fun String.toClipboardUriOrNull(): Uri? {
        return if (startsWith("content://") || startsWith("file://")) Uri.parse(this) else null
    }

    suspend fun stageForCommit(context: Context, raw: String): ClipboardSharedContent? {
        val uri = raw.toClipboardUriOrNull() ?: return null
        return stageForCommit(context, uri)
    }

    /** Staging may retry opening the source, so this is suspend; call it from Dispatchers.IO. */
    suspend fun stageForCommit(context: Context, uri: Uri): ClipboardSharedContent? {
        val mimeType = resolveMimeType(context, uri)
        if (uri.authority == context.packageName + FILE_PROVIDER_SUFFIX) {
            return ClipboardSharedContent(uri, mimeType)
        }
        val displayName = resolveDisplayName(context, uri, mimeType)
        // Prefix a monotonic id: naming the cache file after the source file made two
        // different files with the same name (Download/IMG_001.jpg and
        // Pictures/IMG_001.jpg) collide, so the second copy silently overwrote the first
        // and both history entries pointed at the same content.
        val targetFile = File(cacheRoot(context), uniqueStagedName(displayName))
        return try {
            openInputStreamWithRetry(context, uri)?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            pruneCache(targetFile)
            ClipboardSharedContent(
                uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + FILE_PROVIDER_SUFFIX,
                    targetFile
                ),
                mimeType = mimeType
            )
        } catch (error: Exception) {
            Timber.w(error, "Failed to stage clipboard URI for commit: $uri")
            null
        }
    }

    suspend fun normalizeClipboardText(context: Context, raw: String): String {
        return stageForCommit(context, raw)?.uri?.toString() ?: raw
    }

    fun originalClipboardTextOrEmpty(raw: String, normalized: String): String {
        return if (raw != normalized && raw.toClipboardUriOrNull() != null) raw else ""
    }

    fun deleteClipboardSourceFile(context: Context, target: ClipboardSourceDeletionTarget): Boolean {
        if (!isClipboardSourceWithinRoot(context, target.rawUri, target.rootUri)) return false
        val uri = target.rawUri.toClipboardUriOrNull() ?: return false
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            ContentResolver.SCHEME_CONTENT -> {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    .recoverCatching { context.contentResolver.delete(uri, null, null) > 0 }
                    .getOrDefault(false)
            }

            ContentResolver.SCHEME_FILE -> {
                uri.path?.let { File(it).delete() } ?: false
            }

            else -> false
        }
    }

    fun isClipboardSourceWithinRoot(context: Context, raw: String, root: String): Boolean {
        val uri = raw.toClipboardUriOrNull() ?: return false
        val rootUri = root.toClipboardUriOrNull() ?: return false
        if (!uri.scheme.equals(rootUri.scheme, ignoreCase = true)) return false
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            ContentResolver.SCHEME_CONTENT -> isContentUriWithinRoot(context, uri, rootUri)
            ContentResolver.SCHEME_FILE -> isFileUriWithinRoot(uri, rootUri)
            else -> false
        }
    }

    private fun cacheRoot(context: Context): File {
        return File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
    }

    /**
     * Unique name for a staged copy: "<nanoTime>-<original name>". Keeping the original
     * name as a suffix means the file name shown to the receiving app is still meaningful.
     */
    internal fun uniqueStagedName(displayName: String): String =
        "${System.nanoTime()}-$displayName"

    /**
     * Set when [pruneCache] deletes staged files, so the clipboard database can drop the
     * entries that referenced them. Wired up by ClipboardManager; kept as a callback here to
     * avoid a dependency from utils into the data layer.
     */
    @Volatile
    var onStagedFilesEvicted: ((List<File>) -> Unit)? = null

    private fun pruneCache(latest: File) {
        val files = latest.parentFile?.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        val evicted = files.drop(MAX_CACHED_FILES).filter { it.delete() }
        if (evicted.isNotEmpty()) {
            // Deleting the file alone left history entries pointing at a dead FileProvider
            // URI: the thumbnail loader retried and gave up, and pasting produced nothing.
            onStagedFilesEvicted?.invoke(evicted)
        }
    }

    private fun openInputStream(context: Context, uri: Uri) = when (uri.scheme?.lowercase(Locale.ROOT)) {
        ContentResolver.SCHEME_CONTENT -> runCatching {
            context.contentResolver.openInputStream(uri)
        }.getOrNull() ?: resolveExternalStorageFile(uri)?.takeIf(File::exists)?.let(::FileInputStream)

        ContentResolver.SCHEME_FILE -> uri.path?.let { FileInputStream(it) }
        else -> null
    }

    private suspend fun openInputStreamWithRetry(context: Context, uri: Uri) =
        (0 until OPEN_RETRY_COUNT).firstNotNullOfOrNull { attempt ->
            val stream = runCatching { openInputStream(context, uri) }.getOrNull()
            if (stream == null && attempt < OPEN_RETRY_COUNT - 1) {
                // delay, not Thread.sleep: with the caller on Dispatchers.IO, sleeping blocks
                // a shared pool thread for up to 5 x 120ms per failed open.
                delay(OPEN_RETRY_DELAY_MS)
            }
            stream
        }

    private fun resolveDisplayName(context: Context, uri: Uri, mimeType: String): String {
        val sourceName = when (uri.scheme?.lowercase(Locale.ROOT)) {
            ContentResolver.SCHEME_CONTENT -> runCatching {
                context.contentResolver.queryFileName(uri)
            }.getOrNull() ?: resolveExternalStorageFile(uri)?.name

            ContentResolver.SCHEME_FILE -> uri.path?.let { File(it).name }
            else -> null
        }
        val baseName = sourceName
            ?.substringAfterLast('/')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "clipboard-${System.currentTimeMillis()}"
        val sanitized = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (sanitized.contains('.')) return sanitized
        val extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType)
            ?.takeIf { it.isNotBlank() }
        return if (extension != null) "$sanitized.$extension" else sanitized
    }

    private fun resolveMimeType(context: Context, uri: Uri): String {
        return runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: uri.path
                ?.substringAfterLast('.', "")
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: resolveExternalStorageFile(uri)
                ?.extension
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: DEFAULT_MIME_TYPE
    }

    private fun resolveExternalStorageFile(uri: Uri): File? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val documentPath = runCatching {
            DocumentsContract.getDocumentId(uri)
        }.getOrElse {
            runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        } ?: return null
        val parts = documentPath.split(":", limit = 2)
        if (parts.size != 2) return null
        val (volume, relativePath) = parts[0] to parts[1]
        val absolutePath = when {
            volume.equals("primary", ignoreCase = true) -> {
                if (relativePath.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$relativePath"
            }

            volume.isNotBlank() -> {
                if (relativePath.isBlank()) "/storage/$volume" else "/storage/$volume/$relativePath"
            }

            else -> return null
        }
        return File(absolutePath)
    }

    private fun isContentUriWithinRoot(context: Context, uri: Uri, rootUri: Uri): Boolean {
        if (uri.authority != rootUri.authority) return false
        val childId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return false
        val rootId = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && DocumentsContract.isTreeUri(rootUri)) {
                DocumentsContract.getTreeDocumentId(rootUri)
            } else {
                DocumentsContract.getDocumentId(rootUri)
            }
        }.getOrNull() ?: return false
        return isDocumentIdWithinRoot(childId, rootId)
    }

    private fun isFileUriWithinRoot(uri: Uri, rootUri: Uri): Boolean {
        val childPath = uri.path?.let(::File)?.canonicalPath ?: return false
        val rootPath = rootUri.path?.let(::File)?.canonicalPath ?: return false
        return childPath == rootPath || childPath.startsWith(rootPath.trimEnd('/') + "/")
    }

    private fun isDocumentIdWithinRoot(childId: String, rootId: String): Boolean {
        if (childId == rootId) return true
        if (rootId.endsWith(":")) return childId.startsWith(rootId)
        return childId.startsWith("$rootId/")
    }
}
