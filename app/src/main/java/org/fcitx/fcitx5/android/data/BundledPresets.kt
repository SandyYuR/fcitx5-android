/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File

/**
 * 内置资源（键盘布局 / 主题 / 图标主题 / 弹出定义）安装器。
 *
 * 这些资源随 APK 打包在 `assets/bundled/` 下，首次启动时解包到应用外部
 * 文件目录的对应位置，实现"开箱即用"：
 *
 * - `bundled/keyboard_layouts/TextKeyboardLayout.<名称>.json` → `config/`，并注册对应 profile
 * - `bundled/keyboard_layouts/PopupPreset.json`               → 不落盘，作为运行时默认弹出定义
 * - `bundled/themes/<主题名>.json`                            → `theme/`（自定义主题目录）
 * - `bundled/icon_themes/<主题名>.json`                       → `icon_themes/`
 * - `bundled/fonts/<字体文件名>`                              → `fonts/`（「字体设定」读取的目录）
 *
 * 安装幂等且不覆盖：每个资源以"asset 路径 + 字节数"为版本标记记录在
 * SharedPreferences 中；默认不覆盖已存在的文件，显式登记更新的资源只在
 * 文件大小仍等于上一版内置内容时替换，保留用户自行修改的版本。
 * 登记更新还记录旧字节数，允许修正旧登记后重试仍存在的原版文件。
 * 用户删除已处理版本的文件后不会被再次塞回；更新资源须同步登记旧字节数。
 */
object BundledPresets {

