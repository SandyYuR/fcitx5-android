/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.log

import java.io.File
import java.io.FileOutputStream

/** One bounded report in private no-backup storage, retained across process/task recreation. */
internal class CrashReportStore(private val root: File,
    private val replace: (File, File) -> Boolean = { from, to -> from.renameTo(to) }) {
    private fun directory(): File {
        val directory = File(root.canonicalFile, "crash-reports")
        require(directory.canonicalFile == directory) { "Invalid crash report directory" }
        return directory
    }
    private val file get() = File(directory(), "last-app-crash.txt")

    fun record(report: String, now: Long = System.currentTimeMillis()) = runCatching {
        val directory = directory()
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) error("Cannot prepare crash report")
        directory.listFiles()?.filter { it.name.matches(Regex("app-crash-[0-9]+\\.tmp")) &&
            now - it.lastModified() > RETENTION_MS }?.forEach { it.delete() }
        val temp = File.createTempFile("app-crash-", ".tmp", directory)
        try {
            FileOutputStream(temp).use {
                it.write(report.take(MAX_CHARS).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            check(replace(temp, file)) { "Cannot publish crash report" }
            file.setLastModified(now)
        } finally { temp.delete() }
    }.isSuccess

    fun read(now: Long = System.currentTimeMillis()): String? = runCatching {
        if (!file.isFile || file.canonicalFile != file.absoluteFile) return null
        if (now - file.lastModified() > RETENTION_MS) { file.delete(); return null }
        if (file.length() > MAX_BYTES) return null
        file.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText().take(MAX_CHARS) }
    }.getOrNull()

    fun delete() { runCatching { file.delete() } }

    companion object {
        const val MAX_CHARS = 130_000
        private const val MAX_BYTES = MAX_CHARS * 4L
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
    }
}
