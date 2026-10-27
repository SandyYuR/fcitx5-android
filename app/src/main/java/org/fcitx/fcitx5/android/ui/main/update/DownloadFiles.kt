/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.UUID

@Serializable
internal data class DownloadCheckpoint(
    val etag: String? = null,
    val total: Long? = null,
    val verified: Boolean = false,
    val actualSha256: String? = null,
    val pendingUri: String? = null,
    val publishedUri: String? = null
)

/** A work UUID owns a private directory. It never uses an asset name as a filesystem path. */
internal class DownloadFiles(root: File, id: UUID) {
    private val safeRoot = File(root.parentFile!!.canonicalFile, root.name).also {
        if (it.canonicalFile != it.absoluteFile) throw IOException("Download staging root was redirected")
    }
    val directory = File(safeRoot, id.toString())
    val part get() = child("content.part")
    val pendingName = "fcitx-download-$id.pending"
    private val checkpoint get() = child("checkpoint.json")
    val cancelled get() = child("cancelled").isFile

    private fun child(name: String): File {
        if (directory.canonicalFile != directory.absoluteFile) throw IOException("Download staging was redirected")
        val file = File(directory, name)
        if (file.canonicalFile != file.absoluteFile) throw IOException("Download staging file was redirected")
        return file
    }
    fun prepare() {
        child("work.lock")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot prepare private download staging")
    }
    fun read(): DownloadCheckpoint {
        val file = checkpoint
        if (!file.exists()) return DownloadCheckpoint()
        if (file.length() > 32 * 1024) throw IOException("Download checkpoint exceeds limit")
        return Json.decodeFromString(file.readText())
    }
    fun write(state: DownloadCheckpoint) = FileUtil.writeAtomically(checkpoint, Json.encodeToString(state))
    fun markCancelled() { prepare(); FileUtil.writeAtomically(child("cancelled"), "1") }
    fun requireSpace(additional: Long, available: Long = directory.usableSpace) {
        if (additional < 0 || available - additional < 32L * 1024 * 1024)
            throw InvalidAssetDownload("Insufficient storage for download and publication")
    }

    /** The caller holds this lock until async HTTP callbacks have closed their files too. */
    suspend fun <T> locked(block: suspend () -> T): T {
        prepare()
        RandomAccessFile(child("work.lock"), "rw").use { file ->
            val lock = try { file.channel.lock() } catch (e: OverlappingFileLockException) {
                throw IOException("Previous download worker has not released its files", e)
            }
            lock.use { return block() }
        }
    }
    fun tryLocked(block: () -> Unit): Boolean {
        prepare()
        RandomAccessFile(child("work.lock"), "rw").use { file ->
            val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null } ?: return false
            lock.use { block() }
        }
        return true
    }
    fun removePrivateContent() {
        // Do not recursively walk unexpected children or follow links during cleanup.
        listOf("content.part", "checkpoint.json", "checkpoint.json.tmp", "cancelled", "cancelled.tmp")
            .forEach { child(it).delete() }
    }
    fun removeEmptyDirectory() {
        if (directory.list()?.toSet() == setOf("work.lock")) {
            child("work.lock").delete()
            directory.delete()
        }
    }
    companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
        fun digest(file: File): String = file.inputStream().use { digest(it, MAX_BYTES) }
        fun digest(input: java.io.InputStream, max: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(bytes); if (n < 0) break
                total += n; if (total > max) throw IOException("Download exceeds limit")
                digest.update(bytes, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
