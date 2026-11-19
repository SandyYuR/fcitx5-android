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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.fcitx.fcitx5.android.utils.readBounded
import org.fcitx.fcitx5.android.utils.FileUtil
import org.fcitx.fcitx5.android.utils.compatibleZipInputStream
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

    /** 多色图标里算作「笔画黑」的通道上限：再深一点才替换成键面文字色，色点和浅色阴影不动。 */
    private const val NEAR_BLACK_MAX = 0x30

    private val themeDir: File? by lazy {
        val extDir = appContext.getExternalFilesDir(null)
        if (extDir != null) File(extDir, "icon_themes").also { it.mkdirs() } else null
    }

    private val builtinDefault: IconTheme = IconTheme.default()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val onChangeListeners = WeakHashSet<OnIconThemeChangeListener>()
    private val onListChangeListeners = WeakHashSet<OnIconThemeListChangeListener>()

    /** Backing list of all installed icon themes (loaded from disk). Writes hold [selectionLock]. */
    @Volatile
    private var installedThemes: List<IconTheme> = emptyList()

    /**
     * 解析后的 SVG 对象缓存，键是 "slot:themeName"。
     *
     * 光栅化尺寸变了（换键高、转屏、切分体）只需要重新渲染，不必再解析一遍 XML：
     * androidSVG 的解析是纯 Java 的 XML + 属性解析，26 个字母图标在首帧上是一笔实打实的
     * 主线程开销。切图标主题时与 [drawableCache] 一起清空。
     */
    private val svgCache = object : LruCache<String, CachedSvg>(64) {
        override fun sizeOf(key: String, value: CachedSvg): Int = 1
    }

    /**
     * 缓存条目连同它对应的源 SVG 一起存：缓存键只有「槽位:主题名」，同名主题可以被保存
     * 覆盖，命中时必须确认内容身份一致，否则会渲染上一次保存的图标。
     */
    private class CachedSvg(val content: String, val svg: com.caverock.androidsvg.SVG)

    /**
     * SVG 解析缓存的同步边界。写入前要校验代次，而代次与写入若不在同一把锁里，
     * 「校验通过 → 主题切换 → 写入」会让旧主题的解析结果占住新主题的缓存键。
     */
    private val svgCacheLock = Any()

    /** 预解析线程：切主题时把内联 SVG 先解析好，键盘首帧只剩渲染。 */
    private val svgPrewarmExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "icon-theme-svg-prewarm").apply { isDaemon = true }
    }

    /** 每次换主题自增；后台预解析任务据此丢弃旧主题的结果，不污染新主题的缓存。 */
    private val svgPrewarmGeneration = java.util.concurrent.atomic.AtomicInteger()

    /** 命中只有内容一致才算数：同名主题换了内容时旧条目自动失效。 */
    private fun cachedSvg(key: String, content: String): com.caverock.androidsvg.SVG? =
        synchronized(svgCacheLock) {
            svgCache.get(key)?.takeIf { it.content == content }?.svg
        }

    /**
     * 发布解析结果。代次校验与写入在同一把锁里，且只在新条目缺失或内容不同的时候写：
     * 两个线程同时解析同一份内容，后到的那个不会覆盖先到的同内容结果。
     */
    private fun publishSvg(
        key: String,
        content: String,
        svg: com.caverock.androidsvg.SVG,
        generation: Int
    ) {
        synchronized(svgCacheLock) {
            if (generation != svgPrewarmGeneration.get()) return
            val existing = svgCache.get(key)
            if (existing == null || existing.content != content) {
                svgCache.put(key, CachedSvg(content, svg))
            }
        }
    }

    /**
     * 发布已经光栅化的图标。与 [publishSvg] 同理：代次校验与写入必须在清理缓存
     * （换主题）的同一把锁里，否则「校验通过 → 换同名主题 → 写入」会把旧主题的画面
     * 留在新主题的缓存键上。
     */
    private fun publishDrawable(key: String, info: IconDrawableInfo, generation: Int) {
        synchronized(svgCacheLock) {
            if (generation == svgPrewarmGeneration.get()) drawableCache.put(key, info)
        }
    }

    /** 只删自己那份内容的条目，避免把别处刚写入的新内容一起删掉。 */
    private fun removeCachedSvg(key: String, content: String) {
        synchronized(svgCacheLock) {
            if (svgCache.get(key)?.content == content) svgCache.remove(key)
        }
    }

    /**
     * 切换主题时清空两套缓存并推进代次。清空与推进必须在同一临界区：只验证代次、
     * 却在清空之后才写入的旧任务，否则会把旧主题的解析结果带进新主题。
     */
    private fun invalidateIconCaches(): Int = synchronized(svgCacheLock) {
        drawableCache.evictAll()
        svgCache.evictAll()
        svgPrewarmGeneration.incrementAndGet()
    }

    /** Drawable cache for SVG icons, keyed by "slot:themeName". */
    private val drawableCache = object : LruCache<String, IconDrawableInfo>(16 * 1024) {
        override fun sizeOf(key: String, value: IconDrawableInfo): Int {
            val w = value.drawable.intrinsicWidth.coerceAtLeast(1)
            val h = value.drawable.intrinsicHeight.coerceAtLeast(1)
            return w * h * 4 / 1024
        }
    }

    /**
     * 主题注册表与用户选择的版本号。
     *
     * 「选择」只在 [selectTheme]、以及删除当前主题时产生；「注册表」在保存、删除、扫描发布
     * 时推进。扫描在开始时于 [selectionLock] 内一次取走保存名与两个版本，收尾时只有注册表
     * 版本未变才允许发布；选择版本变了则不再按旧名恢复。这样扫描期间的点击、保存和删除
     * 都服从同一条所有权规则：更新的显式状态赢，过期扫描结果直接丢弃。
     */
    private val explicitSelectionVersion = java.util.concurrent.atomic.AtomicInteger()
    private val registryVersion = java.util.concurrent.atomic.AtomicInteger()

    /** 串行化注册表变更、显式选择与扫描发布，避免判断与赋值之间插入一次点击。 */
    private val selectionLock = Any()

    /** 扫描开始时的不可变快照；磁盘扫描在锁外进行，发布前拿它做版本校验。 */
    internal data class ScanSnapshot(
        val savedName: String?,
        val selectionVersion: Int,
        val registryVersion: Int
    )

    /**
     * 一次只读扫描的结果：主题列表与「需要回填缩略图」的待发布项分开。
     *
     * 扫描阶段只读盘，不能在锁外写回；待发布项带着扫描时读到的内容摘要，发布时再核对，
     * 避免旧扫描把用户刚保存或已删除的文件覆盖/重建。
     */
    internal data class ScannedThemes(
        val themes: List<IconTheme>,
        val thumbnailBackfills: List<PendingThumbnailBackfill> = emptyList()
    )

    internal data class PendingThumbnailBackfill(
        val file: File,
        val sourceDigest: String,
        val theme: IconTheme
    )

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
            val generation = invalidateIconCaches()
            prewarmSvgCache(value, generation)
            dispatchOnMain { onChangeListeners.toList().forEach { it.onIconThemeChange(value) } }
        }

    /**
     * 用户显式选择图标主题。所有来自界面的选择都走这里，扫描收尾据此判断是否该恢复旧名。
     */
    fun selectTheme(theme: IconTheme) {
        synchronized(selectionLock) {
            explicitSelectionVersion.incrementAndGet()
            activeTheme = theme
        }
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

    /** Serializes the initial scan and post-install rescans. */
    private val refreshMutex = Mutex()

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
            loadAndNotify(registerUnlockReceiver = true)
        }
    }

    /**
     * Re-scan themes after an asynchronous resource installer has finished writing them.
     *
     * The initial scan may legitimately see an empty directory on a fresh install. A later scan
     * must be allowed even after [initAsync] has marked initialization as started.
     */
    fun refreshAsync() {
        if (!initStarted.get()) {
            initAsync()
            return
        }
        scope.launch {
            loadAndNotify(registerUnlockReceiver = false)
        }
    }

    private suspend fun loadAndNotify(registerUnlockReceiver: Boolean) {
        refreshMutex.withLock {
            reconcileScans()
            if (registerUnlockReceiver && restorePending.get()) {
                dispatchOnMain { registerUserUnlockReceiver() }
            }
            val listed = iconThemes
            dispatchOnMain { onListChangeListeners.toList().forEach { it.onIconThemeListChange(listed) } }
        }
    }

    /**
     * 反复「取快照 → 只读扫描 → 发布」，直到某次发布没有被注册表变更打断。
     *
     * 单次扫描在用户保存/删除时会被整批丢弃；如果就此返回，盘上尚未注册的主题（例如安装器
     * 刚写入的内置资源）会一直缺席到进程重启。这里用同一把 [refreshMutex] 串行执行扫描请求，
     * 冲突后基于新的起点重扫：旧结果仍然丢弃，但重新扫描会同时看到用户的新内容和盘上其他
     * 主题。整个循环在后台协程中运行，可被取消；用户连续写入时最多表现为多扫几轮。
     *
     * [onScanFinished] 只为单测留出「已读盘、尚未发布」的可控交错点，生产路径使用默认实现。
     */
    internal suspend fun reconcileScans(
        onScanFinished: suspend (attempt: Int) -> Unit = {}
    ) {
        var attempt = 0
        while (true) {
            // 保存名与两个版本必须在同一临界区里取走：分两次读会让「读旧名 → 用户选择 →
            // 读新版本」的扫描误以为自己开始时就是新选择，收尾再把旧名写回去。
            val snapshot = beginScan()
            val scanned = scanThemeDirectory() ?: return
            onScanFinished(attempt)
            attempt++
            if (applyScannedThemes(scanned, snapshot)) return
            yield()
        }
    }

    /**
     * 扫描起点：在 [selectionLock] 内一次取走保存名、选择版本与注册表版本。
     *
     * [savedNameReader] 只为单测留出「读取保存名时用户恰好选择」的可控交错点；生产路径
     * 使用默认实现。它必须在锁内调用，测试里阻塞它即等于阻塞扫描起点。
     */
    internal fun beginScan(
        savedNameReader: () -> String? = { readActiveThemeName() }
    ): ScanSnapshot = synchronized(selectionLock) {
        ScanSnapshot(
            savedName = savedNameReader(),
            selectionVersion = explicitSelectionVersion.get(),
            registryVersion = registryVersion.get()
        )
    }

    /** 读取已保存的图标主题名；受保护存储不可读时返回 null 并登记解锁后重试。 */
    private fun readActiveThemeName(): String? = runCatching { activeThemeName }.getOrElse {
        restorePending.set(true)
        null
    }

    /**
     * 发布一次磁盘扫描。只有扫描起点之后没有任何注册表变更（保存、删除、另一次发布）时
     * 才生效；否则这份结果已经过期，直接丢弃，当前注册表与选择保持不变。
     *
     * 缩略图回填也在这里、在 [selectionLock] 内完成：扫描阶段只读，回填前再核对文件内容
     * 必须仍是扫描时读到的那一份。这样旧扫描不会覆盖用户刚保存的新版文件，也不会把已删除
     * 的文件重新创建出来。
     *
     * 生效时若选择版本也没变，才按扫描前保存的名字恢复；选择变了说明用户已经点过新的
     * 主题，活动主题由那次选择决定，这里不再用扫描前的旧对象覆盖它。
     */
    internal fun applyScannedThemes(scanned: ScannedThemes, snapshot: ScanSnapshot): Boolean =
        synchronized(selectionLock) {
            if (registryVersion.get() != snapshot.registryVersion) return false
            for (pending in scanned.thumbnailBackfills) {
                val current = runCatching {
                    pending.file.inputStream().use { it.readBounded(MAX_IMPORT_JSON_BYTES) }
                }.getOrNull() ?: continue
                if (current.sha256Hex() != pending.sourceDigest) continue
                runCatching {
                    FileUtil.writeAtomically(pending.file, json.encodeToString(pending.theme))
                }.onFailure {
                    Timber.w(it, "Failed to backfill thumbnail for icon theme: ${pending.theme.name}")
                }
            }
            installedThemes = scanned.themes
            registryVersion.incrementAndGet()
            if (explicitSelectionVersion.get() == snapshot.selectionVersion) {
                val current = activeTheme
                if (current !== builtinDefault && scanned.themes.none { it.name == current.name }) {
                    activeTheme = builtinDefault
                }
                restoreActiveThemeLocked(snapshot.savedName)
            }
            true
        }

    /** 单测与内部调用只需要发布主题列表时使用；生产扫描走带待回填项的 [ScannedThemes]。 */
    internal fun applyScannedThemes(themes: List<IconTheme>, snapshot: ScanSnapshot): Boolean =
        applyScannedThemes(ScannedThemes(themes), snapshot)

    private fun restoreActiveTheme() {
        synchronized(selectionLock) { restoreActiveThemeLocked(readActiveThemeName()) }
    }

    /** 调用方必须持有 [selectionLock]。 */
    private fun restoreActiveThemeLocked(savedName: String?) {
        if (savedName == null) return
        val restoredName = migratedThemeName(savedName)
        if (restoredName != savedName) activeThemeName = restoredName
        val saved = installedThemes.find { it.name == restoredName }
        if (saved != null && saved !== builtinDefault) {
            activeTheme = saved
        }
    }

    /**
     * 「蓝狐狸」图标主题改名为「普蓝」：选择里存的旧名要落到新主题上。
     * 旧主题文件还在（用户改过、安装器因此保留）时不迁移，选择仍指向用户那一份。
     */
    private fun migratedThemeName(name: String): String =
        if (name == "蓝狐狸" &&
            installedThemes.none { it.name == "蓝狐狸" } &&
            installedThemes.any { it.name == "普蓝" }
        ) "普蓝" else name

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

    /**
     * @param sizePx 期望的光栅边长（px）。null 表示沿用旧的 24dp 标准尺寸；按键面会按自己的
     *   实际大小传入，避免把 24dp 的位图放大到整键尺寸后糊掉。尺寸参与缓存键，同一槽位在
     *   不同尺寸下各自缓存，互不顶替。
     * @param inkColor 键面文字色。只对**多色**内联 SVG 生效：把近黑笔画换成这个颜色，让自带
     *   配色的图标在深色主题下也读得清，同时保留色点等原有配色。单色图标忽略此参数（它们
     *   走 tint）。颜色参与缓存键。
     */
    fun resolveIconDrawableInfo(
        slot: String,
        sizePx: Int? = null,
        inkColor: Int? = null
    ): IconDrawableInfo? {
        // 先取代次再读主题：主题 setter 是先换字段、后推进代次。这个顺序下，
        // 「旧代次 + 新内容」最多让本次结果不进缓存，绝不会把旧内容写进新代次的缓存。
        val generation = svgPrewarmGeneration.get()
        val theme = activeTheme
        val value = theme.icons[slot] ?: return null
        val isFileIcon = ButtonIconFile.isFileIcon(value)
        val isSvg = isInlineSvg(value)
        // 多色图标（自带配色）不套主题染色，但深色主题下它的近黑笔画会看不见：把近黑笔画
        // 换成键面文字色，色点等彩色部分原样保留。单色图标照旧走 tint，不需要这一步。
        val ink = if (isSvg && inkColor != null && !isSvgMonochrome(value)) inkColor else null
        // 只有内联 SVG 会按 sizePx 重新光栅化；文件图标和 XML 图标与尺寸无关，缓存键里
        // 带上尺寸只会让同一张图按不同尺寸重复存几份。
        val key = if (isSvg && sizePx != null) "$slot:${theme.name}:$sizePx${inkSuffix(ink)}"
        else "$slot:${theme.name}${inkSuffix(ink)}"
        drawableCache.get(key)?.let { cached ->
            return IconDrawableInfo(
                drawable = cloneDrawable(cached.drawable),
                tintWithTheme = cached.tintWithTheme
            )
        }
        val loaded: Drawable = (if (isFileIcon) {
            // 文件图标已在 ButtonIconFile.loadDrawable 出口统一归一到标准图标尺寸。
            ButtonIconFile.loadDrawable(value)
        } else if (isSvg) {
            loadSvgDrawable(value, sizePx, "$slot:" + theme.name + inkSuffix(ink), ink, generation)
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
        publishDrawable(key, info, generation)
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

    private fun loadSvgDrawable(
        svgContent: String,
        sizePx: Int? = null,
        cacheKey: String? = null,
        inkColor: Int? = null,
        generation: Int = svgPrewarmGeneration.get()
    ): Drawable? {
        val density = appContext.resources.displayMetrics.density
        val size = sizePx?.coerceAtLeast(1) ?: (24 * density).toInt().coerceAtLeast(1)
        val content = if (inkColor == null) svgContent else recolorNearBlack(svgContent, inkColor)
        // 解析结果按槽位缓存：同一图标换尺寸时只重新光栅化。缓存未命中时现场解析并回填；
        // 命中和回填都要比对内容身份，渲染失败只删自己那份，避免把坏结果或别人的新结果
        // 留在缓存里。
        val svg = (cacheKey?.let { cachedSvg(it, content) } ?: parseSvg(content)?.also { parsed ->
            if (cacheKey != null) publishSvg(cacheKey, content, parsed, generation)
        }) ?: return null
        return try {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            svg.renderToCanvas(canvas, android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()))
            BitmapDrawable(appContext.resources, bitmap)
        } catch (_: Exception) {
            if (cacheKey != null) removeCachedSvg(cacheKey, content)
            null
        }
    }

    /** 解析内联 SVG；规范化版本解析不了时退回原串（与旧实现同样的兜底顺序）。 */
    private fun parseSvg(svgContent: String): com.caverock.androidsvg.SVG? {
        for (candidate in svgCandidates(svgContent)) {
            try {
                return com.caverock.androidsvg.SVG.getFromString(candidate)
            } catch (_: Exception) {
                // Try the original SVG when normalization changed unsupported markup.
            }
        }
        return null
    }

    /**
     * 后台预解析当前主题里的内联 SVG。
     *
     * 键盘首帧上 26 个字母图标各解析一次 XML，累加起来足以吃掉一帧；切主题时用户还在设置页
     * 或工具栏，正好把这段开销挪到看不见的地方。解析失败不重试、不抛异常。
     */
    private fun prewarmSvgCache(theme: IconTheme, generation: Int) {
        val entries = theme.icons.filterValues { isInlineSvg(it) }
        if (entries.isEmpty()) return
        val themeName = theme.name
        svgPrewarmExecutor.execute {
            for ((slot, svg) in entries) {
                // 主题已经被替换：这份结果属于旧 SVG，写进同名缓存键会让新主题读到过期图标。
                if (generation != svgPrewarmGeneration.get()) return@execute
                val key = "$slot:$themeName"
                if (cachedSvg(key, svg) != null) continue
                val parsed = parseSvg(svg) ?: continue
                // 解析本身要花时间；期间主题可能已被同名保存换成新内容。代次校验和写入
                // 由 publishSvg 放在同一把锁里完成，旧代次的结果不会占住新主题的缓存键；
                // 条目还会记住源 SVG，读取方按自己手里的内容校验，不会读到别的版本。
                publishSvg(key, svg, parsed, generation)
            }
        }
    }

    private fun svgCandidates(raw: String): List<String> =
        listOf(normalizeSvgContent(raw), raw.trim()).distinct()

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

    /** 缓存键里的笔画色后缀；没有替换时为空串，保持旧键不变。 */
    private fun inkSuffix(ink: Int?): String = if (ink == null) "" else ":%08X".format(ink)

    private val svgColorAttr = Regex("(fill|stroke)\\s*=\\s*(['\"])([^'\"]+)\\2")

    /**
     * 把 SVG 里的近黑笔画换成 [inkColor]。
     *
     * 水彩字母是「黑色笔画 + 粉/蓝/绿的小色点」，整枚 SVG 含非灰颜色，按既有规则判为多色、
     * 不套主题染色——于是深色主题下黑笔画永远压在深底上看不见，同一套键盘里带色点的字母黑、
     * 不带色点的字母被染成浅色，混排得很乱。这里只动近黑（每个通道都不超过 [NEAR_BLACK_MAX]）
     * 的 fill/stroke，色点、渐变和 fill-opacity 全部原样保留：浅色主题下文字色本来就接近黑，
     * 观感不变；深色主题下笔画跟着文字色变浅，与单色图标被染色的结果一致。
     */
    private fun recolorNearBlack(svgContent: String, inkColor: Int): String {
        val hex = "#%06X".format(inkColor and 0xFFFFFF)
        return svgColorAttr.replace(svgContent) { match ->
            val color = parseSvgColor(match.groupValues[3]) ?: return@replace match.value
            val nearBlack = Color.red(color) <= NEAR_BLACK_MAX &&
                Color.green(color) <= NEAR_BLACK_MAX &&
                Color.blue(color) <= NEAR_BLACK_MAX
            if (!nearBlack) match.value
            else "${match.groupValues[1]}=${match.groupValues[2]}$hex${match.groupValues[2]}"
        }
    }

    /**
     * 纯磁盘扫描：只解析并算出内存里的缩略图，绝不写盘；发布与回填由 [applyScannedThemes]
     * 在同一把锁内完成。internal 让单测能把「磁盘上实际是什么」与注册表、活动对象分开断言。
     */
    internal fun scanThemeDirectory(): ScannedThemes? {
        val dir = themeDir ?: return null
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return null
        val backfills = mutableListOf<PendingThumbnailBackfill>()
        val themes = files.mapNotNull { f ->
            runCatching {
                require(ThemeStoragePaths.within(dir, f) == ThemeStoragePaths.json(dir, f.nameWithoutExtension))
                val source = f.inputStream().use { it.readBounded(MAX_IMPORT_JSON_BYTES) }
                val loaded = json.decodeFromString<IconTheme>(source.decodeToString())
                require(ThemeStoragePaths.json(dir, loaded.name) == f.canonicalFile)
                val normalized = fillMissingThumbnail(loaded)
                if (normalized != loaded) {
                    backfills += PendingThumbnailBackfill(f, source.sha256Hex(), normalized)
                }
                normalized
            }.onFailure {
                Timber.w(it, "Failed to load icon theme: ${f.name}")
            }.getOrNull()
        }
        return ScannedThemes(themes, backfills)
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun saveTheme(theme: IconTheme) {
        val normalizedTheme = fillMissingThumbnail(theme)
        val dir = themeDir ?: return
        val file = ThemeStoragePaths.json(dir, normalizedTheme.name)
        synchronized(selectionLock) {
            // 磁盘写入与注册表更新同属一次发布：扫描要么看到旧文件旧版本，要么看到新文件
            // 新版本，不会拿到「文件已新、注册表还是旧」的中间态再把它发布出去。
            runCatching {
                FileUtil.writeAtomically(file, json.encodeToString(normalizedTheme))
            }.onFailure {
                Timber.w(it, "Failed to save icon theme: ${normalizedTheme.name}")
                return
            }
            val mutable = installedThemes.toMutableList()
            val idx = mutable.indexOfFirst { it.name == normalizedTheme.name }
            if (idx >= 0) {
                mutable[idx] = normalizedTheme
            } else {
                mutable.add(0, normalizedTheme)
            }
            installedThemes = mutable
            registryVersion.incrementAndGet()
            if (activeTheme.name == normalizedTheme.name) {
                activeTheme = normalizedTheme
            }
        }
        cleanupOrphanedPngs(normalizedTheme.name)
        dispatchOnMain { onListChangeListeners.toList().forEach { it.onIconThemeListChange(iconThemes) } }
    }

    fun deleteTheme(name: String) {
        ThemeStoragePaths.requireName(name)
        var removed = false
        synchronized(selectionLock) {
            val mutable = installedThemes.toMutableList()
            val idx = mutable.indexOfFirst { it.name == name }
            if (idx < 0) return
            val dir = themeDir
            if (dir != null) ThemeStoragePaths.json(dir, name).delete()
            mutable.removeAt(idx)
            installedThemes = mutable
            registryVersion.incrementAndGet()
            removed = true
            // 删除当前主题本身就是一次显式选择：保存名与选择版本都必须落到内置默认，
            // 否则扫描收尾仍会拿着已删除的名字去找主题。
            if (activeTheme.name == name) {
                selectTheme(builtinDefault)
            }
        }
        if (!removed) return
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
            compatibleZipInputStream(src, charset).use { zipStream ->
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
        ThemeStoragePaths.requireName(finalName)

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
