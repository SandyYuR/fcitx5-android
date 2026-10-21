package org.fcitx.directrime

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Extract only into a new, private staging directory; never follow an archive path outside it. */
internal object ProfileArchive {
    fun extract(input: InputStream, staging: File, maxBytes: Long = 512L * 1024 * 1024) {
        check(!staging.exists() && staging.mkdirs())
        try {
            val root = staging.canonicalPath + File.separator
            var bytes = 0L
            var entries = 0
            val seen = HashSet<String>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 20_000) { "压缩包文件数量过多" }
                    val path = entry.name
                    require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path &&
                        path.split('/').none { it == ".." || ':' in it }) { "无效压缩包路径" }
                    val target = File(staging, path)
                    require(target.canonicalPath.startsWith(root)) { "压缩包路径越界" }
                    require(seen.add(target.canonicalPath)) { "压缩包含有重复路径" }
                    if (entry.isDirectory) check(target.mkdirs() || target.isDirectory)
                    else {
                        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                        target.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= maxBytes) { "解压数据超过上限" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(File(staging, "default.yaml").isFile || File(staging, "default.custom.yaml").isFile) {
                "请将 Rime 用户目录中的文件直接打包到 ZIP 根目录（需含 default.yaml 或 default.custom.yaml）"
            }
        } catch (e: Exception) { staging.deleteRecursively(); throw e }
    }

    fun install(staging: File, user: File) {
        val previous = File(user.parentFile, "previous")
        check(!previous.exists() || previous.deleteRecursively())
        val existed = user.exists()
        if (existed) check(user.renameTo(previous)) { "无法备份实验目录" }
        if (!staging.renameTo(user)) {
            if (existed) check(previous.renameTo(user)) { "恢复实验目录失败，旧数据保留在 previous" }
            error("无法安装实验目录")
        }
        // Retain the previous profile for manual recovery and fair A/B resets.
    }

    fun restorePrevious(user: File) {
        val previous = File(user.parentFile, "previous")
        val swap = File(user.parentFile, "rollback-swap")
        require(previous.isDirectory) { "没有上一份实验数据" }
        check(!swap.exists()) { "发现未完成的恢复，请保留 rollback-swap 并检查目录" }
        val existed = user.exists()
        if (existed) check(user.renameTo(swap))
        if (!previous.renameTo(user)) {
            if (existed) check(swap.renameTo(user))
            error("恢复实验数据失败，上一份仍保留在 previous")
        }
        if (existed) check(swap.renameTo(previous)) { "已恢复旧数据，刚才的数据保留在 rollback-swap" }
    }
}
