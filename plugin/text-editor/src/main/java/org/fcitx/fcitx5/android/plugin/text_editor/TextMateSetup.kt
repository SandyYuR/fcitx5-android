/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.res.AssetManager
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource
import timber.log.Timber
import java.nio.charset.StandardCharsets

object TextMateSetup {

    private const val LANGUAGES_PATH = "textmate/languages.json"
    private const val THEME_DARK_PATH = "textmate/themes/darcula.json"
    private const val THEME_LIGHT_PATH = "textmate/themes/quietlight.json"
    private const val THEME_DARK_NAME = "darcula"
    private const val THEME_LIGHT_NAME = "quietlight"

    @Volatile
    private var initialized = false
    @Volatile
    private var grammarsLoaded = false

    @Synchronized
    private fun ensureInitialized(assets: AssetManager) {
        if (initialized) return
        // Register file provider unconditionally; without it nothing else works.
        try {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
        } catch (e: Exception) {
            Timber.e(e, "Failed to register TextMate asset provider")
            return
        }
        // Load each theme independently — a single broken theme must not block highlighting.
        loadThemeSafely(THEME_DARK_PATH, THEME_DARK_NAME, dark = true)
        loadThemeSafely(THEME_LIGHT_PATH, THEME_LIGHT_NAME, dark = false)
        // Grammar loading is best-effort and tracked separately.
        grammarsLoaded = try {
            GrammarRegistry.getInstance().loadGrammars(LANGUAGES_PATH)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load TextMate grammars")
            false
        }
        initialized = true
    }

    private fun loadThemeSafely(path: String, name: String, dark: Boolean) {
        try {
            ThemeRegistry.getInstance().loadTheme(loadThemeModel(path, name, dark))
        } catch (e: Exception) {
            Timber.e(e, "Failed to load TextMate theme $name")
        }
    }

    private fun loadThemeModel(path: String, name: String, dark: Boolean): ThemeModel {
        val stream = FileProviderRegistry.getInstance().tryGetInputStream(path)
            ?: error("Theme asset $path not found")
        val source = IThemeSource.fromInputStream(stream, path, StandardCharsets.UTF_8)
        return ThemeModel(source, name).apply { setDark(dark) }
    }

    fun applyTheme(isDark: Boolean, assets: AssetManager) {
        ensureInitialized(assets)
        val target = if (isDark) THEME_DARK_NAME else THEME_LIGHT_NAME
        try {
            if (!ThemeRegistry.getInstance().setTheme(target)) {
                // Fallback if the requested theme failed to load earlier.
                val fallback = if (isDark) THEME_LIGHT_NAME else THEME_DARK_NAME
                ThemeRegistry.getInstance().setTheme(fallback)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to apply TextMate theme $target")
        }
    }

    fun createColorScheme(assets: AssetManager): EditorColorScheme {
        ensureInitialized(assets)
        return try {
            TextMateColorScheme.create(ThemeRegistry.getInstance())
        } catch (e: Exception) {
            Timber.e(e, "Failed to create TextMate color scheme")
            EditorColorScheme()
        }
    }

    fun createLanguage(scopeName: String?, assets: AssetManager, useTab: Boolean): Language {
        if (scopeName.isNullOrEmpty()) return PlainLanguage(useTab)
        ensureInitialized(assets)
        if (!grammarsLoaded) return PlainLanguage(useTab)
        return try {
            WideIdentifierTextMateLanguage.create(scopeName).also { it.useTab(useTab) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create TextMate language for $scopeName")
            PlainLanguage(useTab)
        }
    }

    // EmptyLanguage.useTab() is hardcoded to false, which forces tab→spaces on any file we don't
    // have a grammar for. Override it so the menu toggle (and the default of inserting a literal
    // tab) takes effect for plain-text files too.
    private class PlainLanguage(private val useTab: Boolean) : EmptyLanguage() {
        override fun useTab(): Boolean = useTab
    }
}
