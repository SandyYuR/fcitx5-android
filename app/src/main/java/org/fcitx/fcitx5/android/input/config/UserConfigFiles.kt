/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

object UserConfigFiles {
    const val DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE = "default"

    /**
     * 「符号 / 表情 / 颜文字」使用内置 catalog 的哨兵值。
     * 与 Foxy 的 `__builtin__` 语义一致（`mv0.c` 的默认值）。
     */
    const val SYMBOL_CATALOG_BUILTIN = "__builtin__"
    private const val TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME = "TextKeyboardLayout.json"
    private const val TEXT_KEYBOARD_LAYOUT_PREFIX = "TextKeyboardLayout."
    private const val JSON_SUFFIX = ".json"
    private const val KEY_SOUND_DIR_NAME = "key_sounds"

    /**
     * 用户自备符号 catalog 的根目录（下分 `symbols/`、`emoji/`、`kaomoji/` 三个子目录）。
     * 对应 Foxy 的 `frontend/{symbols,emoji,kaomoji}/`。
     */
    private const val SYMBOL_CATALOG_DIR_NAME = "symbol_catalogs"

    /** catalog 子目录名：只允许固定的三个，杜绝用 `..` 之类从设置里指到别处。 */
    private val SAFE_DIR_NAME = Regex("^(symbols|emoji|kaomoji)$")

    /**
     * 用户自备 catalog 的文件名规则，与 Foxy 的 `mv0.a` 等价：
     * 以 `.json` 结尾、总长 > 5、且不含任何路径分隔符与 Windows 保留字符。
     *
     * 不含分隔符即可保证解析结果落在 catalog 目录内（`..` 单独出现不构成穿越，
     * 因为 `..json` 这类名字去掉后缀后仍是普通文件名）。
     * 前导 `{1,120}` 保证 `.json` 之前至少有一个字符，即要求总长 > 5。
     */
    private val SAFE_CATALOG_NAME = Regex("^[^/\\\\:*?\"<>|\\p{Cntrl}]{1,120}\\.json$")
    private val AUDIO_FILE_NAME = Regex("^[A-Za-z0-9._ -]+\\.(?i:wav|mp3|ogg|m4a|flac)$")
    private val TEXT_KEYBOARD_LAYOUT_BACKUP_FILE_NAME = Regex(
        "^TextKeyboardLayout(?:\\..+)?_backup_\\d{8}_\\d{6}(?:_.*)?\\.json$"
    )

    private fun externalFilesRoot(): File? = appContext.getExternalFilesDir(null)

    fun configDir(): File? = externalFilesRoot()?.let { File(it, "config") }

    fun fontsDir(): File? = externalFilesRoot()?.let { File(it, "fonts") }

    fun keySoundsDir(): File? = configDir()?.let { File(it, KEY_SOUND_DIR_NAME) }

    fun keySoundFile(name: String): File? {
        if (!AUDIO_FILE_NAME.matches(name)) return null
        return keySoundsDir()?.let { File(it, name) }
    }

