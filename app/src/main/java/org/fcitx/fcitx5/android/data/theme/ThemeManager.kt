/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.ThemeManager.activeTheme
import org.fcitx.fcitx5.android.ui.main.settings.theme.MonetThemePrefs
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.isDarkMode
import org.fcitx.fcitx5.android.utils.userManager

object ThemeManager {

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    fun interface OnThemeListChangeListener {
        fun onThemeListChange(themes: List<Theme>)
    }

    val BuiltinThemes = listOf(
        ThemePreset.MaterialLight,
        ThemePreset.MaterialDark,
        ThemePreset.PixelLight,
        ThemePreset.PixelDark,
        ThemePreset.NordLight,
        ThemePreset.NordDark,
        ThemePreset.DeepBlue,
        ThemePreset.Monokai,
        ThemePreset.AMOLEDBlack,
    )

    val DefaultTheme = ThemePreset.PixelDark

    private var monetThemes = defaultMonetThemes()

    private fun defaultMonetThemes(): List<Theme.Monet> {
        return listOf(ThemeMonet.getLight(), ThemeMonet.getDark())
    }

    private fun loadMonetThemes(): List<Theme.Monet> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !appContext.userManager.isUserUnlocked) {
            return defaultMonetThemes()
        }
        // 检查是否存在自定义映射配置
        val lightMapping = MonetThemePrefs.getMapping("MonetLight")
        val darkMapping = MonetThemePrefs.getMapping("MonetDark")
        val lightWaterRippleResource = MonetThemePrefs.getWaterRippleResource("MonetLight")
        val darkWaterRippleResource = MonetThemePrefs.getWaterRippleResource("MonetDark")
        
        val lightTheme = if (lightMapping != null) {
            ThemeMonet.createFromMapping(
                isDark = false,
                mapping = lightMapping,
                waterRippleResource = lightWaterRippleResource
            )
        } else {
            ThemeMonet.getLight()
        }
        
        val darkTheme = if (darkMapping != null) {
            ThemeMonet.createFromMapping(
                isDark = true,
                mapping = darkMapping,
                waterRippleResource = darkWaterRippleResource
            )
        } else {
            ThemeMonet.getDark()
        }
        
        return listOf(lightTheme, darkTheme)
    }

    const val RANDOM_THEME_NAME = "随机主题"

    private val randomThemeJson = Json { ignoreUnknownKeys = true }
    private val customThemes: MutableList<Theme.Custom> =
        ThemeFilesManager.listThemes().filter { it.name != RANDOM_THEME_NAME }.toMutableList()
    private const val RANDOM_THEME_PREF = "theme_random_slot"
    private val randomThemePrefs by lazy {
        PreferenceManager.getDefaultSharedPreferences(appContext)
    }
    private var randomSlot: RandomThemeSlot? = loadRandomSlot()

    private fun randomThemeOrNull(): Theme.Custom? = randomSlot?.theme?.takeIf {
        it.name == RANDOM_THEME_NAME
    }

    fun isRandomTheme(theme: Theme): Boolean = theme.name == RANDOM_THEME_NAME

    val currentRandomTheme: Theme.Custom?
        get() = randomThemeOrNull()

    val randomThemeCandidate: ThemeCandidate?
        get() = randomSlot?.candidate

    val randomThemeScore: ThemeScore?
        get() = randomSlot?.score ?: randomThemeOrNull()?.let { scoreCustomTheme(it).second }

    val currentRandomResult: RandomThemeResult?
        get() {
            val slot = randomSlot ?: return null
            val candidate = slot.candidate
            if (candidate != null) {
                return RandomThemeResult(
                    theme = slot.theme,
                    candidate = candidate,
                    score = ThemeAestheticScore.scoreTheme(candidate),
                    attempts = slot.attempts
                )
            }
            val (projected, score) = scoreCustomTheme(slot.theme)
            return RandomThemeResult(slot.theme, projected, score, slot.attempts)
        }

    fun randomizeTheme(): RandomThemeResult {
        val result = ThemeRandomizer.generateBestRandomTheme(name = RANDOM_THEME_NAME)
        randomSlot = RandomThemeSlot(result.theme, result.candidate, result.score, result.attempts)
        persistRandomSlot(requireNotNull(randomSlot))
        applyRandomTheme(result.theme)
        return result
    }

    fun updateRandomTheme(
        theme: Theme.Custom,
        candidate: ThemeCandidate? = null,
        attempts: Int = randomSlot?.attempts ?: 1
    ): RandomThemeResult {
        val namedTheme = theme.copy(name = RANDOM_THEME_NAME)
        val scheme = candidate?.scheme ?: randomSlot?.candidate?.scheme ?: "mono"
        val (projectedCandidate, projectedScore) = scoreCustomTheme(namedTheme, scheme)
        val storedCandidate = candidate ?: projectedCandidate
        val score = candidate?.let { ThemeAestheticScore.scoreTheme(it) } ?: projectedScore
        val slot = RandomThemeSlot(namedTheme, storedCandidate, score, attempts.coerceAtLeast(1))
        randomSlot = slot
        persistRandomSlot(slot)
        applyRandomTheme(namedTheme)
        return RandomThemeResult(namedTheme, storedCandidate, score, slot.attempts)
    }

    private fun applyRandomTheme(theme: Theme.Custom) {
        if (prefs.followSystemDayNightTheme.getValue()) {
            prefs.followSystemDayNightTheme.setValue(false)
        }
        setNormalModeTheme(theme)
        fireThemeListChange()
    }

    fun scoreTheme(theme: Theme.Custom): ThemeScore =
        scoreCustomTheme(theme, randomSlot?.candidate?.scheme ?: "mono").second

    private fun scoreCustomTheme(
        theme: Theme.Custom,
        scheme: String = "mono"
    ): Pair<ThemeCandidate, ThemeScore> {
        fun color(value: Int): String = String.format(Locale.ROOT, "#%06x", value and 0x00ffffff)
        val colors = linkedMapOf(
            WebsiteThemeColorKeys.KEY_BACKGROUND to color(theme.keyBackgroundColor),
            WebsiteThemeColorKeys.KEY_PRESSED to color(theme.keyPressHighlightColor),
            WebsiteThemeColorKeys.KEY_BORDER_STROKE to color(theme.keyShadowColor),
            WebsiteThemeColorKeys.KEY_TEXT to color(theme.keyTextColor),
            WebsiteThemeColorKeys.SPECIAL_KEY_BACKGROUND to color(theme.altKeyBackgroundColor),
            WebsiteThemeColorKeys.SPECIAL_KEY_PRESSED to color(theme.keyPressHighlightColor),
            WebsiteThemeColorKeys.SPECIAL_KEY_BORDER_STROKE to color(theme.keyShadowColor),
            WebsiteThemeColorKeys.SPECIAL_KEY_TEXT to color(theme.altKeyTextColor),
            WebsiteThemeColorKeys.ACCENT_KEY_BACKGROUND to color(theme.accentKeyBackgroundColor),
            WebsiteThemeColorKeys.ACCENT_KEY_PRESSED to color(theme.waterRippleColor ?: theme.accentKeyBackgroundColor),
            WebsiteThemeColorKeys.ACCENT_KEY_BORDER_STROKE to color(theme.keyShadowColor),
            WebsiteThemeColorKeys.ACCENT_KEY_TEXT to color(theme.accentKeyTextColor),
            WebsiteThemeColorKeys.ALT_TEXT to color(theme.altKeyTextColor),
            WebsiteThemeColorKeys.BACKGROUND to color(theme.keyboardColor),
            WebsiteThemeColorKeys.PANEL_BACKGROUND to color(theme.barColor),
            WebsiteThemeColorKeys.PANEL_TOOLBAR_TEXT to color(theme.candidateTextColor),
            WebsiteThemeColorKeys.PANEL_TOOLBAR_ACTIVED to color(theme.genericActiveBackgroundColor),
            WebsiteThemeColorKeys.PANEL_TOOLBAR_ICON to color(theme.candidateLabelColor),
            WebsiteThemeColorKeys.PANEL_CANDIDATE_BACKGROUND to color(theme.barColor),
            WebsiteThemeColorKeys.PANEL_CANDIDATE_TEXT to color(theme.candidateTextColor),
            WebsiteThemeColorKeys.PANEL_CANDIDATE_INDEX to color(theme.candidateLabelColor),
            WebsiteThemeColorKeys.PANEL_CANDIDATE_DIVIDER to color(theme.dividerColor),
            WebsiteThemeColorKeys.PANEL_TOOLBAR_PRESSED to color(theme.spaceBarColor),
            WebsiteThemeColorKeys.PINNER_BACKGROUND to color(theme.popupBackgroundColor),
            WebsiteThemeColorKeys.PINNER_TEXT_COLOR to color(theme.popupTextColor),
            WebsiteThemeColorKeys.PINNER_SECONDARY_TEXT_COLOR to color(theme.candidateCommentColor),
            WebsiteThemeColorKeys.TOAST_BACKGROUND to color(theme.popupBackgroundColor),
            WebsiteThemeColorKeys.TOAST_TEXT to color(theme.popupTextColor)
        )
        val candidate = ThemeCandidate(
            colors = colors,
            scheme = scheme,
            isDark = theme.isDark
        )
        return candidate to ThemeAestheticScore.scoreTheme(candidate)
    }

    private fun loadRandomSlot(): RandomThemeSlot? {
        val raw = randomThemePrefs.getString(RANDOM_THEME_PREF, null) ?: return null
        return runCatching {
            val stored = randomThemeJson.decodeFromString<StoredRandomTheme>(raw)
            val (theme, _) = randomThemeJson.decodeFromString(
                CustomThemeSerializer.WithMigrationStatus,
                stored.themeJson
            )
            if (theme.name != RANDOM_THEME_NAME) return@runCatching null
            val candidate = stored.candidateColors?.let { colors ->
                ThemeCandidate(
                    colors = colors,
                    scheme = stored.scheme ?: "mono",
                    baseHue = stored.baseHue ?: 0.0,
                    accentHue = stored.accentHue ?: 0.0,
                    baseSat = stored.baseSat ?: 0.0,
                    isDark = stored.isDark ?: theme.isDark,
                    surfaceStyle = stored.surfaceStyle ?: "Raised"
                )
            }
            val (projected, projectedScore) = scoreCustomTheme(theme, candidate?.scheme ?: "mono")
            RandomThemeSlot(
                theme = theme,
                candidate = candidate ?: projected,
                score = candidate?.let { ThemeAestheticScore.scoreTheme(it) } ?: projectedScore,
                attempts = stored.attempts.coerceAtLeast(1)
            )
        }.getOrElse {
            randomThemePrefs.edit { remove(RANDOM_THEME_PREF) }
            null
        }
    }

    private fun persistRandomSlot(slot: RandomThemeSlot) {
        val candidate = slot.candidate
        val stored = StoredRandomTheme(
            themeJson = randomThemeJson.encodeToString(CustomThemeSerializer, slot.theme),
            candidateColors = candidate?.colors,
            scheme = candidate?.scheme,
            baseHue = candidate?.baseHue,
            accentHue = candidate?.accentHue,
            baseSat = candidate?.baseSat,
            isDark = candidate?.isDark,
            surfaceStyle = candidate?.surfaceStyle,
            attempts = slot.attempts
        )
        randomThemePrefs.edit { putString(RANDOM_THEME_PREF, randomThemeJson.encodeToString(stored)) }
    }

    private data class RandomThemeSlot(
        val theme: Theme.Custom,
        val candidate: ThemeCandidate?,
        val score: ThemeScore?,
        val attempts: Int
    )

    @Serializable
    private data class StoredRandomTheme(
        val themeJson: String,
        val candidateColors: Map<String, String>? = null,
        val scheme: String? = null,
        val baseHue: Double? = null,
        val accentHue: Double? = null,
        val baseSat: Double? = null,
        val isDark: Boolean? = null,
        val surfaceStyle: String? = null,
        val attempts: Int = 1
    )

    fun getTheme(name: String) =
        randomThemeOrNull()?.takeIf { it.name == name }
            ?: customThemes.find { it.name == name }
            ?: monetThemes.find { it.name == name }
            ?: BuiltinThemes.find { it.name == name }

    fun getAllThemes(): List<Theme> = buildList {
        randomThemeOrNull()?.let(::add)
        addAll(customThemes)
        addAll(monetThemes)
        addAll(BuiltinThemes)
    }

    /**
     * Rescan the theme directory and publish the result.
     *
     * Suspend because [ThemeFilesManager.listThemes] walks the directory, reads and parses each
     * theme file, and may write migrated files back — all on the calling thread, which for every
     * caller was the main thread (see D9). The publish half still happens on the main thread.
     */
    suspend fun refreshThemes() {
        val themes = withContext(Dispatchers.IO) { ThemeFilesManager.listThemes() }
        refreshThemes(themes)
    }

    fun refreshThemes(refreshedCustomThemes: List<Theme.Custom>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refreshThemes(refreshedCustomThemes) }
            return
        }
        applyRefreshedThemes(refreshedCustomThemes)
    }

    @MainThread
    private fun applyRefreshedThemes(refreshedCustomThemes: List<Theme.Custom>) {
        customThemes.clear()
        customThemes.addAll(refreshedCustomThemes.filter { it.name != RANDOM_THEME_NAME })
        monetThemes = loadMonetThemes()
        activeTheme = evaluateActiveTheme()
        fireThemeListChange()
    }

    /**
     * [backing property](https://kotlinlang.org/docs/properties.html#backing-properties)
     * of [activeTheme]; holds the [Theme] object currently in use
     */
    private lateinit var _activeTheme: Theme

    var activeTheme: Theme
        get() = _activeTheme
        private set(value) {
            if (_activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private var isDarkMode = false

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()
    private val onThemeListChangeListeners = WeakHashSet<OnThemeListChangeListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    fun addOnThemeListChangedListener(listener: OnThemeListChangeListener) {
        onThemeListChangeListeners.add(listener)
    }

    fun removeOnThemeListChangedListener(listener: OnThemeListChangeListener) {
        onThemeListChangeListeners.remove(listener)
    }

    private fun fireChange() {
        val theme = _activeTheme
        dispatchOnMain {
            onChangeListeners.toList().forEach { it.onThemeChange(theme) }
        }
    }

    private fun fireThemeListChange() {
        val themes = getAllThemes().toList()
        dispatchOnMain {
            onThemeListChangeListeners.toList().forEach { it.onThemeListChange(themes) }
        }
    }

    private inline fun dispatchOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    val prefs = AppPrefs.getInstance().registerProvider(::ThemePrefs)

    fun saveTheme(theme: Theme.Custom) {
        require(theme.name != RANDOM_THEME_NAME) { "The random theme must be copied before saving" }
        ThemeFilesManager.saveThemeFiles(theme)
        customThemes.indexOfFirst { it.name == theme.name }.also {
            if (it >= 0) customThemes[it] = theme else customThemes.add(0, theme)
        }
        if (activeTheme.name == theme.name) {
            activeTheme = theme
        }
        fireThemeListChange()
    }

    fun nonActiveImportName(name: String): String {
        val base = if (name == RANDOM_THEME_NAME) "$name (imported)" else name
        if (activeTheme.name != base || getTheme(base) !is Theme.Custom) return base
        val imported = "$base (imported)"
        if (getTheme(imported) == null) return imported
        var index = 2
        while (true) {
            val candidate = "$imported $index"
            if (getTheme(candidate) == null) return candidate
            index++
        }
    }

    fun deleteTheme(name: String) {
        if (name == RANDOM_THEME_NAME) return
        customThemes.find { it.name == name }?.also {
            // Pass all themes except the one being deleted, so we can clean up unused directories
            val otherThemes = customThemes.filter { it.name != name }
            ThemeFilesManager.deleteThemeFiles(it, otherThemes)
            customThemes.remove(it)
        }
        if (activeTheme.name == name) {
            activeTheme = evaluateActiveTheme()
        }
        fireThemeListChange()
    }

    fun setNormalModeTheme(theme: Theme) {
        // Apply through the active-theme setter first so visible keyboard/candidate views refresh
        // immediately. The preference write keeps the selection across IME recreation; its change
        // callback sees the same active theme and does not emit a second change.
        activeTheme = theme
        prefs.normalModeTheme.setValue(theme)
    }

    fun isUsingConfiguredDarkTheme(): Boolean =
        activeTheme.name == prefs.darkModeTheme.getValue().name

    /**
     * Switch to the opposite configured day/night theme and disable system following so the
     * selection is immediately visible and remains active after the IME is recreated.
     */
    fun toggleConfiguredDayNightTheme(): Theme {
        val nextTheme = if (isUsingConfiguredDarkTheme()) {
            prefs.lightModeTheme.getValue()
        } else {
            prefs.darkModeTheme.getValue()
        }
        if (prefs.followSystemDayNightTheme.getValue()) {
            prefs.followSystemDayNightTheme.setValue(false)
        }
        setNormalModeTheme(nextTheme)
        return nextTheme
    }

    private fun evaluateActiveTheme(): Theme {
        return if (prefs.followSystemDayNightTheme.getValue()) {
            if (isDarkMode) prefs.darkModeTheme else prefs.lightModeTheme
        } else {
            prefs.normalModeTheme
        }.getValue()
    }

    @Keep
    private val onThemePrefsChange = ManagedPreferenceProvider.OnChangeListener { key ->
        if (prefs.dayNightModePrefNames.contains(key)) {
            activeTheme = evaluateActiveTheme()
        } else {
            fireChange()
        }
    }

    fun init(configuration: Configuration) {
        isDarkMode = configuration.isDarkMode()
        monetThemes = loadMonetThemes()
        // fire all `OnThemeChangedListener`s on theme preferences change
        prefs.registerOnChangeListener(onThemePrefsChange)
        _activeTheme = evaluateActiveTheme()
        // 新装/升级用户从未用过随机主题时，主题列表也要直接出现随机主题卡片：
        // 这里仅生成并持久化槽位，不切换用户当前主题。
        ensureRandomSlotSeeded()
    }

    /**
     * 保证随机主题槽位存在；存在则直接返回，不覆盖已有结果。
     *
     * 仅生成并持久化，不调用 applyRandomTheme()——不能在用户只是打开主题列表时
     * 擅自切换他正在用的主题。
     */
    fun ensureRandomSlotSeeded(): RandomThemeResult {
        randomSlot?.let { slot ->
            val candidate = slot.candidate
            val score = slot.score
                ?: candidate?.let { ThemeAestheticScore.scoreTheme(it) }
                ?: scoreCustomTheme(slot.theme, candidate?.scheme ?: "mono").second
            return RandomThemeResult(slot.theme, candidate ?: scoreCustomTheme(slot.theme).first, score, slot.attempts)
        }
        val result = ThemeRandomizer.generateBestRandomTheme(name = RANDOM_THEME_NAME)
        randomSlot = RandomThemeSlot(result.theme, result.candidate, result.score, result.attempts)
        persistRandomSlot(requireNotNull(randomSlot))
        return result
    }

    fun onSystemPlatteChange(newConfig: Configuration) {
        isDarkMode = newConfig.isDarkMode()
        // 重新加载 Monet 主题（包括自定义映射）
        monetThemes = loadMonetThemes()
        // `ManagedThemePreference` finds a theme with same name in `getAllThemes()`
        // thus `evaluateActiveTheme()` should be called after updating `monetThemes`
        activeTheme = evaluateActiveTheme()
        fireThemeListChange()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            prefs.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }

}
