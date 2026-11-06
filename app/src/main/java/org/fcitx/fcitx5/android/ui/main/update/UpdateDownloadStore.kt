/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.util.UUID

/** Exact download identities survive updating this APK. Only used on download/settings IO paths. */
internal object UpdateDownloadStore {
    @Serializable internal data class Record(
        val asset: ReleaseAsset,
        val downloadId: Long? = null,
        val uri: String? = null,
        val sha256: String? = null,
        val release: String? = null,
    )
    internal data class Match(val uri: Uri, val downloadId: Long?, val size: Long)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private fun file(context: Context) = File(context.noBackupFilesDir, "update-downloads.json")
    private fun read(context: Context): List<Record> = runCatching {
        val source = file(context)
        if (!source.isFile || source.length() > 512 * 1024) emptyList()
        else json.decodeFromString<List<Record>>(source.readText()).takeLast(128)
    }.getOrDefault(emptyList())
    private fun write(context: Context, records: List<Record>) {
        FileUtil.writeAtomically(file(context), Json.encodeToString(records.takeLast(128)))
    }
    private fun identity(record: Record) = record.downloadId?.let { "dm:$it" } ?: record.uri
    fun remember(context: Context, record: Record) = synchronized(lock) {
        require(record.downloadId != null || record.uri != null)
        val previous = read(context)
        write(context, previous.filterNot { identity(it) == identity(record) } + record)
    }
    fun releaseIdentity(context: Context, asset: ReleaseAsset): String? = UpdatePrefs.loadReleaseCache(context)?.let {
        if (asset !in it.assets) null else it.publishedAt?.let { time -> "${it.tagName}:$time" }
    }

    /** Avoid DownloadManager ERROR_FILE_ALREADY_EXISTS without touching an unowned namesake. */
    fun destinationName(name: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = clean.lastIndexOf('.')
        val suffix = if (dot > 0) clean.substring(dot).take(16) else ""
        val stem = (if (dot > 0) clean.substring(0, dot) else clean).take(180)
        return "$stem-${UUID.randomUUID().toString().take(12)}$suffix"
    }

