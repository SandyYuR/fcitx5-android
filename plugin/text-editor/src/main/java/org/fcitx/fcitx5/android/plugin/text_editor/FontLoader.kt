/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import timber.log.Timber
import java.io.File

object FontLoader {

    private const val FONTS_DIR = "fonts"

    fun fontsDir(context: Context): File = File(context.filesDir, FONTS_DIR).apply { mkdirs() }

    // Cache key is the in-order list of resolved absolute paths + their lastModified stamps, so
    // re-importing a same-named font (different bytes) still invalidates correctly.
    private data class CacheKey(val paths: List<String>, val mtimes: List<Long>)

    @Volatile
    private var cached: Pair<CacheKey, Typeface>? = null

    fun loadTypeface(context: Context, fileNames: List<String>): Typeface {
        val dir = fontsDir(context)
        val resolved = fileNames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(dir, it) }
            .filter { it.isFile }
        if (resolved.isEmpty()) return Typeface.MONOSPACE

        val key = CacheKey(resolved.map { it.absolutePath }, resolved.map { it.lastModified() })
        cached?.let { (k, tf) -> if (k == key) return tf }

        val tf = buildTypeface(resolved) ?: Typeface.MONOSPACE
        cached = key to tf
        return tf
    }

    private fun buildTypeface(files: List<File>): Typeface? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                val first = FontFamily.Builder(Font.Builder(files[0]).build()).build()
                val builder = Typeface.CustomFallbackBuilder(first)
                for (i in 1 until files.size) {
                    val family = FontFamily.Builder(Font.Builder(files[i]).build()).build()
                    builder.addCustomFallback(family)
                }
                builder.build()
            }.onFailure { Timber.e(it, "Typeface.CustomFallbackBuilder failed") }.getOrNull()
        }
        return runCatching { Typeface.createFromFile(files[0]) }
            .onFailure { Timber.e(it, "Typeface.createFromFile failed") }
            .getOrNull()
    }
}
