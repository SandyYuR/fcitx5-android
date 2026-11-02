/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.io.File

typealias ConfigChangeListener = () -> Unit

interface ConfigProvider {
    fun textKeyboardLayoutFile(): File?
    fun textKeyboardLayoutJson(): JsonObject? = null
    fun popupPresetFile(): File?
    fun fontsetFile(): File?
    fun buttonsLayoutConfigFile(): File?
    fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File>
}

object DefaultConfigProvider : ConfigProvider {
    override fun textKeyboardLayoutFile(): File? {
        if (FcitxApplication.getInstance().isDirectBootMode) {
            return UserConfigFiles.directBootTextKeyboardLayoutJson()?.takeIf { it.isFile }
        }
        val profile = AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
        return UserConfigFiles.textKeyboardLayoutJson(profile)
    }
    override fun popupPresetFile(): File? = UserConfigFiles.popupPresetJson()
    override fun fontsetFile(): File? = UserConfigFiles.fontsetJson()
    override fun buttonsLayoutConfigFile(): File? = UserConfigFiles.buttonsLayoutConfig()
    override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> =
        UserJsonConfigStore.writeFontsetPathMap(pathMap)
}

object ConfigProviders {
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val FILE_EVENTS = FileObserver.CLOSE_WRITE or FileObserver.MODIFY or
        FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.CREATE or
        FileObserver.DELETE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

    @Volatile
    var provider: ConfigProvider = DefaultConfigProvider
        set(value) {
            field = value
            restartWatching()
        }

    @Volatile
    private var configWatcher: DirectoryObserverRegistry.Subscription? = null

    @Volatile
    private var fontWatcher: DirectoryObserverRegistry.Subscription? = null
    
    @Volatile
    private var iconWatcher: DirectoryObserverRegistry.Subscription? = null

    @Volatile
    private var watchedConfigDir: String? = null

    @Volatile
    private var watchedTextLayoutFileName: String? = null

    @Volatile
    private var watchedPopupPresetFileName: String? = null

    @Volatile
    private var watchedFontDir: String? = null

    @Volatile
    private var watchedFontsetFileName: String? = null
    
    @Volatile
    private var watchedButtonsLayoutFileName: String? = null

    @Volatile
    private var watchedIconBaseDir: String? = null

    @Volatile
    private var watchedIconFileNames: Set<String> = emptySet()

    private val textLayoutListeners = linkedSetOf<() -> Unit>()
    private val popupPresetListeners = linkedSetOf<() -> Unit>()
    private val fontsetListeners = linkedSetOf<() -> Unit>()
    private val buttonsLayoutListeners = linkedSetOf<() -> Unit>()
    private val iconChangeListeners = linkedSetOf<() -> Unit>()

    @Synchronized
    private fun notifyListeners(listeners: Set<() -> Unit>) {
        if (listeners.isEmpty()) return
        val snapshot = listeners.toList()
        mainHandler.post {
            snapshot.forEach { listener ->
                runCatching { listener() }
            }
        }
    }

    @Synchronized
    fun addTextKeyboardLayoutListener(listener: () -> Unit) {
        textLayoutListeners.add(listener)
        ensureWatching()
    }

    @Synchronized
    fun addPopupPresetListener(listener: () -> Unit) {
        popupPresetListeners.add(listener)
        ensureWatching()
    }

    @Synchronized
    fun addFontsetListener(listener: () -> Unit) {
        fontsetListeners.add(listener)
        ensureWatching()
    }

    @Synchronized
    fun addButtonsLayoutListener(listener: () -> Unit) {
        buttonsLayoutListeners.add(listener)
        ensureWatching()
    }

    @Synchronized
    fun removeButtonsLayoutListener(listener: () -> Unit) {
        buttonsLayoutListeners.remove(listener)
    }

    @Synchronized
    fun addIconChangeListener(listener: () -> Unit) {
        iconChangeListeners.add(listener)
        ensureWatching()
    }

    @Synchronized
    fun removeIconChangeListener(listener: () -> Unit) {
        iconChangeListeners.remove(listener)
    }

    fun setWatchedIconFileNames(names: Set<String>) {
        watchedIconFileNames = names
    }

    @Synchronized
    private fun restartWatching() {
        configWatcher?.close()
        fontWatcher?.close()
        iconWatcher?.close()
        configWatcher = null
        fontWatcher = null
        iconWatcher = null
        watchedConfigDir = null
        watchedTextLayoutFileName = null
        watchedPopupPresetFileName = null
        watchedButtonsLayoutFileName = null
        watchedFontDir = null
        watchedFontsetFileName = null
        watchedIconBaseDir = null
        ensureWatching()
    }

