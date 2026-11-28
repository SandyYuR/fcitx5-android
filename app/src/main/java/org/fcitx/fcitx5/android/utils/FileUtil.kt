/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.system.Os
import android.system.OsConstants
import org.fcitx.fcitx5.android.FcitxApplication
import java.io.File
import java.io.IOException
import java.io.OutputStream

object FileUtil {

    private val writeLocks = Array(32) { Any() }

    private fun File.isSymlink(): Boolean = OsConstants.S_ISLNK(Os.lstat(path).st_mode)

    /**
     * Delete a [File].
     * If it's a directory, delete its contents first.
     * If it's a symlink, don't follow.
     */
    fun removeFile(file: File) = runCatching {
        if (!file.exists())
            return@runCatching
        val result = if (file.isSymlink()) {
            file.delete()
        } else if (file.isDirectory) {
            file.walkBottomUp()
                .onEnter {
                    // delete symlink (to directory) instead of entering it
                    if (it.isSymlink()) {
                        it.delete()
                        false
                    } else {
                        true
                    }
                }
                .fold(true) { acc, it ->
                    if (!it.exists()) acc else it.delete()
                }
        } else {
            file.delete()
        }
        if (!result)
            throw IOException("Cannot delete '${file.path}'")
    }

    fun symlink(source: File, target: File) = runCatching {
        target.parentFile?.mkdirs()
        Os.symlink(source.path, target.path)
    }

    /**
     * 原子写入文本：先写同目录临时文件并 fsync，再 rename 覆盖目标。
     *
     * 直接 `file.writeText(...)` 在进程中途被杀时会留下被截断的半个文件，调用方
     * 下次读取只能解析失败并退回默认值（例如 DataManager 会退化成"全量重同步"）。
     * rename 在同一文件系统内是原子的，因此读到的要么是旧内容、要么是新内容。
     */
    fun writeAtomically(file: File, content: String) = writeAtomically(file) {
        it.write(content.toByteArray(Charsets.UTF_8))
    }

    /** Serialize writers to the same path; a failed replacement must preserve the old file. */
    fun writeAtomically(file: File, write: (OutputStream) -> Unit) =
        writeAtomically(file, applicationDataRoot(), write)

    private fun applicationDataRoot(): File? = try {
        FcitxApplication.getInstance().applicationInfo?.dataDir?.takeIf { it.isNotBlank() }?.let(::File)
    } catch (_: IllegalStateException) {
        // Pure JVM callers and early startup have no trusted Context: retain strict validation.
        null
    }

    /** The optional root is supplied by Context in production; explicit roots are for filesystem tests. */
    internal fun writeAtomically(file: File, trustedRoot: File?, write: (OutputStream) -> Unit) =
        synchronized(writeLocks[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % writeLocks.size]) {
        val target = FilePathGuard.resolveWritePath(file, trustedRoot)
        val canonicalRoot = trustedRoot?.canonicalFile
        val parent = target.parentFile
        if (parent != null) FilePathGuard.resolveWritePath(parent, canonicalRoot)
        parent?.mkdirs()
        val tmp = File(parent, "${target.name}.tmp")
        require(tmp.exists() || tmp.absoluteFile.parentFile?.list()?.contains(tmp.name) != true) { "Temporary path is a dangling symbolic link" }
        require(!tmp.exists() || tmp.isFile) { "Temporary path is not a regular file" }
        FilePathGuard.resolveWritePath(tmp, canonicalRoot)
        try {
            java.io.FileOutputStream(tmp).use { out ->
                write(out)
                out.flush()
                // 元数据 rename 是原子的，但内容必须先落盘，否则崩溃后可能 rename 出空文件。
                out.fd.sync()
            }
            replaceAtomically(tmp, target)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /** API 23–25 use Android's atomic rename; newer Android and host JVMs can also replace files. */
    fun replaceAtomically(source: File, destination: File) {
        AtomicFileMove.replace(source, destination)
    }
}
