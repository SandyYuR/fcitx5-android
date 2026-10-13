/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * @return all extracted files in zip file (including subdirectories)
 */
fun ZipInputStream.extract(destDir: File, maxEntries: Int = 100_000,
                           maxBytes: Long = 2L * 1024 * 1024 * 1024): List<File> {
    var entry = nextEntry
    val canonicalDest = destDir.canonicalFile
    val seen = mutableSetOf<String>()
    var entries = 0
    var totalBytes = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val drivePrefix = Regex("^[A-Za-z]:")
    while (entry != null) {
        if (++entries > maxEntries) throw IOException("Too many files in backup.")
        val path = entry.name.replace('\\', '/')
        if (path.startsWith('/') || drivePrefix.containsMatchIn(path)) {
            throw SecurityException("Absolute path in backup.")
        }
        val file = File(destDir, path).canonicalFile
        if (!file.path.startsWith(canonicalDest.path + File.separator)) {
            throw SecurityException("Backup entry is outside the destination directory.")
        }
        if (!seen.add(file.path)) throw IOException("Duplicate path in backup.")
        if (!entry.isDirectory) {
            val parent = file.parentFile!!
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Unable to extract backup.")
            file.outputStream().use { output ->
                while (true) {
                    val count = read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > maxBytes) throw IOException("Backup exceeds the extraction size limit.")
                    output.write(buffer, 0, count)
                }
            }
        } else {
            if (!file.isDirectory && !file.mkdirs()) throw IOException("Unable to extract backup.")
        }
        entry = nextEntry
    }
    // Return all files recursively (not just top-level)
    return destDir.walkTopDown().filter { it.isFile }.toList()
}
