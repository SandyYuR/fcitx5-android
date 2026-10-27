/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class InvalidAssetDownload(message: String) : IOException(message)
internal data class AssetDownloadProgress(val bytes: Long, val total: Long, val speed: Long)

/** Stream to private disk; only strong validators permit appending after interruption. */
internal object ResumableAssetDownload {
    private fun strongEtag(value: String?) = value?.takeIf {
        it.length in 2..1024 && it.first() == '"' && it.last() == '"' &&
            it.substring(1, it.lastIndex).all { c -> c.code in 0x21..0x7e && c != '"' }
    }
    suspend fun run(client: OkHttpClient, url: String, asset: ReleaseAsset, files: DownloadFiles,
                    progress: (AssetDownloadProgress) -> Unit) {
        require(asset.sizeBytes in 0..DownloadFiles.MAX_BYTES) { "Asset exceeds download limit" }
        val expectedHash = asset.sha256?.lowercase()?.also {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid asset checksum" }
        }
        var state = files.read()
        if (state.verified && files.part.isFile && files.part.length() == state.total &&
            (asset.sizeBytes == 0L || files.part.length() == asset.sizeBytes) &&
            DownloadFiles.digest(files.part) == state.actualSha256 &&
            (expectedHash == null || state.actualSha256 == expectedHash)) return
        if (files.part.isFile && expectedHash != null && files.part.length() == asset.sizeBytes &&
            DownloadFiles.digest(files.part) == expectedHash) {
            files.write(state.copy(verified = true, total = files.part.length(), actualSha256 = expectedHash)); return
        }
        val validator = strongEtag(state.etag)
        val offset = if (validator != null && files.part.isFile && files.part.length() <= DownloadFiles.MAX_BYTES)
            files.part.length() else 0L
        val request = Request.Builder().url(url).header("User-Agent", "fcitx5-android-update")
            .header("Accept-Encoding", "identity").apply {
                if (offset > 0) { header("Range", "bytes=$offset-"); header("If-Range", validator!!) }
            }.build()
        val call = client.newCall(request)
        val finished = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                val callback = object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        try { if (continuation.isActive) continuation.resumeWithException(e) }
                        finally { finished.complete(Unit) }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use { reply ->
                                fun active() {
                                    if (!continuation.isActive || files.cancelled) throw kotlinx.coroutines.CancellationException("Download stopped")
                                }
                                active()
                                if (reply.code == 416) {
                                    files.part.delete(); files.write(state.copy(etag = null, verified = false, actualSha256 = null))
                                    throw IOException("Server rejected the saved download range; restarting")
                                }
                                if (!reply.isSuccessful) {
                                    if (reply.code in listOf(408, 429) || reply.code >= 500) throw IOException("Download HTTP ${reply.code}")
                                    throw InvalidAssetDownload("Download HTTP ${reply.code}")
                                }
                                if (reply.header("Content-Encoding")?.let { !it.equals("identity", true) } == true)
                                    throw InvalidAssetDownload("Unexpected content encoding")
                                val body = reply.body ?: throw IOException("Empty download response")
                                val rawEtag = reply.header("ETag")
                                val etag = strongEtag(rawEtag)
                                val start: Long
                                val total: Long
                                if (reply.code == 206) {
                                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(reply.header("Content-Range").orEmpty())
                                        ?: throw InvalidAssetDownload("Invalid Content-Range")
                                    val first = range.groupValues[1].toLongOrNull() ?: throw InvalidAssetDownload("Invalid range start")
                                    val last = range.groupValues[2].toLongOrNull() ?: throw InvalidAssetDownload("Invalid range end")
                                    total = range.groupValues[3].toLongOrNull() ?: throw InvalidAssetDownload("Invalid range total")
                                    if (offset == 0L || first != offset || last < first || last != total - 1 ||
                                        (body.contentLength() >= 0 && body.contentLength() != last - first + 1))
                                        throw InvalidAssetDownload("Unexpected download range")
                                    if (rawEtag != null && rawEtag != validator) {
                                        files.part.delete(); files.write(state.copy(etag = null, verified = false, actualSha256 = null))
                                        throw IOException("Download version changed; restarting")
                                    }
                                    start = offset
                                } else {
                                    if (reply.code != 200) throw InvalidAssetDownload("Unexpected download response")
                                    start = 0L
                                    total = body.contentLength().takeIf { it >= 0 } ?: asset.sizeBytes
                                }
                                if (total > DownloadFiles.MAX_BYTES || total < start ||
                                    (asset.sizeBytes > 0 && total > 0 && total != asset.sizeBytes))
                                    throw InvalidAssetDownload("Download size differs from release metadata")
                                files.requireSpace((total - start).coerceAtLeast(0) + total)
                                state = state.copy(etag = etag ?: if (start > 0) validator else null,
                                    total = total.takeIf { it > 0 }, verified = false, actualSha256 = null)
                                files.write(state)
                                var downloaded = start
                                var sampledBytes = start
                                var sampledAt = System.nanoTime()
                                var spaceCheckedAt = start
                                progress(AssetDownloadProgress(start, total, 0))
                                RandomAccessFile(files.part, "rw").use { destination ->
                                    if (start == 0L) destination.setLength(0)
                                    destination.seek(start)
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(32 * 1024)
                                        while (true) {
                                            active()
                                            val n = input.read(buffer); if (n < 0) break
                                            downloaded += n
                                            if (downloaded > DownloadFiles.MAX_BYTES || (total > 0 && downloaded > total))
                                                throw InvalidAssetDownload("Download exceeds declared size")
                                            destination.write(buffer, 0, n)
                                            if (downloaded - spaceCheckedAt >= 1024 * 1024) {
                                                files.requireSpace(if (total > 0) total - downloaded + total else downloaded)
                                                spaceCheckedAt = downloaded
                                            }
                                            val now = System.nanoTime()
                                            if (now - sampledAt >= 1_000_000_000L) {
                                                destination.fd.sync()
                                                progress(AssetDownloadProgress(downloaded, total,
                                                    (downloaded - sampledBytes) * 1_000_000_000L / (now - sampledAt)))
                                                sampledAt = now; sampledBytes = downloaded
                                            }
                                        }
                                    }
                                    active(); destination.fd.sync()
                                }
                                if (downloaded == 0L || (total > 0 && downloaded != total) ||
                                    (asset.sizeBytes > 0 && downloaded != asset.sizeBytes)) throw IOException("Incomplete download")
                                active()
                                files.requireSpace(downloaded)
                                val digest = DownloadFiles.digest(files.part)
                                if (expectedHash != null && digest != expectedHash) throw InvalidAssetDownload("Download checksum mismatch")
                                files.write(state.copy(total = downloaded, verified = true, actualSha256 = digest))
                                progress(AssetDownloadProgress(downloaded, downloaded, 0))
                            }
                            if (continuation.isActive) continuation.resume(Unit)
                        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                        finally { finished.complete(Unit) }
                    }
                }
                try { call.enqueue(callback) }
                catch (e: Exception) { finished.complete(Unit); if (continuation.isActive) continuation.resumeWithException(e) }
            }
        } finally {
            // Cancellation resumes the coroutine before onResponse unwinds. Keep the file lock
            // until that callback has closed its response and file, so cleanup cannot race it.
            withContext(NonCancellable) { finished.await() }
        }
    }
}
