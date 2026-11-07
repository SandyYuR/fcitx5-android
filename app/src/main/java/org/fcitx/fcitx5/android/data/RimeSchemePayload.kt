/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Stage resources only. Neither archives nor task destinations may redirect writes outside Rime. */
internal object RimeSchemePayload {
    const val MAX_BYTES = WanxiangPayload.MAX_BYTES
    const val MAX_ENTRIES = WanxiangPayload.MAX_ENTRIES

    fun resource(path: String): Boolean {
        WanxiangPayload.relative(path)
        require(path.length <= 1024 && path.none(Char::isISOControl)) { "无效的资源路径" }
        val parts = path.lowercase(Locale.ROOT).split('/')
        require(parts.all { it.toByteArray().size <= 240 }) { "资源文件名过长" }
        return parts.none { it.startsWith('.') || it in setOf("build", "sync") || ".userdb" in it } &&
            parts.last() !in setOf("user.yaml", "installation.yaml")
    }

    /** Also bounds skipped members and archive padding, so excluded entries cannot bypass limits. */
    private class Bounded(input: InputStream, private val limit: Long, private val check: () -> Unit) : FilterInputStream(input) {
        private var bytes = 0L
        private fun count(n: Int): Int {
            check()
            if (n > 0) { bytes += n; require(bytes <= limit) { "资源解压后超过大小限制" } }
            return n
        }
        override fun read(): Int { check(); val value = `in`.read(); if (value >= 0) count(1); return value }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { check(); return count(`in`.read(buffer, offset, length)) }
        override fun skip(n: Long): Long {
            var left = n
            val buffer = ByteArray(8192)
            while (left > 0) { val read = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); if (read < 0) break; left -= read }
            return n - left
        }
    }

    fun stage(source: File, task: RimeSchemeTask, scratch: File, payload: File,
              limit: Long = MAX_BYTES, checkActive: () -> Unit = {}): Int {
        task.validated()
        check(!scratch.exists() && scratch.mkdirs())
        check(payload.isDirectory || payload.mkdirs())
        try {
            val wrapper = if (task.unpack) extract(source, scratch, limit, checkActive) else {
                source.inputStream().use { input ->
                    copy(Bounded(input, limit, checkActive), WanxiangPayload.child(scratch, task.fileName), checkActive)
                }
                null
            }
            val root = if (task.stripRoot && wrapper != null) File(scratch, wrapper).takeIf(File::isDirectory) ?: scratch else scratch
            val rules = task.rules()
            var size = payload.walkTopDown().filter(File::isFile).sumOf(File::length)
            var count = payload.walkTopDown().count(File::isFile)
            var accepted = 0
            root.walkTopDown().filter(File::isFile).forEach { file ->
                checkActive()
                val relative = file.relativeTo(root).invariantSeparatorsPath
                require(WanxiangPayload.child(root.canonicalFile, relative) == file.absoluteFile)
                for (destination in task.destinations) {
                    val path = if (destination.isEmpty()) relative else "$destination/$relative"
                    if (!resource(path) || rules.any { it.containsMatchIn(relative) || it.containsMatchIn(path) }) continue
                    val target = WanxiangPayload.child(payload.canonicalFile, path)
                    require(!target.exists() || target.isFile) { "资源目标发生文件与目录冲突：$path" }
                    size += file.length() - if (target.isFile) target.length() else 0
                    if (!target.exists()) count++
                    require(size <= limit && count <= MAX_ENTRIES) { "本次任务的资源总量超过限制" }
                    require(payload.usableSpace - file.length() >= 32L * 1024 * 1024) { "存储空间不足" }
                    file.inputStream().use { copy(it, target, checkActive) }
                    accepted++
                }
            }
            return accepted
        } finally { FileUtil.removeFile(scratch).getOrThrow() }
    }

    private fun copy(input: InputStream, target: File, check: () -> Unit) {
        require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        target.outputStream().buffered().use { output ->
            val bytes = ByteArray(32 * 1024)
            var sinceSpaceCheck = 0
            while (true) {
                check(); val n = input.read(bytes); if (n < 0) break
                output.write(bytes, 0, n); sinceSpaceCheck += n
                if (sinceSpaceCheck >= 1024 * 1024) {
                    require(target.parentFile!!.usableSpace >= 32L * 1024 * 1024) { "存储空间不足" }
                    sinceSpaceCheck = 0
                }
            }
        }
    }

    private fun extract(source: File, root: File, limit: Long, check: () -> Unit): String? {
        val header = ByteArray(512)
        val length = source.inputStream().use { it.read(header) }
        require(length > 0) { "压缩包为空" }
        fun starts(vararg values: Int) = values.indices.all { it < length && (header[it].toInt() and 255) == values[it] }
        val seen = HashSet<String>()
        val topLevel = HashSet<String>()
        var hasRootFile = false
        var entries = 0
        fun member(name: String, directory: Boolean, stream: InputStream) {
            check()
            require(++entries <= MAX_ENTRIES) { "压缩包文件数量过多" }
            val path = name.trimEnd('/').removePrefix("./")
            if (directory && (path.isEmpty() || path == ".")) return
            WanxiangPayload.relative(path)
            require(seen.add(path.lowercase(Locale.ROOT))) { "压缩包包含重复路径：$path" }
            topLevel += path.substringBefore('/')
            if (!directory && '/' !in path) hasRootFile = true
            val target = if (resource(path)) WanxiangPayload.child(root.canonicalFile, path) else null
            if (directory) { if (target != null) require(target.isDirectory || target.mkdirs()); return }
            if (target != null) copy(stream, target, check)
            else { val buffer = ByteArray(32 * 1024); while (stream.read(buffer) >= 0) check() }
        }
        if (starts(0x50, 0x4b, 3, 4) || starts(0x50, 0x4b, 5, 6)) {
            // Count inflated bytes, including members discarded by the protection rules.
            ZipInputStream(source.inputStream().buffered()).use { zip ->
                val bounded = Bounded(zip, limit, check)
                while (true) {
                    check(); val entry = zip.nextEntry ?: break
                    member(entry.name, entry.isDirectory, bounded)
                    if (entry.isDirectory) { val buffer = ByteArray(8192); while (bounded.read(buffer) >= 0) check() }
                    zip.closeEntry()
                }
            }
        } else {
            val raw = source.inputStream().buffered()
            val decoded = try {
                when {
                    starts(0x1f, 0x8b) -> GZIPInputStream(raw)
                    starts(0x42, 0x5a, 0x68) -> BZip2CompressorInputStream(raw)
                    starts(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0) -> XZCompressorInputStream(raw, false, 128 * 1024)
                    length >= 512 && RimeTarReader.matches(header) -> raw
                    else -> error("不支持的压缩格式。请选择 ZIP、TAR、TAR.GZ、TAR.BZ2 或 TAR.XZ")
                }
            } catch (error: Throwable) { raw.close(); throw error }
            Bounded(decoded, limit, check).use { bounded ->
                RimeTarReader.extract(bounded, check, ::member)
            }
        }
        require(entries > 0) { "压缩包没有文件" }
        return topLevel.singleOrNull()?.takeUnless { hasRootFile }
    }
}