    private const val PREF_NAME = "bundled_presets"
    private const val KEY_INSTALLED = "installed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /** 已处理版本及更新来源标记；旧版本标记继续保护已删除的文件。 */
    private val handled: MutableSet<String> by lazy {
        prefs.getStringSet(KEY_INSTALLED, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun mark(vararg tags: String) {
        handled.addAll(tags)
        prefs.edit { putStringSet(KEY_INSTALLED, handled) }
    }

    /** Android assets 无法可靠列举中文文件名，直接维护清单最可靠。 */
    private val layoutAssets = listOf(
        "TextKeyboardLayout.大同-9+18+26keys.json",
        "TextKeyboardLayout.九键布局.json",
        "TextKeyboardLayout.行之-26键.json",
        "TextKeyboardLayout.行之-万象九键v2.json",
        "TextKeyboardLayout.QM-朝花.json",
        "TextKeyboardLayout.Sandy-数字行26+万象九键.json",
        "TextKeyboardLayout.水彩.json",
        "TextKeyboardLayout.普蓝.json"
    )

    /**
     * 「上一版内置内容」的字节大小，用于区分用户是否改过本地文件：
     * [installAsset] 只在 `dest.length()` 等于这里的登记值时才原位替换，
     * 长度不同即视为用户自己编辑过、保留用户版本。
     *
     * 因此每次更新某个资源后，要把这里的值改成**更新前那一版的字节数**
     * （不是新版本的字节数），否则新内容对存量用户永不生效；未登记的
     * 资源则只在目标文件不存在时才写出。
     */
    private val assetSizes = mapOf(
        "bundled/keyboard_layouts/TextKeyboardLayout.大同-9+18+26keys.json" to 13777,
        "bundled/keyboard_layouts/TextKeyboardLayout.Sandy-数字行26+万象九键.json" to 36265,
        "bundled/icon_themes/谷歌.json" to 68073,
        // 水彩套装：随 foxy 上游素材更新；登记的是上一版内置内容的字节数。
        "bundled/icon_themes/水彩.json" to 183743,
        "bundled/keyboard_layouts/TextKeyboardLayout.水彩.json" to 30782,
        "bundled/themes/水彩.json" to 838,
        "bundled/icon_themes/普蓝.json" to 62181,
        "bundled/keyboard_layouts/TextKeyboardLayout.普蓝.json" to 9436,
        "bundled/themes/普蓝.json" to 701
    )

    /**
     * 已退役的内置资源：升级后删掉设备上仍是原版内容（字节数等于这里登记值）的旧文件。
     *
     * 主题改名会留下旧文件名的那一份，它与新文件同名不同名地并存，主题列表里就会出现
     * 两张几乎一样的卡片。用户改过的文件（长度不同）保留，与 [installAsset] 的判定一致。
     */
    private val retiredAssets = mapOf(
        "theme/水彩（浅色）.json" to 850L,
        "icon_themes/蓝狐狸.json" to 56240L,
        "config/TextKeyboardLayout.蓝狐狸.json" to 19729L,
        "theme/蓝狐狸.json" to 709L
    )

    private val themeAssets = listOf(
        "森林集·write.json",
        "长日将烬.json",
        "braun-minimal-dark.json",
        "braun-minimal-light.json",
        "Caramel Dawn.json",
        "Caramel Ember.json",
        "github-dark-dim.json",
        "industrial-orange-light.json",
        "isGboard.json",
        "sage-journey.json",
        "WeChat Dark.json",
        "wechat-light.json",
        "水彩.json",
        "普蓝.json"
    )

    private val iconThemeAssets = listOf(
        "谷歌.json",
        "无题.json",
        "长日将烬.json",
        "水彩.json",
        "普蓝.json"
    )

    /**
     * 内置字体：安装到 `fonts/`，即「字体设定」列出可选字体的目录。
     * 成套主题引用的字体必须在这里登记，否则新装设备上主题会指向一个不存在的文件。
     */
    private val fontAssets = listOf(
        "字体图标.otf"
    )

    const val POPUP_PRESET_ASSET = "bundled/keyboard_layouts/PopupPreset.json"

    /** 布局文件名 → profile 名（去掉 TextKeyboardLayout. 前缀与 .json 后缀）。 */
    fun layoutProfileOf(fileName: String): String? {
        if (!fileName.startsWith("TextKeyboardLayout.") || !fileName.endsWith(".json")) return null
        return fileName.removePrefix("TextKeyboardLayout.").removeSuffix(".json")
    }

    /**
     * 在后台安装内置资源。重复调用是安全的；安装失败只记录日志，不影响启动。
     * [onComplete] 在本次安装尝试结束后于后台线程调用。
     */
    fun installAsync(onComplete: () -> Unit = {}) {
        scope.launch {
            runCatching { install() }.onFailure {
                Timber.w(it, "Failed to install bundled presets")
            }
            onComplete()
        }
    }

    private fun install() {
        val extDir = appContext.getExternalFilesDir(null) ?: return
        var installedCount = 0

        // 键盘布局：安装到 config/
        val configDir = UserConfigFiles.configDir() ?: File(extDir, "config").apply { mkdirs() }
        // 只有第一次铺内置布局时才自动选中第一套；之后用户显式选择 "default" 也要保留，
        // 不能因为每次启动都跑一遍而把他改回内置布局。
        val hadBundledLayout = layoutAssets.any { File(configDir, it).isFile }
        layoutAssets.forEach { name ->
            installAsset("bundled/keyboard_layouts/$name", File(configDir, name)).let { installedCount += it }
        }

        // 退役资源：只删除仍与上一版内置内容一致的文件，用户改过的保留。
        // 必须排在下面的改名迁移之前：迁移是否安全取决于旧文件是否还在——
        // 原版旧文件删掉才迁移选择，用户改过而留下的旧文件则保留其选择。
        retiredAssets.forEach { (path, size) ->
            val file = File(extDir, path)
            if (file.isFile && file.length() == size) {
                if (file.delete()) {
                    Timber.i("Retired bundled asset %s", path)
                    installedCount += 1
                }
            }
        }

        // 改名迁移必须每次跑；首次默认布局的选择只在还没有任何内置布局时做。
        ensureLayoutProfile(allowDefaultProfileSelection = !hadBundledLayout)

        // 主题：安装到 theme/（与用户自定义主题同目录）
        val themeDir = File(extDir, "theme").apply { mkdirs() }
        themeAssets.forEach { name ->
            installAsset("bundled/themes/$name", File(themeDir, name)).let { installedCount += it }
        }

        // 图标主题：安装到 icon_themes/
        val iconThemeDir = File(extDir, "icon_themes").apply { mkdirs() }
        iconThemeAssets.forEach { name ->
            installAsset("bundled/icon_themes/$name", File(iconThemeDir, name)).let { installedCount += it }
        }

        // 字体：安装到 fonts/（「字体设定」与成套主题的按键副字体都从这里取）
        val fontDir = UserConfigFiles.fontsDir() ?: File(extDir, "fonts").apply { mkdirs() }
        fontAssets.forEach { name ->
            installAsset("bundled/fonts/$name", File(fontDir, name)).let { installedCount += it }
        }

        if (installedCount > 0) {
            Timber.i("Bundled presets installed %d file(s)", installedCount)
        }
    }

    /**
     * 安装单个 asset。返回 1 表示本次实际写出了文件，0 表示跳过（已处理/已存在/读取失败）。
     */
    private fun installAsset(assetPath: String, dest: File, assetBytes: ByteArray? = readAsset(assetPath)): Int {
        val bytes = assetBytes ?: return 0
        val tag = "$assetPath#${bytes.size}"
        val previousSize = assetSizes[assetPath]
        val updateTag = previousSize?.let { "$tag#from=$it" } ?: tag
        // A corrected previous-size registration must retry a previously skipped old file.
        // Existing legacy tags still protect files the user has edited, but a missing file
        // is always recreated: bundled presets should not depend on the user never deleting one.
        val missing = !dest.isFile
        if (!missing && tag in handled && (updateTag in handled || previousSize == null ||
                    dest.length() != previousSize.toLong())) return 0
        // 内容有更新的内置资源（assetSizes 中登记了上一版字节数）：只有用户从未改过
        // 本地文件（大小仍等于上一版内置内容）时才原位替换；否则保留用户版本。
        if (previousSize != null && dest.exists() && dest.length() != previousSize.toLong()) {
            mark(tag, updateTag)
            return 1
        }
        if (missing || !dest.exists() || previousSize != null) {
            runCatching {
                dest.parentFile?.mkdirs()
                val tmp = File(dest.absolutePath + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(dest)) {
                    dest.writeBytes(bytes)
                    tmp.delete()
                }
            }.onFailure {
                Timber.w(it, "Failed to install bundled asset %s", assetPath)
                return 0
            }
            Timber.d("Installed bundled asset %s", assetPath)
        }
        mark(tag, updateTag)
        return 1
    }

    private fun readAsset(assetPath: String): ByteArray? {
        return runCatching {
            appContext.assets.open(assetPath).use { it.readBytes() }
        }.onFailure {
            Timber.w(it, "Failed to read bundled asset %s", assetPath)
        }.getOrNull()
    }

    /**
     * 首次铺完内置布局后，把"布局文件"选择指向第一份内置布局，让用户开机即得完整布局；
     * 用户已选择过其它 profile 时保持其选择不动。改名迁移每次都会执行；
     * [allowDefaultProfileSelection] 为 false 时连显式选择 "default" 也保留，
     * 避免每次启动都把默认布局改回第一套内置布局。
     */
    private fun ensureLayoutProfile(allowDefaultProfileSelection: Boolean) {
        val keyboardPrefs = AppPrefs.getInstance().keyboard
        val current = keyboardPrefs.textKeyboardLayoutProfile.getValue()
        // 蓝狐狸改名为普蓝：只有旧文件确实不存在（原版已在本轮退役）时才迁移。
        // 旧文件还在说明用户改过它，那是用户自己的布局，不能把选择悄悄换到另一份。
        if (current == "蓝狐狸") {
            val renamed = "普蓝"
            val oldFile = UserConfigFiles.textKeyboardLayoutJson(current)
            if (oldFile?.exists() != true &&
                UserConfigFiles.textKeyboardLayoutJson(renamed)?.exists() == true
            ) {
                keyboardPrefs.textKeyboardLayoutProfile.setValue(renamed)
                return
            }
        }
        if (current != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            // 用户已选择过：仅当指向的文件确实不存在时才回退
            val file = UserConfigFiles.textKeyboardLayoutJson(current)
            if (file?.exists() == true) return
        } else {
            // 显式选择 "default" 时保留其选择；首次铺内置布局时才自动落到第一套。
            // 同时不覆盖用户自己放在 config/TextKeyboardLayout.json 的默认布局。
            if (!allowDefaultProfileSelection) return
            if (UserConfigFiles.textKeyboardLayoutJson()?.exists() == true) return
        }
        val defaultProfile = layoutProfileOf(layoutAssets.first()) ?: return
        if (UserConfigFiles.textKeyboardLayoutJson(defaultProfile)?.exists() == true) {
            keyboardPrefs.textKeyboardLayoutProfile.setValue(defaultProfile)
        }
    }

    /**
     * 读取内置的 PopupPreset.json（默认弹出定义）。
     * 该文件不安装到用户目录：`config/PopupPreset.json` 一旦存在就会整体覆盖
     * 默认定义，因此内置定义只作为运行时 fallback 从 assets 直接读取。
     */
    fun readBundledPopupPreset(): Map<String, List<String>>? {
        val bytes = readAsset(POPUP_PRESET_ASSET) ?: return null
        return runCatching {
            val text = bytes.decodeToString()
            val stripped = org.fcitx.fcitx5.android.input.config.UserJsonConfigStore
                .stripLineComments(text)
            org.fcitx.fcitx5.android.input.config.UserJsonConfigStore.parser
                .decodeFromString<Map<String, List<String>>>(stripped)
        }.onFailure {
            Timber.w(it, "Failed to parse bundled popup preset")
        }.getOrNull()
    }
}
