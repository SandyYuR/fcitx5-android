/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.log

import java.io.File
import java.io.FileOutputStream

/**
 * 软链接探测：崩溃报告只能写进应用私有目录，指向别处的链接会把堆栈写到外部存储。
 *
 * 不能直接 `import java.nio.file.Files`：minSdk 是 23，`java.nio.file` 要到 API 26，
 * 且 core library desugaring 用的 `desugar_jdk_libs` 不覆盖它（同 AtomicFileMove 的说明），
 * 低版本设备执行到这儿会 NoClassDefFoundError。因此反射取用；取不到就当不可用——
 * Android/Linux 上 canonical 路径换算本来就会跟随链接，退回只比较 canonical 已经够拦。
 */
private object SymbolicLinkProbe {
    private val probe: ((File) -> Boolean)? = try {
        val path = Class.forName("java.nio.file.Path")
        val toPath = File::class.java.getMethod("toPath")
        val isSymbolicLink = Class.forName("java.nio.file.Files").getMethod("isSymbolicLink", path)
        val action: (File) -> Boolean = { isSymbolicLink.invoke(null, toPath.invoke(it)) as Boolean }
        action
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: NoSuchMethodException) {
        null
    }

    fun isSymbolicLink(file: File): Boolean =
        runCatching { probe?.invoke(file) ?: false }.getOrDefault(false)
}

/** One bounded report in private no-backup storage, retained across process/task recreation. */
internal class CrashReportStore(private val root: File,
    private val replace: (File, File) -> Boolean = { from, to -> from.renameTo(to) }) {
    private fun directory(): File {
        val directory = File(root.canonicalFile, "crash-reports")
        // 两条判定都要：canonical 换算在 Linux/Android 上会跟随链接因而能拦，
        // 但在 Windows 上它不解析目录链接，只有 nio 的 isSymbolicLink 认得出来。
        require(directory.canonicalFile == directory && !SymbolicLinkProbe.isSymbolicLink(directory)) {
            "Invalid crash report directory"
        }
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
