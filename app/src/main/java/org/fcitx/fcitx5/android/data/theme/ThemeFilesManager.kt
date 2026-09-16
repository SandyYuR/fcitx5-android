package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileFilter
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ThemeFilesManager {

    /**
     * 主题 ZIP 的限额，做法与常量风格对齐 [IconThemeManager] 的 A6 修复（不另造一套）。
     *
     * 主题包就是一个 JSON 加两张图片，这些上限对正常包非常宽松，目的是把压缩炸弹/超大包
     * 变成可处理的失败，而不是让 [InputStream.readBytes] 把整个归档读进堆（OOM 是 Error，
     * `runCatching` 和上层的 `catch (Exception)` 都拦不住），或让逐条目解压把 cache 目录写满。
     *
     * 单条目上限取 16MB 而不是 IconThemeManager 的 4MB：图标主题的图片是按钮小图，
     * 主题包的背景图是整屏壁纸（导出时原图与裁切图一起打包），4MB 会误伤正常主题。
     */
    private const val MAX_IMPORT_ARCHIVE_BYTES = 16 * 1024 * 1024
    private const val MAX_IMPORT_JSON_BYTES = 1024 * 1024
    private const val MAX_IMPORT_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_IMPORT_TOTAL_BYTES = 32L * 1024 * 1024
    private const val MAX_IMPORT_ENTRIES = 256

    /** Windows 盘符式前缀，用于拒绝 "C:/..." 这类条目名。 */
    private val DRIVE_LETTER_PREFIX = Regex("^[A-Za-z]:")

    /**
     * 主题 ZIP 被限额或路径校验拒绝时抛出的内部异常。
     *
     * 单独一个类型，是为了让"UTF-8 → GBK → Big5 各试一遍"的回退逻辑认出：
     * 这跟编码无关，换编码不可能成功，必须立刻放弃，而不是把炸弹再解析两遍。
     */
    private class ThemeArchiveRejectedException(message: String) : IllegalArgumentException(message)

    private val themeRootDir: File by lazy {
        File(appContext.getExternalFilesDir(null), "theme").also { it.mkdirs() }
    }

    private fun themeDir(): File {
        return themeRootDir
    }

    private fun themeFile(theme: Theme.Custom) = File(themeDir(), theme.name + ".json")

    fun newCustomBackgroundImages(): Triple<String, File, File> {
        val themeName = UUID.randomUUID().toString()
        val (croppedImageFile, srcImageFile) = newBackgroundImagesForTheme(themeName)
        return Triple(themeName, croppedImageFile, srcImageFile)
    }

    fun newBackgroundImagesForTheme(themeName: String): Pair<File, File> {
        val folder = File(themeDir(), safeThemePathComponent(themeName)).also { it.mkdirs() }
        val fileBase = safeThemePathComponent(themeName)
        val croppedImageFile = File(folder, "$fileBase-cropped.png")
        val srcImageFile = File(folder, "$fileBase-src")
        return croppedImageFile to srcImageFile
    }

    fun alignBackgroundAssetsWithThemeName(theme: Theme.Custom): Theme.Custom {
        val bg = theme.backgroundImage ?: return theme
        val appFilesDir = appContext.getExternalFilesDir(null) ?: return theme
        val themeDir = File(appFilesDir, "theme")
        val srcFile = resolveImagePath(bg.srcFilePath, appFilesDir, themeDir)
        val croppedFile = resolveImagePath(bg.croppedFilePath, appFilesDir, themeDir)

        val fileBase = safeThemePathComponent(theme.name)
        val targetDir = File(themeDir(), fileBase).also { it.mkdirs() }
        val srcExt = srcFile.extension.takeIf { it.isNotEmpty() }
        val targetSrc = File(targetDir, buildString {
            append(fileBase)
            append("-src")
            if (srcExt != null) {
                append('.')
                append(srcExt)
            }
        })
        val targetCropped = File(targetDir, "$fileBase-cropped.png")

        moveOrCopyFile(croppedFile, targetCropped)
        moveOrCopyFile(srcFile, targetSrc)
        cleanupEmptyParents(croppedFile.parentFile)
        cleanupEmptyParents(srcFile.parentFile)

        return theme.copy(
            backgroundImage = bg.copy(
                croppedFilePath = targetCropped.absolutePath,
                srcFilePath = targetSrc.absolutePath
            )
        )
    }

    private fun moveOrCopyFile(source: File, target: File) {
        if (source.absolutePath == target.absolutePath) return
        if (!source.exists()) return
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        source.delete()
    }

    private fun cleanupEmptyParents(start: File?) {
        val baseDir = themeDir()
        var current = start
        while (current != null && current != baseDir) {
            val files = current.listFiles()
            if (files != null && files.isEmpty()) {
                if (!current.delete()) break
            } else {
                break
            }
            current = current.parentFile
        }
    }

    private fun safeThemePathComponent(name: String): String {
        val trimmed = name.trim().ifEmpty { "theme" }
        return trimmed.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
    }

    fun saveThemeFiles(theme: Theme.Custom) {
        // Normalize background paths to portable relative form (relative to theme dir)
        // so the persisted JSON survives export/import across build variants with
        // different applicationIds.
        val normalized = theme.backgroundImage?.let {
            val baseDir = themeDir()
            theme.copy(
                backgroundImage = theme.backgroundImage.copy(
                    croppedFilePath = File(it.croppedFilePath).relativeToOrSelf(baseDir).path
                        .replace('\\', '/'),
                    srcFilePath = File(it.srcFilePath).relativeToOrSelf(baseDir).path
                        .replace('\\', '/')
                )
            )
        } ?: theme
        themeFile(theme).writeText(Json.encodeToString(CustomThemeSerializer, normalized))
    }

    fun deleteThemeFiles(theme: Theme.Custom, allThemes: List<Theme.Custom> = emptyList()) {
        val baseDir = themeDir()
        
        // Collect directories and files to process
        val dirsToCheck = mutableSetOf<File>()
        val filesToDelete = mutableSetOf<File>()
        
        theme.backgroundImage?.let {
            val croppedFile = File(it.croppedFilePath)
            val srcFile = File(it.srcFilePath)
            
            collectParentDirs(croppedFile, dirsToCheck)
            collectParentDirs(srcFile, dirsToCheck)
            
            // Only delete files if no other theme is using them
            if (!isFileInUse(it.croppedFilePath, allThemes)) {
                filesToDelete.add(croppedFile)
            }
            if (!isFileInUse(it.srcFilePath, allThemes)) {
                filesToDelete.add(srcFile)
            }
        }

        // Delete theme JSON file
        themeFile(theme).delete()
        
        // Delete image files not in use by other themes
        filesToDelete.forEach { it.delete() }

        // Cleanup empty directories from deepest to shallowest
        dirsToCheck.sortedByDescending { it.absolutePath.length }.forEach { dir ->
            cleanupEmptyDir(dir, allThemes, baseDir)
        }
    }
    
    /**
     * Check if a file path is used by any other theme.
     */
    private fun isFileInUse(filePath: String, allThemes: List<Theme.Custom>): Boolean {
        return allThemes.any { theme ->
            theme.backgroundImage?.let { bg ->
                bg.croppedFilePath == filePath || bg.srcFilePath == filePath
            } ?: false
        }
    }
    
    /**
     * Collect all parent directories from a file up to the base theme dir.
     */
    private fun collectParentDirs(file: File, dirs: MutableSet<File>) {
        var parent = file.parentFile
        while (parent != null) {
            dirs.add(parent)
            parent = parent.parentFile
        }
    }
    
    /**
     * Clean up an empty directory if no other theme is using files in it.
     * Recursively cleans up parent directories if they become empty.
     *
     * @param dir The directory to check and potentially delete
     * @param allThemes List of remaining themes to check for directory usage
     * @param baseDir The base theme directory - stop cleanup at this level
     */
    private fun cleanupEmptyDir(dir: File, allThemes: List<Theme.Custom>, baseDir: File) {
        // Don't delete the base theme directory itself
        if (dir.absolutePath == baseDir.absolutePath) return

        // Check if directory exists and is empty
        if (!dir.exists() || !dir.isDirectory) return
        val remainingFiles = dir.listFiles()
        if (remainingFiles?.isNotEmpty() == true) return  // Directory not empty, skip

        // Check if any other theme is using files in this directory or its subdirectories
        val isDirInUse = allThemes.any { theme ->
            theme.backgroundImage?.let { bg ->
                bg.croppedFilePath.startsWith(dir.absolutePath) ||
                bg.srcFilePath.startsWith(dir.absolutePath)
            } ?: false
        }

        // Delete directory if not in use, then recursively check parent
        if (!isDirInUse && dir.delete()) {
            cleanupEmptyDir(dir.parentFile ?: return, allThemes, baseDir)
        }
    }

    fun listThemes(): MutableList<Theme.Custom> {
        val dir = themeDir()
        val files = dir.listFiles(FileFilter { it.extension == "json" }) ?: return mutableListOf()
        return files
            .sortedByDescending { it.lastModified() } // newest first
            .mapNotNull decode@{
                val raw = it.readText()
                // Normalize paths to this app's external files dir
                // Replace any package name with current app's package name
                val normalized = raw.replace(
                    Regex("""/Android/data/[^/]+/files"""),
                    "/Android/data/${appContext.packageName}/files"
                )
                val (theme, migratedFromSerializer) = runCatching {
                    Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, normalized)
                }.getOrElse { e ->
                    Timber.w("Failed to decode theme file ${it.absolutePath}: ${e.message}")
                    return@decode null
                }

                // Resolve relative paths to absolute paths
                val resolvedTheme = if (theme.backgroundImage != null) {
                    val appFilesDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
                    val baseDir = themeDir()
                    theme.copy(
                        backgroundImage = theme.backgroundImage.copy(
                            croppedFilePath = resolveImagePath(
                                theme.backgroundImage.croppedFilePath,
                                appFilesDir,
                                baseDir
                            ).absolutePath,
                            srcFilePath = resolveImagePath(
                                theme.backgroundImage.srcFilePath,
                                appFilesDir,
                                baseDir
                            ).absolutePath
                        )
                    )
                } else {
                    theme
                }

                // If we changed the JSON text (normalized) or the serializer reported migration, persist the corrected JSON
                if (normalized != raw || migratedFromSerializer) {
                    saveThemeFiles(resolvedTheme)
                }

                if (resolvedTheme.backgroundImage != null) {
                    if (!File(resolvedTheme.backgroundImage.croppedFilePath).exists() ||
                        !File(resolvedTheme.backgroundImage.srcFilePath).exists()
                    ) {
                        return@decode null
                    }
                }

                return@decode resolvedTheme
            }.toMutableList()
    }

    /**
     * [dest] will be closed on finished
     */
    fun exportTheme(theme: Theme.Custom, dest: OutputStream) =
        runCatching {
            ZipOutputStream(dest.buffered()).use { zipStream ->
                // we don't export the internal path of images
                val tweakedTheme = theme.backgroundImage?.let {
                    theme.copy(
                        backgroundImage = theme.backgroundImage.copy(
                            croppedFilePath = theme.backgroundImage.croppedFilePath
                                .substringAfterLast('/'),
                            srcFilePath = theme.backgroundImage.srcFilePath
                                .substringAfterLast('/'),
                        )
                    )
                } ?: theme
                if (tweakedTheme.backgroundImage != null) {
                    requireNotNull(theme.backgroundImage)
                    // write cropped image
                    zipStream.putNextEntry(ZipEntry(tweakedTheme.backgroundImage.croppedFilePath))
                    File(theme.backgroundImage.croppedFilePath).inputStream()
                        .use { it.copyTo(zipStream) }
                    // write src image
                    zipStream.putNextEntry(ZipEntry(tweakedTheme.backgroundImage.srcFilePath))
                    File(theme.backgroundImage.srcFilePath).inputStream()
                        .use { it.copyTo(zipStream) }
                }
                // write json
                zipStream.putNextEntry(ZipEntry("${tweakedTheme.name}.json"))
                zipStream.write(
                    Json.encodeToString(CustomThemeSerializer, tweakedTheme)
                        .encodeToByteArray()
                )
                // done
                zipStream.closeEntry()
            }
        }

    /**
     * Resolve image path from JSON to absolute file path.
     * Handles both absolute paths and relative paths.
     *
     * Examples:
     * - Absolute: /Android/data/org.fcitx.fcitx5.android/files/theme/xxx.png → appFilesDir/theme/xxx.png
     * - Relative: theme/xxx.png → appFilesDir/theme/xxx.png
     * - Relative: ./xxx.png → appFilesDir/theme/xxx.png
     * - Relative: xxx.png → appFilesDir/theme/xxx.png
     */
    private fun resolveImagePath(jsonPath: String, appFilesDir: File, themeDir: File): File {
        // If already an absolute path in current app, use it directly
        if (jsonPath.startsWith(appFilesDir.absolutePath)) {
            return File(jsonPath)
        }
        
        // Handle /Android/data/[package]/files/... paths (from other app installations)
        if (jsonPath.startsWith("/Android/data/") || jsonPath.startsWith("/data/data/")) {
            val rel = jsonPath.substringAfter("/files/").trimStart('/')
            return File(appFilesDir, rel)
        }
        
        // Handle relative paths
        // Remove leading ./ if present
        val cleanPath = jsonPath.removePrefix("./")
        
        // If path starts with "theme/", resolve relative to appFilesDir
        if (cleanPath.startsWith("theme/")) {
            return File(appFilesDir, cleanPath)
        }
        
        // Otherwise, assume it's relative to theme directory
        return File(themeDir, cleanPath)
    }

    /**
     * @return (newCreated, theme, migrated)
     */
    fun importTheme(src: InputStream, importedName: String? = null): Result<Triple<Boolean, Theme.Custom, Boolean>> =
        runCatching {
            // 三种编码要把同一份归档完整解析三遍，所以先有界地读进内存一次（原来是无界
            // readBytes()，超大 zip 会直接 OOM）。
            val zipBytes = src.readAtMost(MAX_IMPORT_ARCHIVE_BYTES) { rejectArchiveTooLarge() }
            // Try importing with different ZIP encodings (UTF-8, GBK, Big5)
            // This handles ZIP files created on Windows with non-UTF-8 encodings
            val encodings = listOf("UTF-8", "GBK", "Big5")
            for (encoding in encodings) {
                try {
                    return@runCatching importThemeWithEncoding(zipBytes.inputStream(), encoding, importedName)
                } catch (e: ThemeArchiveRejectedException) {
                    // 限额/路径校验失败与编码无关，再换编码也只是把同一个归档重放一遍。
                    throw e
                } catch (e: Exception) {
                    // Try next encoding
                }
            }
            
            // All encodings failed
            errorRuntime(R.string.exception_theme_src_image)
        }

    fun decodeTheme(src: InputStream): Result<Theme.Custom> =
        runCatching {
            val zipBytes = src.readAtMost(MAX_IMPORT_ARCHIVE_BYTES) { rejectArchiveTooLarge() }
            val encodings = listOf("UTF-8", "GBK", "Big5")
            for (encoding in encodings) {
                try {
                    return@runCatching decodeThemeWithEncoding(zipBytes.inputStream(), encoding)
                } catch (e: ThemeArchiveRejectedException) {
                    // 同 importTheme。
                    throw e
                } catch (e: Exception) {
                    // Try next encoding
                }
            }
            errorRuntime(R.string.exception_theme_json)
        }

    private fun decodeThemeWithEncoding(src: InputStream, encoding: String): Theme.Custom {
        return ZipInputStream(src, Charset.forName(encoding)).use { zipStream ->
            var entry = zipStream.nextEntry
            var entryCount = 0
            var totalBytes = 0L
            while (entry != null) {
                if (!entry.isDirectory) {
                    entryCount++
                    if (entryCount > MAX_IMPORT_ENTRIES) rejectTooManyEntries()
                }
                if (!entry.isDirectory && entry.name.endsWith(".json")) {
                    // 取成 val 再进 lambda：entry 是可变的局部变量（循环末尾会重新赋值），
                    // 直接把 entry 捕获进闭包会丢掉非空智能转换。
                    val entryName = entry.name
                    // 改成有界读取：一个声称 100MB 的 JSON 条目过去会被整份读进堆。
                    // 不用 entry.size 预判——ZipInputStream 的流式头不填 size（实测恒为 -1）。
                    val rawJson = zipStream.readAtMost(MAX_IMPORT_JSON_BYTES) {
                        rejectEntryTooLarge(entryName, MAX_IMPORT_JSON_BYTES.toLong())
                    }.toString(Charsets.UTF_8)
                    val normalizedJson = rawJson.replace(
                        Regex("""/Android/data/[^/]+/files"""),
                        "/Android/data/${appContext.packageName}/files"
                    )
                    val (theme, _) = Json.decodeFromString(
                        CustomThemeSerializer.WithMigrationStatus,
                        normalizedJson
                    )
                    return theme
                }
                // 这条路径不需要图片内容，但必须把条目读完 nextEntry 才会推进；用固定缓冲区
                // 丢弃并计总量，解压炸弹就不会靠 CPU/磁盘把这台设备拖垮（与解压路径同一套限额）。
                if (!entry.isDirectory) {
                    totalBytes += zipStream.discardAtMost(MAX_IMPORT_ENTRY_BYTES, entry.name)
                    if (totalBytes > MAX_IMPORT_TOTAL_BYTES) rejectArchiveTooLarge(MAX_IMPORT_TOTAL_BYTES)
                }
                entry = zipStream.nextEntry
            }
            errorRuntime(R.string.exception_theme_json)
        }
    }

    /**
     * 有界读取 [limit] 字节；超限时调用 [reject] 抛出对应的本地化错误。
     *
     * 与 [IconThemeManager] 的 `readAtMost` 同一套做法，只是错误文案由调用方决定，
     * 保证用户看到的是翻译过的文案而不是裸英文异常。
     */
    private inline fun InputStream.readAtMost(limit: Int, reject: () -> Nothing): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read <= 0) break
            total += read
            if (total > limit) reject()
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /**
     * 把一个条目解压到 [target]，同时受单条目 [perEntryLimit] 与剩余总量预算约束；
     * 返回实际写入的字节数。
     *
     * 用固定缓冲区，所以无论条目声称多大，峰值内存都是常量；超限直接失败，
     * 而不是把 cache 目录写满。超的是哪一条限额就报哪一条的本地化文案。
     */
    private fun InputStream.copyAtMostTo(
        target: File,
        perEntryLimit: Long,
        remainingTotal: Long,
        entryName: String
    ): Long {
        if (remainingTotal <= 0) rejectArchiveTooLarge(MAX_IMPORT_TOTAL_BYTES)
        val limit = minOf(perEntryLimit, remainingTotal)
        val overTotal = remainingTotal < perEntryLimit
        var written = 0L
        target.outputStream().use { output ->
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = read(chunk)
                if (read <= 0) break
                written += read
                if (written > limit) {
                    if (overTotal) {
                        rejectArchiveTooLarge(MAX_IMPORT_TOTAL_BYTES)
                    } else {
                        rejectEntryTooLarge(entryName, perEntryLimit)
                    }
                }
                output.write(chunk, 0, read)
            }
        }
        return written
    }

    /**
     * 把 ZIP 条目名解析成 [tempDir] 内的文件，拒绝任何逃逸出临时目录的条目。
     *
     * 原来是 `File(tempDir, entry.name)`：`entry.name` 直接来自归档，`/abs/path`、`C:\...`
     * 或 `../../x` 都能落到 tempDir 之外（路径穿越）。这里既做名字层面的快速拒绝
     * （换编码不可能改变结果，所以在回退循环里会直接失败），也在解压前用 canonical path
     * 复核一次，确保解析结果确实在 tempDir 之内。
     */
    private fun resolveEntryTarget(tempDir: File, entryName: String): File {
        val normalized = entryName.replace('\\', '/')
        val isUnsafe = normalized.isEmpty() ||
            normalized.startsWith("/") ||
            DRIVE_LETTER_PREFIX.containsMatchIn(normalized) ||
            normalized.split('/').any { it == ".." }
        if (isUnsafe) rejectUnsafeEntryPath(entryName)
        val target = File(tempDir, entryName)
        val canonicalRoot = tempDir.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (canonicalTarget != canonicalRoot &&
            !canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            rejectUnsafeEntryPath(entryName)
        }
        return target
    }

    /** 丢弃一个条目的内容，最多 [limit] 字节，超限即失败；返回实际丢弃的字节数。 */
    private fun InputStream.discardAtMost(limit: Long, entryName: String): Long {
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(chunk)
            if (read <= 0) break
            total += read
            if (total > limit) rejectEntryTooLarge(entryName, limit)
        }
        return total
    }

    /** 归档读入内存的字节数超出限额（[MAX_IMPORT_ARCHIVE_BYTES] 或解压总量 [MAX_IMPORT_TOTAL_BYTES]）。 */
    private fun rejectArchiveTooLarge(limitBytes: Long = MAX_IMPORT_ARCHIVE_BYTES.toLong()): Nothing =
        throw ThemeArchiveRejectedException(
            appContext.getString(
                R.string.exception_theme_zip_too_large,
                limitBytes / (1024 * 1024)
            )
        )

    private fun rejectEntryTooLarge(
        entryName: String,
        limitBytes: Long = MAX_IMPORT_ENTRY_BYTES
    ): Nothing = throw ThemeArchiveRejectedException(
        appContext.getString(
            R.string.exception_theme_zip_entry_too_large,
            entryName.take(64),
            limitBytes / (1024 * 1024)
        )
    )

    private fun rejectTooManyEntries(): Nothing = throw ThemeArchiveRejectedException(
        appContext.getString(R.string.exception_theme_zip_too_many_entries, MAX_IMPORT_ENTRIES)
    )

    private fun rejectUnsafeEntryPath(entryName: String): Nothing = throw ThemeArchiveRejectedException(
        appContext.getString(R.string.exception_theme_zip_unsafe_entry, entryName.take(64))
    )
    
    /**
     * Import theme with specific ZIP entry encoding.
     * @param encoding Character encoding for ZIP entry names
     */
    private fun importThemeWithEncoding(
        src: InputStream,
        encoding: String?,
        importedName: String?
    ): Triple<Boolean, Theme.Custom, Boolean> {
        val charset = encoding?.let { Charset.forName(it) }
        return ZipInputStream(src, charset).use { zipStream ->
            withTempDir { tempDir ->
                // Extract all files and keep track of their paths
                val extractedPaths = mutableMapOf<String, File>()
                var jsonFile: File? = null

                var entry = zipStream.nextEntry
                var entryCount = 0
                var totalBytes = 0L
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // 条目数/解压总量与单条目上限：原来 copyTo 是无界的，一个 zip 炸弹
                        // 可以把 cache 目录写满（见任务 C）。
                        entryCount++
                        if (entryCount > MAX_IMPORT_ENTRIES) rejectTooManyEntries()
                        val file = resolveEntryTarget(tempDir, entry.name)
                        file.parentFile?.mkdirs()
                        val written = zipStream.copyAtMostTo(
                            target = file,
                            perEntryLimit = MAX_IMPORT_ENTRY_BYTES,
                            remainingTotal = MAX_IMPORT_TOTAL_BYTES - totalBytes,
                            entryName = entry.name
                        )
                        totalBytes += written
                        extractedPaths[entry.name] = file
                        if (entry.name.endsWith(".json")) {
                            jsonFile = file
                        }
                    }
                    entry = zipStream.nextEntry
                }
                jsonFile ?: errorRuntime(R.string.exception_theme_json)
                val rawJson = jsonFile.readText()
                // Normalize paths to current app's external files dir (replace package name)
                val normalizedJson = rawJson.replace(
                    Regex("""/Android/data/[^/]+/files"""),
                    "/Android/data/${appContext.packageName}/files"
                )
                val (decoded, migrated) = Json.decodeFromString(
                    CustomThemeSerializer.WithMigrationStatus,
                    normalizedJson
                )
                val importedThemeName = importedName ?: ThemeManager.nonActiveImportName(decoded.name)
                if (ThemeManager.BuiltinThemes.find { it.name == importedThemeName } != null)
                    errorRuntime(R.string.exception_theme_name_clash)
                val oldTheme = ThemeManager.getTheme(importedThemeName) as? Theme.Custom
                val newCreated = oldTheme == null
                val theme = decoded.copy(name = importedThemeName)
                val newTheme = if (decoded.backgroundImage != null) {
                    val appFilesDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
                    val baseDir = themeDir()

                    // Resolve target paths: handle both absolute and relative paths
                    val (croppedTarget, srcTarget) = if (importedName == null) {
                        resolveImagePath(
                            decoded.backgroundImage.croppedFilePath,
                            appFilesDir,
                            baseDir
                        ) to resolveImagePath(
                            decoded.backgroundImage.srcFilePath,
                            appFilesDir,
                            baseDir
                        )
                    } else {
                        newBackgroundImagesForTheme(importedThemeName)
                    }

                    srcTarget.parentFile?.mkdirs()
                    croppedTarget.parentFile?.mkdirs()

                    val oldSrcFile = oldTheme?.backgroundImage?.srcFilePath?.let { File(it) }
                    val srcFileNameMatches = oldSrcFile?.name == srcTarget.name
                    val srcFileNameInZip = File(decoded.backgroundImage.srcFilePath).name

                    // Find source file by filename (handles ZIP encoding differences)
                    val srcFileInZip = extractedPaths.values.find { it.name == srcFileNameInZip }

                    srcFileInZip?.let {
                        it.copyTo(srcTarget, overwrite = srcFileNameMatches)
                    } ?: errorRuntime(R.string.exception_theme_src_image)

                    val oldCroppedFile = oldTheme?.backgroundImage?.croppedFilePath?.let { File(it) }
                    val croppedFileNameMatches = oldCroppedFile?.name == croppedTarget.name
                    val croppedFileNameInZip = File(decoded.backgroundImage.croppedFilePath).name

                    // Find cropped file by filename
                    val croppedFileInZip = extractedPaths.values.find { it.name == croppedFileNameInZip }

                    croppedFileInZip?.let {
                        it.copyTo(croppedTarget, overwrite = croppedFileNameMatches)
                    } ?: errorRuntime(R.string.exception_theme_cropped_image)

                    if (!srcFileNameMatches) {
                        oldSrcFile?.delete()
                    }
                    if (!croppedFileNameMatches) {
                        oldCroppedFile?.delete()
                    }

                    // Save theme with absolute paths; saveThemeFiles normalizes to
                    // portable relative form so the JSON survives cross-variant import.
                    theme.copy(
                        backgroundImage = decoded.backgroundImage.copy(
                            croppedFilePath = croppedTarget.path,
                            srcFilePath = srcTarget.path
                        )
                    )
                } else {
                    theme
                }
                saveThemeFiles(newTheme)
                Triple(newCreated, newTheme, migrated)
            }
        }
    }

}
