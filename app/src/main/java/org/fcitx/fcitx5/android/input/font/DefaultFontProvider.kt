/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.font

import android.graphics.Typeface
import java.io.File

class DefaultFontProvider : FontProviderApi {
    @Volatile
    private var cachedFontTypefaceMap: MutableMap<String, Typeface?>? = null
    @Volatile
    private var cachedFontSizeMap: MutableMap<String, Float>? = null
    @Volatile
    private var lastModified = 0L
    @Volatile
    private var isLoading = false

    override fun clearCache() {
        synchronized(this) {
            cachedFontTypefaceMap = null
            cachedFontSizeMap = null
            lastModified = 0L
            isLoading = false
        }
    }

    /**
     * Preload fonts asynchronously to avoid blocking UI thread.
     * Call this when keyboard is about to show.
     */
    fun preloadFontsAsync(onComplete: ((MutableMap<String, Typeface?>) -> Unit)? = null) {
        if (isLoading) return  // Already loading
        isLoading = true

        Thread {
            val fonts = fontTypefaceMap
            isLoading = false
            onComplete?.invoke(fonts)
        }.start()
    }

    override val fontTypefaceMap: MutableMap<String, Typeface?>
        get() {
            // Fast path: lock-free volatile read.
            val cached = cachedFontTypefaceMap
            if (cached != null && !isLoading) return cached
            return synchronized(this) { loadTypefaceMapLocked() }
        }

    private fun loadTypefaceMapLocked(): MutableMap<String, Typeface?> {
        val recheck = cachedFontTypefaceMap
        if (recheck != null && !isLoading) return recheck

        val snapshot = org.fcitx.fcitx5.android.input.config.ConfigProviders
            .readFontsetPathMapSnapshot()
            .getOrNull() ?: run {
            cachedFontTypefaceMap = null
            return mutableMapOf()
        }
        val fontsDir = snapshot.file?.parentFile ?: run {
            cachedFontTypefaceMap = null
            return mutableMapOf()
        }
        if (cachedFontTypefaceMap == null || lastModified != snapshot.lastModified) {
            cachedFontTypefaceMap = runCatching {
                snapshot.value
                    .filterKeys { !it.endsWith("_size") }
                    .mapValues { (_, paths) ->
                        runCatching {
                            val validPaths = paths.map { it.trim() }
                                .filter { File(fontsDir, it).exists() }
                            when {
                                validPaths.isEmpty() -> null
                                validPaths.size == 1 || android.os.Build.VERSION.SDK_INT < 29 ->
                                    Typeface.createFromFile(File(fontsDir, validPaths.first()))
                                else -> buildCustomFallbackTypeface(fontsDir, validPaths)
                            }
                        }.getOrNull()
                    } as MutableMap<String, Typeface?>
            }.getOrElse { mutableMapOf() }
            lastModified = snapshot.lastModified
        }
        return cachedFontTypefaceMap ?: mutableMapOf()
    }

    @androidx.annotation.RequiresApi(29)
    private fun buildCustomFallbackTypeface(
        fontsDir: File,
        validPaths: List<String>
    ): Typeface {
        val firstFamily = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(File(fontsDir, validPaths[0])).build()
        ).build()
        val builder = android.graphics.Typeface.CustomFallbackBuilder(firstFamily)
        for (i in 1 until validPaths.size) {
            val family = android.graphics.fonts.FontFamily.Builder(
                android.graphics.fonts.Font.Builder(File(fontsDir, validPaths[i])).build()
            ).build()
            builder.addCustomFallback(family)
        }
        return builder.build()
    }

    override val fontSizeMap: MutableMap<String, Float>
        get() {
            // Fast path: lock-free volatile read.
            val cached = cachedFontSizeMap
            if (cached != null) return cached
            return synchronized(this) { loadSizeMapLocked() }
        }

    private fun loadSizeMapLocked(): MutableMap<String, Float> {
        val recheck = cachedFontSizeMap
        if (recheck != null) return recheck

        val snapshot = org.fcitx.fcitx5.android.input.config.ConfigProviders
            .readFontsetPathMapSnapshot()
            .getOrNull() ?: run {
            cachedFontSizeMap = null
            return mutableMapOf()
        }
        if (cachedFontSizeMap == null || lastModified != snapshot.lastModified) {
            cachedFontSizeMap = runCatching {
                snapshot.value
                    .filterKeys { it.endsWith("_size") }
                    .mapValues { (_, values) ->
                        runCatching {
                            values.firstOrNull()?.trim()
                                ?.toFloatOrNull()?.coerceIn(8f, 72f)
                        }.getOrNull()
                    }
                    .filterValues { it != null }
                    .mapValues { it.value!! } as MutableMap<String, Float>
            }.getOrElse { mutableMapOf() }
        }
        return cachedFontSizeMap ?: mutableMapOf()
    }
}