    fun listKeySoundFiles(): List<String> = keySoundsDir()
        ?.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && AUDIO_FILE_NAME.matches(it.name) }
        ?.map { it.name }
        ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
        ?.toList()
        .orEmpty()

    fun textKeyboardLayoutJson(): File? = textKeyboardLayoutJson(DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)

    fun textKeyboardLayoutJson(profile: String): File? {
        val normalized = normalizeTextKeyboardLayoutProfile(profile) ?: return null
        val fileName = if (normalized == DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME
        } else {
            "$TEXT_KEYBOARD_LAYOUT_PREFIX$normalized$JSON_SUFFIX"
        }
        return configDir()?.let { File(it, fileName) }
    }

    fun normalizeTextKeyboardLayoutProfile(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val sanitized = trimmed
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "")
            .trim()
            .trim('.')
        if (sanitized.isEmpty()) return null
        return if (sanitized.equals(DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE, ignoreCase = true)) {
            DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        } else {
            sanitized
        }
    }

    fun listTextKeyboardLayoutProfiles(): List<String> {
        val dir = configDir() ?: return listOf(DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)
        val fileNames = dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.toList()
            .orEmpty()

        val profiles = mutableSetOf<String>()
        if (fileNames.any { it == TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME }) {
            profiles += DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        }
        fileNames.forEach { name ->
            if (TEXT_KEYBOARD_LAYOUT_BACKUP_FILE_NAME.matches(name)) return@forEach
            if (name == TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME) return@forEach
            if (name.startsWith(TEXT_KEYBOARD_LAYOUT_PREFIX) && name.endsWith(JSON_SUFFIX)) {
                val rawProfile = name.removePrefix(TEXT_KEYBOARD_LAYOUT_PREFIX).removeSuffix(JSON_SUFFIX)
                val profile = normalizeTextKeyboardLayoutProfile(rawProfile)
                if (profile != null && profile != DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
                    profiles += profile
                }
            }
        }

        profiles += DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        return profiles.toList().sortedWith(compareBy({ it != DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }, { it }))
    }

    fun textKeyboardLayoutFileName(profile: String): String {
        val normalized = normalizeTextKeyboardLayoutProfile(profile) ?: DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        return if (normalized == DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME
        } else {
            "$TEXT_KEYBOARD_LAYOUT_PREFIX$normalized$JSON_SUFFIX"
        }
    }

    fun popupPresetJson(): File? = configDir()?.let { File(it, "PopupPreset.json") }

    /**
     * 用户自备符号 catalog 的目录，按 [subDir] 区分三类
     * （`symbols` / `emoji` / `kaomoji`，与 Foxy 的 `frontend/<kind>/` 对应）。
     */
    fun symbolCatalogDir(subDir: String): File? {
        if (!SAFE_DIR_NAME.matches(subDir)) return null
        return configDir()?.let { File(File(it, SYMBOL_CATALOG_DIR_NAME), subDir) }
    }

    /**
     * 校验 catalog 文件名是否符合规则（纯函数，不触碰文件系统，便于单测）。
     *
     * 规则与 Foxy 的 `mv0.a` 等价：以 `.json` 结尾、总长 > 5、且不含任何路径分隔符
     * 或 Windows 保留字符。不含分隔符即可保证拼接结果落在 catalog 目录内。
     */
    fun isValidCatalogName(name: String): Boolean = SAFE_CATALOG_NAME.matches(name)

    /**
     * 校验 catalog 文件名并解析成文件。
     *
     * 见 [isValidCatalogName]；返回 null 表示名字非法（调用方按"用户自备不可用"处理）。
     */
    fun symbolCatalogFile(subDir: String, name: String): File? {
        if (!isValidCatalogName(name)) return null
        return symbolCatalogDir(subDir)?.let { File(it, name) }
    }

    /** 列出某个目录下可作为 catalog 的文件名（已排序，过滤目录/非法名/越界链接）。 */
    fun listSymbolCatalogFiles(subDir: String): List<String> {
        val dir = symbolCatalogDir(subDir) ?: return emptyList()
        // 用规范化后的目录路径做基准：Android 上 getExternalFilesDir 返回的路径
        // 本身常含软链接（如 /storage/emulated/0 → /mnt/user/0/...），因此**不能**
        // 用 `canonicalPath != absolutePath` 判断"文件是软链接"——那样会把所有
        // 正常文件都判成软链接。改为检查规范化后的文件路径是否仍在目录内：
        // 目录内的普通文件通过，指向外部的软链接被排除。
        val base = runCatching { dir.canonicalPath }.getOrNull() ?: return emptyList()
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && isValidCatalogName(it.name) }
            ?.filter { file ->
                val real = runCatching { file.canonicalPath }.getOrNull() ?: return@filter false
                real.startsWith(base + File.separator)
            }
            ?.map { it.name }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?.toList()
            .orEmpty()
    }

    fun fontsetJson(): File? = fontsDir()?.let { File(it, "fontset.json") }
    
    fun kawaiiBarButtonsConfig(): File? = configDir()?.let { File(it, "KawaiiBarButtonsLayout.json") }

    fun statusAreaButtonsConfig(): File? = configDir()?.let { File(it, "StatusAreaButtonsLayout.json") }

    /**
     * Unified buttons layout configuration file.
     * Replaces separate KawaiiBarButtonsLayout.json and StatusAreaButtonsLayout.json files.
     */
    fun buttonsLayoutConfig(): File? = configDir()?.let { File(it, "ButtonsLayout.json") }
}