    @Synchronized
    fun ensureWatching() {
        // Skip file watching when provider uses in-memory JSON (e.g., preview scenarios)
        // This avoids null pointer exceptions and unnecessary file monitoring
        if (provider.textKeyboardLayoutJson() != null) {
            return
        }

        val hasConfigListeners = textLayoutListeners.isNotEmpty() || popupPresetListeners.isNotEmpty() ||
                buttonsLayoutListeners.isNotEmpty() || iconChangeListeners.isNotEmpty()
        val hasFontsetListeners = fontsetListeners.isNotEmpty()

        if (!hasConfigListeners && !hasFontsetListeners) return

        val textFile = provider.textKeyboardLayoutFile()
        val popupFile = provider.popupPresetFile()
        val buttonsLayoutFile = provider.buttonsLayoutConfigFile()
        val configDir = textFile?.parentFile ?: popupFile?.parentFile ?: buttonsLayoutFile?.parentFile
        val hasButtonsListeners = buttonsLayoutListeners.isNotEmpty()

        if (hasConfigListeners && configDir != null) {
            val dirPath = configDir.absolutePath
            val textFileName = textFile?.name
            val popupFileName = popupFile?.name
            val buttonsLayoutFileName = buttonsLayoutFile?.name

            if (!(configWatcher?.isWatching == true &&
                        watchedConfigDir == dirPath &&
                        watchedTextLayoutFileName == textFileName &&
                        watchedPopupPresetFileName == popupFileName &&
                        watchedButtonsLayoutFileName == buttonsLayoutFileName)
            ) {
                configWatcher?.close()
                watchedConfigDir = dirPath
                watchedTextLayoutFileName = textFileName
                watchedPopupPresetFileName = popupFileName
                watchedButtonsLayoutFileName = buttonsLayoutFileName
                configWatcher = DirectoryObservers.shared.subscribe(configDir, FILE_EVENTS) { _, path ->
                    if (path == null || path == watchedTextLayoutFileName) {
                        notifyListeners(textLayoutListeners)
                    }
                    if (path == null || path == watchedPopupPresetFileName) {
                        notifyListeners(popupPresetListeners)
                    }
                    if (path == null || path == watchedButtonsLayoutFileName) {
                        notifyListeners(buttonsLayoutListeners)
                    }
                }
            }
        } else if (!hasConfigListeners) {
            configWatcher?.close()
            configWatcher = null
            watchedConfigDir = null
            watchedTextLayoutFileName = null
            watchedPopupPresetFileName = null
            watchedButtonsLayoutFileName = null
        }

        val hasIconWatcherListeners = hasButtonsListeners || iconChangeListeners.isNotEmpty()
        val iconBaseDir = buttonsLayoutFile?.parentFile?.parentFile?.let { File(it, ButtonIconFile.DIR) }
        if (hasIconWatcherListeners && iconBaseDir != null) {
            val dirPath = iconBaseDir.absolutePath
            if (!(iconWatcher?.isWatching == true && watchedIconBaseDir == dirPath)) {
                iconWatcher?.close()
                iconWatcher = DirectoryObservers.shared.subscribe(iconBaseDir, FILE_EVENTS) { _, path ->
                    val watched = watchedIconFileNames
                    if (path == null || watched.isEmpty() || path in watched) {
                        notifyListeners(iconChangeListeners)
                    }
                }
                watchedIconBaseDir = dirPath
            }
        } else if (!hasIconWatcherListeners) {
            iconWatcher?.close()
            iconWatcher = null
            watchedIconBaseDir = null
        }

        val fontsetFile = provider.fontsetFile()
        val fontDir = fontsetFile?.parentFile
        if (hasFontsetListeners && fontDir != null) {
            val dirPath = fontDir.absolutePath
            val fileName = fontsetFile.name
            if (!(fontWatcher?.isWatching == true && watchedFontDir == dirPath && watchedFontsetFileName == fileName)) {
                fontWatcher?.close()
                watchedFontDir = dirPath
                watchedFontsetFileName = fileName
                fontWatcher = DirectoryObservers.shared.subscribe(fontDir, FILE_EVENTS) { _, path ->
                    if (path == null || path == watchedFontsetFileName) {
                        notifyListeners(fontsetListeners)
                    }
                }
            }
        } else if (!hasFontsetListeners) {
            fontWatcher?.close()
            fontWatcher = null
            watchedFontDir = null
            watchedFontsetFileName = null
        }
    }

    inline fun <reified T> readTextKeyboardLayout(): UserJsonConfigStore.JsonSnapshot<T>? {
        // Try to read from in-memory JSON first to avoid disk I/O
        val memoryJson = provider.textKeyboardLayoutJson()
        if (memoryJson != null) {
            return UserJsonConfigStore.readJson<T>(memoryJson).also { ensureWatching() }
        }
        // Fallback to file-based reading. stripLineComments matches the editor, which both
        // accepts and preserves `//` comments (LayoutDataManager.removeJsonComments); without
        // it a commented layout parses in the editor but silently falls back to the built-in
        // QWERTY layout on the real keyboard.
        return UserJsonConfigStore.readJson<T>(
            provider.textKeyboardLayoutFile(),
            stripLineComments = true
        ).also { ensureWatching() }
    }

    inline fun <reified T> readPopupPreset(): UserJsonConfigStore.JsonSnapshot<T>? =
        UserJsonConfigStore.readJson<T>(
            provider.popupPresetFile(),
            stripLineComments = true
        ).also { ensureWatching() }

    /**
     * Read unified buttons layout configuration.
     * Supports migration from old separate config files.
     */
    inline fun <reified T> readButtonsLayoutConfig(): UserJsonConfigStore.JsonSnapshot<T>? =
        UserJsonConfigStore.readJson<T>(provider.buttonsLayoutConfigFile()).also { ensureWatching() }

    /**
     * @deprecated Use readButtonsLayoutConfig instead
     */
    @Deprecated("Use readButtonsLayoutConfig instead", ReplaceWith("readButtonsLayoutConfig()"))
    inline fun <reified T> readKawaiiBarButtonsConfig(): UserJsonConfigStore.JsonSnapshot<T>? =
        readButtonsLayoutConfig()

    /**
     * @deprecated Use readButtonsLayoutConfig instead
     */
    @Deprecated("Use readButtonsLayoutConfig instead", ReplaceWith("readButtonsLayoutConfig()"))
    inline fun <reified T> readStatusAreaButtonsConfig(): UserJsonConfigStore.JsonSnapshot<T>? =
        readButtonsLayoutConfig()

    fun readFontsetPathMapSnapshot(): Result<UserJsonConfigStore.JsonSnapshot<Map<String, List<String>>>?> =
        runCatching {
            ensureWatching()
            UserJsonConfigStore.readFontsetPathMapSnapshot().getOrNull()
        }
}