    private fun ownedUri(context: Context, record: Record): Uri? {
        record.downloadId?.let { id ->
            val manager = context.getSystemService(DownloadManager::class.java)
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                if (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) return null
                if (cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) != record.asset.name) return null
                return manager.getUriForDownloadedFile(id)
            }
            return null
        }
        if (Build.VERSION.SDK_INT < 29) return null
        val uri = record.uri?.let(Uri::parse) ?: return null
        if (uri.scheme != "content" || uri.authority != "media" || uri.pathSegments.size != 3 ||
            uri.pathSegments[1] != "downloads" || uri.lastPathSegment?.toLongOrNull() == null) return null
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
            if (it.moveToFirst() && it.getString(0) == context.packageName &&
                it.getString(1).orEmpty().trimEnd('/') == Environment.DIRECTORY_DOWNLOADS && it.getInt(2) == 0) return uri
        }
        return null
    }

    private fun digest(context: Context, uri: Uri) = context.contentResolver.openInputStream(uri)?.use {
        DownloadFiles.digest(it, DownloadFiles.MAX_BYTES)
    }
    private fun matches(record: Record, asset: ReleaseAsset, release: String?, actual: String): Boolean {
        val expected = asset.sha256?.takeIf { it.isNotBlank() }
        return if (expected != null) expected.equals(actual, true)
        else record.asset == asset && release != null && record.release == release &&
            (record.sha256 == null || record.sha256 == actual)
    }

    /** Opening the updater can recover renamed files without triggering deletion. */
    fun find(context: Context, asset: ReleaseAsset): Match? {
        val records = synchronized(lock) { read(context) }.asReversed()
        val release = releaseIdentity(context, asset)
        for (record in records.filter { it.asset.browserDownloadUrl == asset.browserDownloadUrl }) {
            val match = runCatching {
                val uri = ownedUri(context, record) ?: return@runCatching null
                val actual = digest(context, uri) ?: return@runCatching null
                if (!matches(record, asset, release, actual)) return@runCatching null
                Match(uri, record.downloadId, record.asset.sizeBytes)
            }.getOrNull()
            if (match != null) return match
        }
        return null
    }

    /** Invoked explicitly before a new download, after the updated application has restarted. */
    fun beforeDownload(context: Context, asset: ReleaseAsset): Match? {
        val recorded = synchronized(lock) { read(context) }
        // Migrate only this application's successful DownloadManager jobs. Older
        // releases had no private ledger; no filesystem-wide APK scan is needed.
        val legacy = runCatching {
            context.getSystemService(DownloadManager::class.java)
                .query(DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL))?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext() && size < 128) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                            if (recorded.any { it.downloadId == id }) continue
                            val name = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty()
                            val url = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)).orEmpty()
                            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            if (name.endsWith(".apk", true) && bytes in 1..DownloadFiles.MAX_BYTES)
                                add(Record(ReleaseAsset(name, url, bytes, null), downloadId = id))
                        }
                    }
                }.orEmpty()
        }.getOrDefault(emptyList())
        val records = recorded + legacy
        val release = releaseIdentity(context, asset)
        val installedFile = context.applicationInfo.sourceDir?.let(::File)
        val installedSize = installedFile?.length() ?: -1
        val installedDigest by lazy { installedFile?.let { runCatching { DownloadFiles.digest(it) }.getOrNull() } }
        var reusable: Match? = null
        for (record in records) {
            val sameAsset = record.asset.browserDownloadUrl == asset.browserDownloadUrl ||
                (asset.sha256 != null && asset.sha256.equals(record.sha256 ?: record.asset.sha256, true))
            val possibleInstalled = record.asset.name.endsWith(".apk", true) &&
                (record.asset.sizeBytes <= 0 || record.asset.sizeBytes == installedSize)
            if (!sameAsset && !possibleInstalled) continue
            runCatching {
                val uri = ownedUri(context, record) ?: return@runCatching
                val actual = digest(context, uri) ?: return@runCatching
                if (record.sha256 != null && record.sha256 != actual) return@runCatching
                if (record.asset.sha256?.takeIf { it.isNotBlank() }?.equals(actual, true) == false) return@runCatching
                if (matches(record, asset, release, actual)) {
                    reusable = Match(uri, record.downloadId, record.asset.sizeBytes)
                } else if (possibleInstalled && installedDigest != null && actual == installedDigest) {
                    // Byte identity with the currently installed base APK proves installation.
                    // Do not infer success from returning from the installer or from a filename.
                    if (ownedUri(context, record) != uri) return@runCatching
                    val removed = record.downloadId?.let { context.getSystemService(DownloadManager::class.java).remove(it) > 0 }
                        ?: (context.contentResolver.delete(uri, null, null) > 0)
                    if (removed) synchronized(lock) { write(context, read(context).filterNot { identity(it) == identity(record) }) }
                }
            }.onFailure { timber.log.Timber.w(it, "Could not inspect a previous update download") }
        }
        return reusable
    }

    fun rememberCompleted(context: Context, record: Record) {
        val uri = ownedUri(context, record) ?: return
        val actual = record.sha256 ?: digest(context, uri) ?: return
        if (record.asset.sha256?.takeIf { it.isNotBlank() }?.equals(actual, true) == false) return
        val original = synchronized(lock) { read(context).firstOrNull { identity(it) == identity(record) } }
        remember(context, record.copy(uri = uri.toString(), sha256 = actual,
            release = original?.takeIf { it.asset == record.asset }?.release ?: record.release))
    }
}

internal fun downloadNameMatchesAsset(displayName: String, assetName: String): Boolean {
    if (displayName == assetName) return true
    val dot = assetName.lastIndexOf('.')
    if (dot <= 0) return false
    return Regex("^${Regex.escape(assetName.substring(0, dot))} ?\\([0-9]+\\)${Regex.escape(assetName.substring(dot))}$")
        .matches(displayName)
}
