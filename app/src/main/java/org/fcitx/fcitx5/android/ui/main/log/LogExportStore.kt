/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.log

import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Receives detached raw lines only; never reads a live adapter on a worker thread. */
internal class LogExportStore(cacheDir: File, private val crashReport: () -> String? = { null }) {
    private val directory = File(cacheDir, "log-exports")

    fun prepare(header: String, lines: List<String>): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare log export directory" }
        val file = File.createTempFile("snapshot-", ".txt", directory)
        try {
            file.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(header)
                runCatching(crashReport).getOrNull()?.let {
                    writer.write("\n"); writer.write(it); writer.write("\n")
                }
                lines.forEachIndexed { index, line ->
                    if (index != 0) writer.write("\n")
                    writer.write(line)
                }
            }
            check(file.length() > 0) { "Empty log snapshot" }
            return file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    fun restore(name: String?): File? {
        if (name == null || !name.startsWith("snapshot-") || !name.endsWith(".txt") ||
            name != File(name).name) return null
        return File(directory, name).takeIf { it.isFile && it.length() > 0 }
    }

    fun copy(file: File, openDestination: () -> OutputStream?) {
        // Validate/open the prepared source before creating or truncating the destination.
        file.inputStream().use { source ->
            if (file.length() == 0L) throw IOException("Empty log snapshot")
            val destination = openDestination() ?: throw IOException("Document provider returned no output stream")
            destination.use { source.copyTo(it) }
        }
    }

    fun removeExpired(now: Long = System.currentTimeMillis()) {
        directory.listFiles()?.filter { now - it.lastModified() > 24 * 60 * 60 * 1000L }
            ?.forEach { it.delete() }
    }
}
