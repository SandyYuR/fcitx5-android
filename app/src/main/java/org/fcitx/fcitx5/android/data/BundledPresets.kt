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
 *
 * 安装幂等且不覆盖：每个资源以"asset 路径 + 字节数"为版本标记记录在
 * SharedPreferences 中；目标文件已存在（用户自建或已安装）时绝不覆盖，
 * 用户删除已安装的文件后也不会被再次塞回。资源在后续版本更新时，修改
 * 文件内容（改变字节数）即可让新版本被重新安装。
 */
object BundledPresets {

    private const val PREF_NAME = "bundled_presets"
    private const val KEY_INSTALLED = "installed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /** 已处理资源标记集合（"asset路径#字节数"）。 */
    private val handled: MutableSet<String> by lazy {
        prefs.getStringSet(KEY_INSTALLED, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun mark(tag: String) {
        handled.add(tag)
        prefs.edit { putStringSet(KEY_INSTALLED, handled) }
    }

    /** Android assets 无法可靠列举中文文件名，直接维护清单最可靠。 */
    private val layoutAssets = listOf(
        "TextKeyboardLayout.大同-9+18+26keys.json",
        "TextKeyboardLayout.九键布局.json",
        "TextKeyboardLayout.行之-26键.json",
        "TextKeyboardLayout.行之-万象九键v2.json",
        "TextKeyboardLayout.QM-朝花.json",
        "TextKeyboardLayout.Sandy-数字行26+万象九键.json"
    )

    /**
     * 内置资源字节大小，作为安装器版本标记的一部分：更新某个资源的内容后
     * 同步更新这里的字节数，老用户才能收到新版本（见类注释的更新策略）。
     */
    private val assetSizes = mapOf(
        "bundled/keyboard_layouts/TextKeyboardLayout.大同-9+18+26keys.json" to 14611
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
        "wechat-light.json"
    )

    private val iconThemeAssets = listOf(
        "谷歌.json",
        "无题.json",
        "长日将烬.json"
    )

    const val POPUP_PRESET_ASSET = "bundled/keyboard_layouts/PopupPreset.json"

    /** 布局文件名 → profile 名（去掉 TextKeyboardLayout. 前缀与 .json 后缀）。 */
    fun layoutProfileOf(fileName: String): String? {
        if (!fileName.startsWith("TextKeyboardLayout.") || !fileName.endsWith(".json")) return null
        return fileName.removePrefix("TextKeyboardLayout.").removeSuffix(".json")
    }

    /**
     * 在后台安装内置资源。重复调用是安全的；安装失败只记录日志，不影响启动。
     */
    fun installAsync() {
        scope.launch {
            runCatching { install() }.onFailure {
                Timber.w(it, "Failed to install bundled presets")
            }
        }
    }

    private fun install() {
        val extDir = appContext.getExternalFilesDir(null) ?: return
        var installedCount = 0

        // 键盘布局：安装到 config/
        val configDir = UserConfigFiles.configDir() ?: File(extDir, "config").apply { mkdirs() }
        layoutAssets.forEach { name ->
            installAsset("bundled/keyboard_layouts/$name", File(configDir, name)).let { installedCount += it }
        }
        if (installedCount > 0) {
            ensureLayoutProfile()
        }

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

        if (installedCount > 0) {
            Timber.i("Bundled presets installed %d file(s)", installedCount)
        }
    }

    /**
     * 安装单个 asset。返回 1 表示本次实际写出了文件，0 表示跳过（已处理/已存在/读取失败）。
     */
    private fun installAsset(assetPath: String, dest: File): Int {
        val bytes = readAsset(assetPath) ?: return 0
        val tag = "$assetPath#${bytes.size}"
        if (tag in handled) return 0
        // 内容有更新的内置资源（assetSizes 中登记了新字节数）：只有用户从未改过
        // 本地文件（大小仍等于上一版内置内容）时才原位替换；否则保留用户版本。
        val newSize = assetSizes[assetPath]
        if (newSize != null && dest.exists() && dest.length() != newSize.toLong()) {
            mark(tag)
            return 1
        }
        if (!dest.exists() || newSize != null) {
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
        mark(tag)
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
     * 首次安装布局后，把"布局文件"选择指向第一份内置布局，让用户开机即得完整布局；
     * 用户已选择过其它 profile 时保持其选择不动。
     */
    private fun ensureLayoutProfile() {
        val keyboardPrefs = AppPrefs.getInstance().keyboard
        val current = keyboardPrefs.textKeyboardLayoutProfile.getValue()
        if (current != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            // 用户已选择过：仅当指向的文件确实不存在时才回退
            val file = UserConfigFiles.textKeyboardLayoutJson(current)
            if (file?.exists() == true) return
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
