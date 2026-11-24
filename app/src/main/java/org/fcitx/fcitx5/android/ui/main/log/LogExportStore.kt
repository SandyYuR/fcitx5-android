/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.log

import android.app.Application
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.fcitx.fcitx5.android.FcitxApplication

/** Receives detached raw lines only; never reads a live adapter on a worker thread. */
internal class LogExportStore(cacheDir: File, private val crashReport: () -> String? = { null }) {
    private val directory = File(cacheDir, "log-exports")

    /**
     * Renders exactly the text [prepare] writes: the header, the retained crash report when there is
     * one, then the frozen lines. Sharing goes through this too, so both export options carry the
     * same diagnostics — the stored crash report is the only record left once the crashing process'
     * logcat has rolled over.
     */
    fun render(header: String, lines: List<String>): String = buildString {
        append(header)
        runCatching(crashReport).getOrNull()?.let { append('\n').append(it).append('\n') }
        lines.forEachIndexed { index, line ->
            if (index != 0) append('\n')
            append(line)
        }
    }

    fun prepare(header: String, lines: List<String>): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare log export directory" }
        val file = File.createTempFile("snapshot-", ".txt", directory)
        try {
            file.writeText(render(header, lines), Charsets.UTF_8)
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

/** The one place that decides where the retained crash report lives; export and share both use it. */
internal fun logExportStore(application: Application): LogExportStore =
    LogExportStore(application.cacheDir) {
        val context = (application as? FcitxApplication)?.directBootAwareContext ?: application
        CrashReportStore(context.noBackupFilesDir).read()
    }
