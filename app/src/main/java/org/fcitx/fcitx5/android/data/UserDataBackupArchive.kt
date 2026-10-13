/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Return a complete private archive only after the engine has resumed successfully. */
internal object UserDataBackupArchive {
    const val PREFIX = "user-data-export-"

    fun prepare(cacheDir: File, pause: () -> Unit, resume: () -> Unit,
                write: (OutputStream) -> Unit): File {
        val archive = File.createTempFile(PREFIX, ".zip", cacheDir)
        var paused = false
        var failure: Throwable? = null
        try {
            pause()
            paused = true
            archive.outputStream().use(write)
        } catch (e: Throwable) {
            failure = e
        } finally {
            if (paused) {
                try { resume() }
                catch (e: Throwable) {
                    if (failure == null) failure = e else failure.addSuppressed(e)
                }
            }
        }
        if (failure != null) {
            if (archive.exists() && !archive.delete()) failure.addSuppressed(IOException("Unable to remove an incomplete private backup."))
            throw failure
        }
        return archive
    }

    /** A saved-state path can only refer to an archive created in this application's cache. */
    fun pending(path: String?, cacheDir: File): File? = path?.let { File(it) }?.takeIf {
        it.canonicalFile.parentFile == cacheDir.canonicalFile && it.name.startsWith(PREFIX) &&
            it.name.endsWith(".zip") && it.isFile
    }
}
