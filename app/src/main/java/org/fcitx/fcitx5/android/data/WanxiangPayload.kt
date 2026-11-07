/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Only scheme resources enter the live directory. User state is never an update asset. */
internal object WanxiangPayload {
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    private val resourceDirectories = setOf("lua", "opencc", "dicts", "custom")

    fun relative(path: String): String {
        require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && '\u0000' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." || ':' in it }) { "Invalid update path: $path" }
        return path
    }

    fun protected(path: String): Boolean {
        val parts = relative(path).lowercase(Locale.ROOT).split('/')
        val name = parts.last()
        return parts.any { it.startsWith('.') || it == "build" || it == "sync" || ".userdb" in it } ||
            name.endsWith(".custom.yaml") || name in setOf("user.yaml", "installation.yaml", "custom_phrase.txt",
                "custom_phrase.dict.yaml", "wanxiang_abbrev.dict.yaml") ||
            (name.startsWith("sequence") && name.endsWith(".txt"))
    }

    fun runtimeState(path: String): Boolean {
        val parts = relative(path).lowercase(Locale.ROOT).split('/')
        return parts.any { it.startsWith('.') || it == "build" || it == "sync" || ".userdb" in it } ||
            parts.last() in setOf("user.yaml", "installation.yaml")
    }

    fun resource(path: String, allowOverrides: Boolean = false): Boolean {
        if (if (allowOverrides) runtimeState(path) else protected(path)) return false
        val parts = path.split('/')
        return if (parts.size > 1) parts.first() in resourceDirectories
        else path.endsWith(".yaml") || path.endsWith(".lua") || path.endsWith(".gram") || path == "version.txt" ||
            allowOverrides && path.endsWith(".txt")
    }

    fun child(root: File, path: String): File {
        relative(path)
        val file = File(root, path)
        require(file.canonicalFile == file.absoluteFile) { "Update path is redirected: $path" }
        require(file.exists() || file.parentFile?.list()?.contains(file.name) != true) { "Update path is a dangling symbolic link: $path" }
        return file
    }

    fun extract(archive: File, payload: File, dictionary: Boolean, allowOverrides: Boolean = false, checkActive: () -> Unit = {}) {
        check(payload.isDirectory || payload.mkdirs())
        val names = ZipFile(archive).use { zip ->
            zip.entries().asSequence().map { entry ->
                checkActive()
                relative(entry.name.trimEnd('/'))
            }.take(MAX_ENTRIES + 1).toList()
        }
        require(names.isNotEmpty() && names.size <= MAX_ENTRIES) { "Invalid update archive entry count" }
        val roots = names.map { it.substringBefore('/') }.distinct()
        val wrapper = roots.singleOrNull()?.takeIf { it !in resourceDirectories && names.all { n -> n == it || n.startsWith("$it/") } &&
            names.any { n -> n.startsWith("$it/") } }
        val seen = HashSet<String>()
        var bytes = payload.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        var accepted = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var count = 0
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                require(++count <= MAX_ENTRIES)
                val original = relative(entry.name.trimEnd('/'))
                require(seen.add(original)) { "Duplicate archive path: $original" }
                val stripped = if (wrapper != null) original.removePrefix("$wrapper/") else original
                val path = if (dictionary && !stripped.startsWith("dicts/")) "dicts/$stripped" else stripped
                val install = !entry.isDirectory && resource(path, allowOverrides) && (!dictionary || path.endsWith(".dict.yaml"))
                val target = if (install) child(payload, path) else null
                if (target != null) check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                val output = target?.outputStream()?.buffered()
                try {
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        checkActive()
                        val n = zip.read(buffer)
                        if (n < 0) break
                        bytes += n
                        require(bytes <= MAX_BYTES) { "Update archive exceeds extraction limit" }
                        output?.write(buffer, 0, n)
                    }
                } finally { output?.close() }
                if (install) accepted++
                zip.closeEntry() // Validate CRC even for excluded entries.
            }
        }
        require(accepted > 0) { "Archive contains no scheme resources" }
        if (!dictionary) require(payload.listFiles().orEmpty().any { it.name.startsWith("wanxiang") && it.name.endsWith(".schema.yaml") }) {
            "Archive contains no Wanxiang scheme"
        }
    }

    fun model(download: File, payload: File) {
        val header = "Rime::Grammar/1.0".toByteArray(Charsets.US_ASCII)
        val actual = download.inputStream().use { input -> ByteArray(header.size).also { require(input.read(it) == it.size) } }
        if (!actual.contentEquals(header)) throw IOException("Invalid Rime grammar model")
        check(payload.isDirectory || payload.mkdirs())
        download.copyTo(child(payload, "wanxiang-lts-zh-hans.gram"), overwrite = true)
    }
}
