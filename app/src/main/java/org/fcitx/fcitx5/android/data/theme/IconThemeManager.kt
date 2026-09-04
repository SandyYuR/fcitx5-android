/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.input.config.ButtonIconFile
import org.fcitx.fcitx5.android.utils.FileNames
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser

object IconThemeManager {
    data class IconDrawableInfo(
        val drawable: Drawable,
        val tintWithTheme: Boolean
    )

    fun interface OnIconThemeChangeListener {
        fun onIconThemeChange(theme: IconTheme)
    }

    fun interface OnIconThemeListChangeListener {
        fun onIconThemeListChange(themes: List<IconTheme>)
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Limits for icon-theme ZIP import (see A6).
     *
     * An icon theme is a JSON document plus a handful of small images. These bounds are generous
     * for a real theme and keep a zip bomb from turning into an OutOfMemoryError, which no
     * `catch (Exception)` in the import path could contain.
     */
    private const val MAX_IMPORT_ARCHIVE_BYTES = 16 * 1024 * 1024
    private const val MAX_IMPORT_JSON_BYTES = 1024 * 1024
    private const val MAX_IMPORT_ENTRY_BYTES = 4L * 1024 * 1024
    private const val MAX_IMPORT_TOTAL_BYTES = 32L * 1024 * 1024
    private const val MAX_IMPORT_ENTRIES = 256

    private val themeDir: File? by lazy {
        val extDir = appContext.getExternalFilesDir(null)
        if (extDir != null) File(extDir, "icon_themes").also { it.mkdirs() } else null
    }

    private val builtinDefault: IconTheme = IconTheme.default()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val onChangeListeners = WeakHashSet<OnIconThemeChangeListener>()
    private val onListChangeListeners = WeakHashSet<OnIconThemeListChangeListener>()

    /** Backing list of all installed icon themes (loaded from disk). */
    @Volatile
    private var installedThemes: List<IconTheme> = emptyList()

    private val themeLock = Any()

    /** Drawable cache for SVG icons, keyed by "slot:themeName". */
    private val drawableCache = object : LruCache<String, IconDrawableInfo>(16 * 1024) {
        override fun sizeOf(key: String, value: IconDrawableInfo): Int {
            val w = value.drawable.intrinsicWidth.coerceAtLeast(1)
            val h = value.drawable.intrinsicHeight.coerceAtLeast(1)
            return w * h * 4 / 1024
        }
    }

    /**
     * The currently active icon theme. Defaults to builtin (all slots empty).
     *
     * Volatile because [initAsync] assigns it from an IO coroutine while the main thread reads
     * it on every icon lookup.
     */
    @Volatile
    var activeTheme: IconTheme = builtinDefault
        set(value) {
            if (field == value) return
            field = value
            activeThemeName = value.name
            clearDrawableCache()
            dispatchOnMain { onChangeListeners.toList().forEach { it.onIconThemeChange(value) } }
        }

    /** Store/restore active theme name in device-protected SharedPreferences. */
    private var activeThemeName: String
        get() {
            val dpc = FcitxApplication.getInstance().directBootAwareContext
            val prefs = dpc.getSharedPreferences("icon_theme", 0)
            val saved = prefs.getString("active_icon_theme", null)
            if (saved != null) return saved

            // Migrate from credential-encrypted storage (pre-existing installs)
            val migrated = runCatching {
                appContext.getSharedPreferences("icon_theme", 0)
                    .getString("active_icon_theme", builtinDefault.name)
            }.getOrDefault(builtinDefault.name) ?: builtinDefault.name

            if (migrated != builtinDefault.name) {
                prefs.edit().putString("active_icon_theme", migrated).apply()
            }
            return migrated
        }
        set(value) {
            FcitxApplication.getInstance().directBootAwareContext
                .getSharedPreferences("icon_theme", 0)
                .edit()
                .putString("active_icon_theme", value)
                .apply()
        }

    /** All installed icon themes (including builtin default at index 0). */
    val iconThemes: List<IconTheme>
        get() = listOf(builtinDefault) + installedThemes

    private val restorePending = AtomicBoolean(false)

    /** Background scope for disk work; icon themes are process-scoped state. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards against a second scan when several entry points call [initAsync]. */
    private val initStarted = AtomicBoolean(false)

    /**
     * Load installed icon themes off the main thread.
     *
     * This used to run in `init { refresh() }`, so merely *touching* the object from the main
     * thread (first keyboard show, via IdleUi / ButtonsBarUi) did listFiles + readText + JSON
     * parsing + SVG parsing for every icon, and could write thumbnail backfills to disk. Until
     * this completes, resolveIcon* fall back to the builtin default, which resolves to no
     * custom icon rather than a wrong one.
     */
    fun initAsync() {
        if (!initStarted.compareAndSet(false, true)) return
        scope.launch {
            refresh()
            restoreActiveTheme()
            if (restorePending.get()) {
                dispatchOnMain { registerUserUnlockReceiver() }
            }
            val themes = iconThemes
            dispatchOnMain { onListChangeListeners.toList().forEach { it.onIconThemeListChange(themes) } }
        }
    }

    private fun restoreActiveTheme() {
        val savedName = runCatching { activeThemeName }.getOrElse {
            restorePending.set(true)
            return
        }
        val saved = installedThemes.find { it.name == savedName }
        if (saved != null && saved !== builtinDefault) {
            activeTheme = saved
        }
    }

    private fun registerUserUnlockReceiver() {
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        appContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                context.unregisterReceiver(this)
                // onReceive is on the main thread; restoreActiveTheme reads SharedPreferences.
                scope.launch { restoreActiveTheme() }
            }
        }, filter)
    }

    fun resolveIcon(slot: String): String? {
        val value = activeTheme.icons[slot]
        return if (value.isNullOrEmpty()) null else value
    }

    fun buildThemeThumbnailSvg(icons: Map<String, String>): String? {
        val svgBySlot = icons.filterValues { isInlineSvg(it) }
        if (svgBySlot.isEmpty()) return null

        val preferredSlots = IconTheme.ALL_SLOTS.filter { svgBySlot.containsKey(it) }
        val chosenSlots = preferredSlots.take(4)
        if (chosenSlots.isEmpty()) return null

        val tileSize = 24
        val canvasSize = tileSize * 2
        val cells = listOf(
            0 to 0,
            tileSize to 0,
            0 to tileSize,
            tileSize to tileSize
        )

        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\"0 0 $canvasSize $canvasSize\">")
            chosenSlots.forEachIndexed { index, slot ->
                val raw = svgBySlot[slot] ?: return@forEachIndexed
                val parts = extractSvgParts(raw) ?: return@forEachIndexed
                val (x, y) = cells[index]
                append("<svg x=\"$x\" y=\"$y\" width=\"$tileSize\" height=\"$tileSize\" viewBox=\"${parts.viewBox}\">")
                append(parts.innerContent)
                append("</svg>")
            }
            append("</svg>")
        }.takeIf { it.contains("<svg x=") }
    }

    /**
     * Resolve an icon slot to a Drawable, with caching.
     * Returns null if the slot has no custom icon, or if it's emoji/text.
     */
    fun resolveIconDrawable(slot: String): Drawable? {
        return resolveIconDrawableInfo(slot)?.drawable
    }

    fun resolveIconDrawableInfo(slot: String): IconDrawableInfo? {
        val value = activeTheme.icons[slot] ?: return null
        val key = "$slot:${activeTheme.name}"
        drawableCache.get(key)?.let { cached ->
            return IconDrawableInfo(
                drawable = cloneDrawable(cached.drawable),
                tintWithTheme = cached.tintWithTheme
            )
        }
        val isFileIcon = ButtonIconFile.isFileIcon(value)
        val isSvg = isInlineSvg(value)
        val loaded: Drawable = (if (isFileIcon) {
            ButtonIconFile.loadDrawable(value)?.let { normalizedDrawable(it) }
        } else if (isSvg) {
            loadSvgDrawable(value)
        } else {
            loadInlineDrawableXml(value)
        }) ?: return null
        val info = IconDrawableInfo(
            drawable = loaded,
            tintWithTheme = when {
                isFileIcon -> ButtonIconFile.shouldTintIcon(value)
                isSvg -> isSvgMonochrome(value)
                else -> true
            }
        )
        drawableCache.put(key, info)
        return IconDrawableInfo(
            drawable = cloneDrawable(info.drawable),
            tintWithTheme = info.tintWithTheme
        )
    }

    private fun normalizeSvgContent(raw: String): String {
        val withoutBom = raw.removePrefix("\uFEFF")
        val withoutXmlDecl = withoutBom.replaceFirst(Regex("^\\s*<\\?xml[^>]*\\?>\\s*"), "")
        val withoutLeadingComments = withoutXmlDecl.replaceFirst(
            Regex("^(\\s*<!--.*?-->\\s*)+", setOf(RegexOption.DOT_MATCHES_ALL)),
            ""
        )
        val svgStart = Regex("<svg\\b", RegexOption.IGNORE_CASE).find(withoutLeadingComments)?.range?.first
        val content = if (svgStart != null) {
            withoutLeadingComments.substring(svgStart)
        } else {
            withoutLeadingComments
        }
        val openingTagRegex = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)
        val openingTag = openingTagRegex.find(content)?.value ?: return content
        val normalizedOpening = openingTag
            .replace(Regex("\\swidth\\s*=\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\sheight\\s*=\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\swidth\\s*=\\s*'[^']*'", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\sheight\\s*=\\s*'[^']*'", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\swidth\\s*=\\s*[^\\s>]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\sheight\\s*=\\s*[^\\s>]+", RegexOption.IGNORE_CASE), "")
        var normalized = content.replaceFirst(openingTag, normalizedOpening)
        if (normalized.contains("xlink:") && !normalized.contains("xmlns:xlink")) {
            normalized = normalized.replaceFirst(
                Regex("<svg\\b([^>]*)>", RegexOption.IGNORE_CASE),
                "<svg$1 xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
            )
        }
        return normalized
    }

    private fun detectXmlRootTag(raw: String): String? {
        val clean = normalizeXmlContent(raw)
        val match = Regex("^<\\s*([A-Za-z_][A-Za-z0-9_\\-.:]*)\\b").find(clean) ?: return null
        return match.groupValues[1].substringAfterLast(':')
    }

    /** True if the value is an inline SVG (handles xml decl/comments/BOM prefixes). */
    fun isInlineSvg(value: String): Boolean = try {
        val normalized = normalizeSvgContent(value)
        if (!Regex("^\\s*<svg\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) {
            return false
        }
        svgCandidates(value).any { com.caverock.androidsvg.SVG.getFromString(it) != null }
    } catch (_: Exception) { false }

    fun shouldTintInlineSvg(value: String): Boolean {
        if (!isInlineSvg(value)) return false
        return isSvgMonochrome(value)
    }

    private fun normalizeXmlContent(raw: String): String {
        return raw
            .removePrefix("\uFEFF")
            .replaceFirst(
                Regex(
                    "^\\s*(?:<\\?xml\\b[^>]*\\?>|<!DOCTYPE\\b[^>]*(?:\\[[^\\]]*\\])?[^>]*>|<!--.*?-->)\\s*",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ),
                ""
            )
            .replaceFirst(
                Regex("^(\\s*<!--.*?-->\\s*)+", setOf(RegexOption.DOT_MATCHES_ALL)),
                ""
            )
            .trimStart()
    }

    fun isInlineDrawableXml(value: String): Boolean {
        val clean = normalizeXmlContent(value)
        if (!clean.startsWith("<")) return false
        if (detectXmlRootTag(clean).equals("svg", ignoreCase = true)) return false
        return loadInlineDrawableXml(clean) != null
    }

    fun loadInlineDrawableXml(xmlContent: String): Drawable? {
        val clean = normalizeXmlContent(xmlContent)
        return ButtonIconFile.loadInlineXmlDrawable(clean)
    }

    private fun loadSvgDrawable(svgContent: String): Drawable? {
        val density = appContext.resources.displayMetrics.density
        val size = (24 * density).toInt().coerceAtLeast(1)
        for (candidate in svgCandidates(svgContent)) {
            try {
                val svg = com.caverock.androidsvg.SVG.getFromString(candidate)
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                svg.renderToCanvas(canvas, android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()))
                return BitmapDrawable(appContext.resources, bitmap)
            } catch (_: Exception) {
                // Try the original SVG when normalization changed unsupported markup.
            }
        }
        return null
    }

    private fun svgCandidates(raw: String): List<String> =
        listOf(normalizeSvgContent(raw), raw.trim()).distinct()

    /** Scale an oversized BitmapDrawable down to the standard icon size (24dp)
     *  so PNG/SVG file icons don't overflow key/toolbar button boundaries. */
    internal fun normalizedDrawable(drawable: Drawable): Drawable {
        if (drawable !is BitmapDrawable) return drawable
        val bitmap = drawable.bitmap
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val density = appContext.resources.displayMetrics.density
        val targetSize = (24 * density).toInt()
        if (w <= targetSize && h <= targetSize) return drawable
        val scale = minOf(targetSize.toFloat() / w, targetSize.toFloat() / h)
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true
        )
        return BitmapDrawable(appContext.resources, scaled)
    }

    private fun cloneDrawable(drawable: Drawable): Drawable {
        val cloned = drawable.constantState?.newDrawable(appContext.resources) ?: drawable
        return cloned.mutate()
    }

    private data class SvgParts(
        val viewBox: String,
        val innerContent: String
    )

    private fun extractSvgParts(svgContent: String): SvgParts? {
        val clean = normalizeSvgContent(svgContent)
        val openingMatch = Regex("<svg\\b([^>]*)>", RegexOption.IGNORE_CASE).find(clean) ?: return null
        val attrs = openingMatch.groupValues[1]
        val viewBox = Regex("viewBox\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(attrs)
            ?.groupValues
            ?.get(1)
            ?: "0 0 24 24"
        val closeIndex = clean.lastIndexOf("</svg>", ignoreCase = true)
        if (closeIndex <= openingMatch.range.last) return null
        val inner = clean.substring(openingMatch.range.last + 1, closeIndex).trim()
        if (inner.isEmpty()) return null
        return SvgParts(viewBox = viewBox, innerContent = inner)
    }

    private fun isSvgMonochrome(svgContent: String): Boolean {
        val clean = normalizeSvgContent(svgContent)
        return try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(ByteArrayInputStream(clean.toByteArray()), null)
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val fill = parseSvgColor(getAttr(parser, "fill"))
                    if (fill != null && !isGray(fill)) return false
                    val stroke = parseSvgColor(getAttr(parser, "stroke"))
                    if (stroke != null && !isGray(stroke)) return false
                }
                event = parser.next()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getAttr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == name) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun parseSvgColor(value: String?): Int? {
        if (value.isNullOrBlank() || value.equals("none", ignoreCase = true)) return null
        return runCatching { Color.parseColor(value.trim()) }.getOrNull()
    }

    private fun isGray(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return r == g && g == b
    }

    private fun clearDrawableCache() {
        drawableCache.evictAll()
    }

    fun refresh() {
        val dir = themeDir ?: return
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        val themes = files.mapNotNull { f ->
            runCatching {
                val loaded = json.decodeFromString<IconTheme>(f.readText())
                val normalized = fillMissingThumbnail(loaded)
                if (normalized != loaded) {
                    runCatching { f.writeText(json.encodeToString(normalized)) }
                        .onFailure { Timber.w(it, "Failed to backfill thumbnail for icon theme: ${loaded.name}") }
                }
                normalized
            }.onFailure {
                Timber.w(it, "Failed to load icon theme: ${f.name}")
            }.getOrNull()
        }
        synchronized(themeLock) {
            installedThemes = themes
        }
        // Ensure active theme still exists
        val current = activeTheme
        if (current !== builtinDefault && themes.none { it.name == current.name }) {
            activeTheme = builtinDefault
        }
    }

    fun saveTheme(theme: IconTheme) {
        val normalizedTheme = fillMissingThumbnail(theme)
        val dir = themeDir ?: return
        val file = File(dir, "${normalizedTheme.name}.json")
        runCatching {
            file.writeText(json.encodeToString(normalizedTheme))
        }.onFailure {
            Timber.w(it, "Failed to save icon theme: ${normalizedTheme.name}")
            return
        }
        synchronized(themeLock) {
            val mutable = installedThemes.toMutableList()
            val idx = mutable.indexOfFirst { it.name == normalizedTheme.name }
            if (idx >= 0) {
                mutable[idx] = normalizedTheme
            } else {
                mutable.add(0, normalizedTheme)
            }
            installedThemes = mutable
        }
        if (activeTheme.name == normalizedTheme.name) {
            activeTheme = normalizedTheme
        }
        cleanupOrphanedPngs(normalizedTheme.name)
        dispatchOnMain { onListChangeListeners.toList().forEach { it.onIconThemeListChange(iconThemes) } }
    }

    fun deleteTheme(name: String) {
        synchronized(themeLock) {
            val mutable = installedThemes.toMutableList()
            val idx = mutable.indexOfFirst { it.name == name }
            if (idx < 0) return
            mutable.removeAt(idx)
            installedThemes = mutable
        }
        val dir = themeDir
        if (dir != null) File(dir, "$name.json").delete()
        if (activeTheme.name == name) {
            activeTheme = builtinDefault
        }
        cleanupOrphanedPngs(name)
        dispatchOnMain { onListChangeListeners.toList().forEach { it.onIconThemeListChange(iconThemes) } }
    }

    fun importTheme(jsonString: String): IconTheme {
        val theme = json.decodeFromString<IconTheme>(jsonString)
        saveTheme(theme)
        return theme
    }

    /** Directory under button_icons/ for a theme's PNG resources. */
    fun pngDirForTheme(name: String): File {
        val extDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        return File(extDir, "${ButtonIconFile.DIR}/${sanitizeFileName(name)}").also { it.mkdirs() }
    }

    private fun sanitizeFileName(name: String): String = FileNames.sanitize(name)

    /**
     * Safe file name for an icon resource coming from a content provider or an import.
     * See [FileNames.safeImageFileName]; null when the extension is not accepted.
     */
    fun safeIconFileName(rawName: String, fallbackBaseName: String = "image"): String? =
        FileNames.safeImageFileName(rawName, fallbackBaseName)

    /** Collect all `file:` references in a theme's icons map. */
    fun collectFileReferences(theme: IconTheme): Set<String> =
        theme.icons.values.filter(ButtonIconFile::isFileIcon).toSet()

    /** Collect all `file:` references across all installed themes. */
    private fun collectAllFileReferences(): Set<String> =
        installedThemes.flatMap { collectFileReferences(it) }.toSet()

    /**
     * Clean up PNG files in [themeName]'s directory that are NOT referenced
     * by any installed theme. Call after save or delete.
     */
    fun cleanupOrphanedPngs(themeName: String) {
        val dir = pngDirForTheme(themeName)
        if (!dir.isDirectory) return
        val used = collectAllFileReferences()
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val relativePath = "${ButtonIconFile.DIR}/${sanitizeFileName(themeName)}/${file.name}"
                if (ButtonIconFile.PREFIX + relativePath !in used) {
                    runCatching { file.delete() }
                        .onFailure { Timber.w(it, "Failed to delete unused PNG: $file") }
                }
            }
        }
        dir.list()?.takeIf { it.isEmpty() }?.let { runCatching { dir.delete() } }
    }

    /**
     * Export an icon theme and its PNG resources as a ZIP file.
     * The JSON is written with relative `file:` paths so the ZIP is portable.
     * [dest] will be closed on finished.
     */
    fun exportThemeToZip(theme: IconTheme, dest: java.io.OutputStream) =
        runCatching {
            val safeName = sanitizeFileName(theme.name)
            val fileRefs = collectFileReferences(theme)
            val extDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
            val btnIconsBase = File(extDir, ButtonIconFile.DIR)

            // Build normalized icons map with portable (relative) paths
            val portableIcons = theme.icons.mapValues { (_, value) ->
                if (ButtonIconFile.isFileIcon(value)) {
                    ButtonIconFile.PREFIX + value.removePrefix(ButtonIconFile.PREFIX)
                        .split("/").takeLast(2).joinToString("/")
                        .let { "${ButtonIconFile.DIR}/$it" }
                        .let { ButtonIconFile.PREFIX + it }
                } else value
            }

            ZipOutputStream(dest.buffered()).use { zip ->
                // Write JSON
                zip.putNextEntry(ZipEntry("$safeName.json"))
                val exportTheme = theme.copy(icons = portableIcons)
                val jsonBytes = json.encodeToString(exportTheme).toByteArray(Charsets.UTF_8)
                zip.write(jsonBytes)
                zip.closeEntry()

                // Write each referenced PNG
                fileRefs.forEach { ref ->
                    runCatching {
                        val raw = ref.removePrefix(ButtonIconFile.PREFIX)
                        val file = File(btnIconsBase, raw)
                        val file2 = File(extDir, raw)
                        val resolved = when {
                            file.isFile -> file
                            file2.isFile -> file2
                            else -> null
                        }
                        if (resolved != null) {
                            val entryName = ref.removePrefix(ButtonIconFile.PREFIX)
                                .split("/").takeLast(2).joinToString("/")
                                .let { "${ButtonIconFile.DIR}/$it" }
                            zip.putNextEntry(ZipEntry(entryName))
                            FileInputStream(resolved).use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }.onFailure { Timber.w(it, "Failed to pack PNG for export: $ref") }
                }
            }
        }

    /**
     * Import an icon theme from a ZIP input stream.
     * PNG resources are extracted to button_icons/<themeName>/.
     * @return the imported IconTheme
     */
    fun importThemeFromZip(src: java.io.InputStream, importedName: String? = null): Result<IconTheme> =
        runCatching {
            // Read the archive once, with a cap. src.readBytes() was unbounded: the sharing path
            // limits the *compressed* size to 16MB but nothing limited what came out of it, and
            // an OutOfMemoryError is an Error, so neither this runCatching nor the Activity's
            // catch(Exception) could contain it (see A6).
            val zipBytes = src.readAtMost(MAX_IMPORT_ARCHIVE_BYTES) {
                "Icon theme ZIP exceeds ${MAX_IMPORT_ARCHIVE_BYTES / (1024 * 1024)}MB"
            }
            val encodings = listOf("UTF-8", "GBK", "Big5")
            var lastFailure: Throwable? = null
            for (encoding in encodings) {
                try {
                    return@runCatching importThemeFromZipWithEncoding(zipBytes.inputStream(), encoding, importedName)
                } catch (t: Throwable) {
                    // Throwable, not Exception: a malformed archive can surface as an Error, and
                    // swallowing only Exception let those escape as a process crash (see A6).
                    lastFailure = t
                }
            }
            throw IllegalArgumentException(
                "Failed to decode icon theme ZIP with any encoding",
                lastFailure
            )
        }

    /**
     * Read at most [limit] bytes, failing instead of allocating without bound.
     */
    private inline fun InputStream.readAtMost(limit: Int, message: () -> String): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read <= 0) break
            total += read
            if (total > limit) throw IllegalArgumentException(message())
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /**
     * Copy this stream to [target], bounded by both a per-entry and a remaining-total budget.
     *
     * @return the number of bytes written
     */
    private fun InputStream.copyAtMostTo(
        target: File,
        perEntryLimit: Long,
        remainingTotal: Long
    ): Long {
        if (remainingTotal <= 0) {
            throw IllegalArgumentException(
                "Icon theme ZIP decompresses to more than ${MAX_IMPORT_TOTAL_BYTES / (1024 * 1024)}MB"
            )
        }
        val limit = minOf(perEntryLimit, remainingTotal)
        var written = 0L
        target.outputStream().use { output ->
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = read(chunk)
                if (read <= 0) break
                written += read
                if (written > limit) {
                    throw IllegalArgumentException("Icon theme ZIP entry exceeds the allowed size")
                }
                output.write(chunk, 0, read)
            }
        }
        return written
    }

    private fun importThemeFromZipWithEncoding(
        src: java.io.InputStream,
        encoding: String,
        importedName: String?
    ): IconTheme {
        val charset = Charset.forName(encoding)
        val extDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val btnIconsBase = File(extDir, ButtonIconFile.DIR)

        // Single streaming pass. Every entry used to be fully decompressed into a ByteArray and
        // accumulated in a list, so a zip bomb decompressed straight into OOM (see A6). Icon
        // files are now written to a staging directory as they are read, and three limits apply:
        // entry count, per-entry size and total decompressed size.
        var jsonText: String? = null
        val stagedIcons = mutableListOf<File>()
        val stagingDir = File(appContext.cacheDir, "icon_theme_import").apply {
            deleteRecursively()
            mkdirs()
        }
        var entryCount = 0
        var totalBytes = 0L
        try {
            ZipInputStream(src, charset).use { zipStream ->
                var entry = zipStream.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        if (++entryCount > MAX_IMPORT_ENTRIES) {
                            throw IllegalArgumentException("Icon theme ZIP has more than $MAX_IMPORT_ENTRIES entries")
                        }
                        if (entry.name.endsWith(".json")) {
                            val bytes = zipStream.readAtMost(MAX_IMPORT_JSON_BYTES) {
                                "Icon theme JSON entry exceeds ${MAX_IMPORT_JSON_BYTES / 1024}KB"
                            }
                            totalBytes += bytes.size
                            jsonText = bytes.toString(Charsets.UTF_8)
                        } else {
                            // File name only: an entry called "../../evil.png" must not escape
                            // the staging directory.
                            val fileName = FileNames.sanitize(File(entry.name).name)
                            if (fileName.isNotBlank()) {
                                val staged = File(stagingDir, fileName)
                                val written = zipStream.copyAtMostTo(
                                    staged,
                                    perEntryLimit = MAX_IMPORT_ENTRY_BYTES,
                                    remainingTotal = MAX_IMPORT_TOTAL_BYTES - totalBytes
                                )
                                totalBytes += written
                                stagedIcons.add(staged)
                            }
                        }
                    }
                    entry = zipStream.nextEntry
                }
            }
        } catch (t: Throwable) {
            stagingDir.deleteRecursively()
            throw t
        }

        val rawJson = jsonText ?: throw IllegalArgumentException("No JSON found in icon theme ZIP")

        // Normalize paths for cross-variant import
        val normalizedJson = rawJson.replace(
            Regex("""/Android/data/[^/]+/files"""),
            "/Android/data/${appContext.packageName}/files"
        )

        val theme = json.decodeFromString<IconTheme>(normalizedJson)
        val finalName = importedName ?: theme.name

        // Move the staged icons into the theme directory. They are already on disk, so this is a
        // rename (or a copy across filesystems) rather than another full-size allocation.
        val pngDir = pngDirForTheme(finalName).apply { mkdirs() }
        stagedIcons.forEach { staged ->
            runCatching {
                val targetFile = File(pngDir, staged.name)
                if (!staged.renameTo(targetFile)) {
                    staged.inputStream().use { input ->
                        targetFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }.onFailure { Timber.w(it, "Failed to write icon from ZIP: ${staged.name}") }
        }
        stagingDir.deleteRecursively()

        // Re-resolve file paths in the theme to point to the correct location
        val resolvedIcons = theme.icons.mapValues { (_, value) ->
            if (ButtonIconFile.isFileIcon(value)) {
                val fileName = value.removePrefix(ButtonIconFile.PREFIX).substringAfterLast('/')
                if (fileName.isNotBlank()) {
                    ButtonIconFile.PREFIX + ButtonIconFile.DIR + "/" + sanitizeFileName(finalName) + "/" + fileName
                } else value
            } else value
        }

        val finalTheme = theme.copy(name = finalName, icons = resolvedIcons)
        saveTheme(finalTheme)
        return finalTheme
    }

    @Synchronized
    fun addOnChangedListener(listener: OnIconThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    @Synchronized
    fun removeOnChangedListener(listener: OnIconThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    @Synchronized
    fun addOnListChangeListener(listener: OnIconThemeListChangeListener) {
        onListChangeListeners.add(listener)
    }

    @Synchronized
    fun removeOnListChangeListener(listener: OnIconThemeListChangeListener) {
        onListChangeListeners.remove(listener)
    }

    private inline fun dispatchOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private fun fillMissingThumbnail(theme: IconTheme): IconTheme {
        if (!theme.thumbnailSvg.isNullOrBlank()) return theme
        val generated = buildThemeThumbnailSvg(theme.icons)
        if (generated.isNullOrBlank()) return theme
        return theme.copy(thumbnailSvg = generated)
    }
}
