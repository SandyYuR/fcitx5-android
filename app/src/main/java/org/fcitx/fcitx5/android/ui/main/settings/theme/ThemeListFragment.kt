/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeKits
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeMonet
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.input.config.UserJsonConfigStore
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.parcelable
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

class ThemeListFragment : Fragment() {

    private lateinit var imageLauncher: ActivityResultLauncher<Theme.Custom?>
    private lateinit var monetEditorLauncher: ActivityResultLauncher<Theme.Monet>

    private lateinit var importLauncher: ActivityResultLauncher<String>

    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var shareImportManager: ThemeShareImportManager

    private lateinit var themeListAdapter: ThemeListAdapter

    private var followSystemDayNightTheme by ThemeManager.prefs.followSystemDayNightTheme

    private var beingExported: Theme.Custom? = null

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        lifecycleScope.launch {
            updateSelectedThemes(it)
        }
    }

    @Keep
    private val onThemeListChangeListener = ThemeManager.OnThemeListChangeListener { themes ->
        lifecycleScope.launch {
            themeListAdapter.setThemes(themes)
            updateSelectedThemes()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareImportManager = ThemeShareImportManager(
            fragment = this,
            previewViewProvider = {
                parentFragment?.view?.findViewWithTag<View>("theme_preview_capture")
            }
        ) { newCreated, theme, migrated ->
            onThemeImported(newCreated, theme, migrated)
        }
        parentFragmentManager.setFragmentResultListener(REQUEST_THEME_IMPORTED, this) { _, bundle ->
            val theme = bundle.parcelable<Theme.Custom>(BUNDLE_THEME) ?: return@setFragmentResultListener
            val newCreated = bundle.getBoolean(BUNDLE_NEW_CREATED, false)
            val migrated = bundle.getBoolean(BUNDLE_MIGRATED, false)
            onThemeImported(newCreated, theme, migrated)
        }

        imageLauncher = registerForActivityResult(CustomThemeActivity.Contract()) { result ->
            if (result == null) return@registerForActivityResult
            // The editor has already persisted and published its transaction.
            themeListAdapter.setThemes(ThemeManager.getAllThemes())
            updateSelectedThemes()
        }
        monetEditorLauncher = registerForActivityResult(MonetThemeEditorActivity.Contract()) { result ->
            if (result == null) return@registerForActivityResult
            // refreshThemes() scans the theme directory on IO now (see D9).
            lifecycleScope.launch {
                ThemeManager.refreshThemes()
                updateSelectedThemes()
            }
        }
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val cr = ctx.contentResolver
                lifecycleScope.withLoadingDialog(ctx) {
                    val name = cr.queryFileName(uri) ?: return@withLoadingDialog
                    val ext = name.substringAfterLast('.')
                    if (ext != "zip") {
                        ctx.importErrorDialog(R.string.exception_theme_filename, ext)
                        return@withLoadingDialog
                    }
                    try {
                        val (_, _, migrated) = withContext(Dispatchers.IO) {
                            val zipBytes = cr.openInputStream(uri)!!.use(ThemeFilesManager::readArchive)
                            val decodedName = ThemeFilesManager.decodeTheme(ByteArrayInputStream(zipBytes))
                                .getOrNull()
                                ?.name
                            val importedName = decodedName?.let { ThemeManager.nonActiveImportName(it) }
                            ThemeFilesManager.importTheme(
                                ByteArrayInputStream(zipBytes),
                                importedName?.takeIf { it != decodedName }
                            ).getOrThrow()
                        }
                        ThemeManager.refreshThemes()
                        if (migrated) {
                            ctx.toast(R.string.theme_migrated)
                        }
                    } catch (e: Exception) {
                        ctx.importErrorDialog(e)
                    }
                }
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val exported = beingExported ?: return@registerForActivityResult
                beingExported = null
                lifecycleScope.withLoadingDialog(requireContext()) {
                    try {
                        withContext(Dispatchers.IO) {
                            val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                            ThemeFilesManager.exportTheme(exported, outputStream).getOrThrow()
                        }
                    } catch (e: Exception) {
                        ctx.toast(e)
                    }
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        themeListAdapter = object : ThemeListAdapter() {
            override fun onAddNewTheme() = addTheme()
            override fun onSelectTheme(theme: Theme) = selectTheme(theme)
            override fun onEditTheme(theme: Theme.Custom) = editTheme(theme)
            override fun onEditMonetTheme(theme: Theme.Monet) = editMonetTheme(theme)
            override fun onEditRandomTheme(theme: Theme.Custom) = editRandomTheme(theme)
            override fun onRandomizeTheme() = randomizeTheme()
            override fun onExportTheme(theme: Theme.Custom) = exportTheme(theme)
        }
        themeListAdapter.setThemes(ThemeManager.getAllThemes())
        updateSelectedThemes()
        lifecycleScope.launch {
            ThemeManager.ensureRandomSlotSeeded()
            themeListAdapter.setThemes(ThemeManager.getAllThemes())
            val themes = withContext(Dispatchers.IO) {
                ThemeFilesManager.listThemes()
            }
            ThemeManager.refreshThemes(themes)
        }
        return ResponsiveThemeListView(requireContext()).apply {
            adapter = themeListAdapter
            applyNavBarInsetsBottomPadding()
        }
    }

    override fun onStart() {
        super.onStart()
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        ThemeManager.addOnThemeListChangedListener(onThemeListChangeListener)
    }

    override fun onStop() {
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        ThemeManager.removeOnThemeListChangedListener(onThemeListChangeListener)
        super.onStop()
    }

    private fun updateSelectedThemes(activeTheme: Theme? = null) {
        val active = activeTheme ?: ThemeManager.activeTheme
        var light: Theme? = null
        var dark: Theme? = null
        if (followSystemDayNightTheme) {
            light = ThemeManager.prefs.lightModeTheme.getValue()
            dark = ThemeManager.prefs.darkModeTheme.getValue()
        }
        themeListAdapter.setSelectedThemes(active, light, dark)
    }

    private fun addTheme() {
        val ctx = requireContext()
        val actions = arrayOf(
            getString(R.string.choose_image),
            getString(R.string.import_from_file),
            getString(R.string.duplicate_builtin_theme),
            getString(R.string.theme_share_active),
            getString(R.string.theme_import_qr_scan),
            getString(R.string.theme_import_qr_image)
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.new_theme)
            .setNegativeButton(android.R.string.cancel, null)
            .setItems(actions) { _, i ->
                when (i) {
                    0 -> imageLauncher.launch(null)
                    1 -> importLauncher.launch("application/zip")
                    2 -> {
                        val view = ResponsiveThemeListView(ctx).apply {
                            // force AlertDialog's customPanel to grow
                            minimumHeight = Int.MAX_VALUE
                        }
                        val dialog = MaterialAlertDialogBuilder(ctx)
                            .setTitle(getString(R.string.duplicate_builtin_theme).removeSuffix("…"))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setView(view)
                            .create()
                        val duplicableThemes =
                            ThemeManager.BuiltinThemes + listOf(ThemeMonet.getLight(), ThemeMonet.getDark())
                        view.adapter = object :
                            SimpleThemeListAdapter<Theme>(duplicableThemes) {
                            override fun onClick(theme: Theme) {
                                val newTheme =
                                    when (theme) {
                                        is Theme.Builtin -> theme.deriveCustomNoBackground(UUID.randomUUID().toString())
                                        is Theme.Monet -> theme.toCustom().copy(name = UUID.randomUUID().toString())
                                        else -> return
                                    }
                                ThemeManager.saveTheme(newTheme)
                                dialog.dismiss()
                            }
                        }
                        dialog.show()
                    }
                    3 -> shareImportManager.shareActiveThemeFromMenu()
                    4 -> shareImportManager.importThemeByQrScan()
                    5 -> shareImportManager.importThemeByQrImage()
                }
            }
            .show()
    }

    private fun selectTheme(theme: Theme) {
        if (followSystemDayNightTheme) {
            val ctx = requireContext()
            MaterialAlertDialogBuilder(ctx)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.configure)
                .setMessage(R.string.theme_message_follow_system_day_night_mode_enabled)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.disable_it) { _, _ ->
                    followSystemDayNightTheme = false
                    updateSelectedThemes()
                    // 关掉跟随系统只是选择的前提；成套主题是否一并应用仍然要问一次。
                    continueSelectTheme(theme)
                }
                .show()
            return
        }
        continueSelectTheme(theme)
    }

    /** 主题选择的后半段：成套主题先问一次，避免只换了配色、布局与图标还停在上一套。 */
    private fun continueSelectTheme(theme: Theme) {
        val kit = ThemeKits.kitOf(theme)
        if (kit != null && !isKitApplied(kit)) {
            promptApplyKit(theme, kit)
            return
        }
        ThemeManager.setNormalModeTheme(theme)
    }

    /** 布局、图标主题与副字体都已经是这一套时不必再问——答案已经定了。 */
    private fun isKitApplied(kit: ThemeKits.Kit): Boolean =
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue() == kit.layoutProfile &&
                IconThemeManager.activeTheme.name == kit.iconTheme &&
                isKeyAltFontApplied(kit.keyAltFont)

    /**
     * 套装字体是否已经就是「按键副字体」。
     *
     * 字体文件还没落到 `fonts/` 时视为已应用：没有可应用的对象，反复弹框只会挡住主题切换
     * （首次启动的释放是异步的，用户可能先点到了主题卡片）。
     */
    private fun isKeyAltFontApplied(fontName: String?): Boolean {
        if (fontName == null) return true
        val file = UserConfigFiles.fontsDir()?.let { File(it, fontName) }
        if (file?.isFile != true) return true
        val current = ConfigProviders.readFontsetPathMapSnapshot().getOrNull()?.value
        return current?.get(KEY_ALT_FONT) == listOf(fontName)
    }

    private fun promptApplyKit(theme: Theme, kit: ThemeKits.Kit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.theme_kit_title)
            .setMessage(getString(R.string.theme_kit_message, theme.name))
            .setPositiveButton(R.string.theme_kit_apply_all) { _, _ -> applyKit(theme, kit) }
            // 取消只意味着「不要配套资源」，用户点的仍然是他要的那套配色。
            .setNegativeButton(R.string.theme_kit_theme_only) { _, _ ->
                ThemeManager.setNormalModeTheme(theme)
            }
            .show()
    }

    /**
     * 成套应用：布局、图标主题、副字体与操作键形状先就位，主题最后切。
     *
     * 主题变更会触发键盘整体重绘，放在最后只需一次刷新就能看到成套结果，
     * 不会先出现「新布局配旧配色」的中间态。资源缺失时跳过对应一项，
     * 避免把选择指向不存在的布局或字体。
     */
    private fun applyKit(theme: Theme, kit: ThemeKits.Kit) {
        if (UserConfigFiles.textKeyboardLayoutJson(kit.layoutProfile)?.isFile == true) {
            AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(kit.layoutProfile)
        }
        IconThemeManager.iconThemes.find { it.name == kit.iconTheme }?.let {
            IconThemeManager.selectTheme(it)
        }
        kit.keyAltFont?.let(::applyKeyAltFont)
        // 只覆盖偏好本身：用户之后仍可以在「键盘」里改回来，换主题不会反复重置。
        kit.specialKeyOvalShape?.let { ThemeManager.prefs.specialKeyOvalShape.setValue(it) }
        ThemeManager.setNormalModeTheme(theme)
    }

    /**
     * 把「按键副字体」指向套装自带字体。
     *
     * 只改这一项：用户在「字体设定」里配过的其它字体、字号原样保留，字体文件缺失时
     * 直接跳过（写进去只会得到一条指向不存在文件的配置）。写完标记字体需要刷新，
     * 键盘下次显示时按新字体重建，不必等用户手动去字体设定里点一次保存。
     */
    private fun applyKeyAltFont(fontName: String) {
        val fontFile = UserConfigFiles.fontsDir()?.let { File(it, fontName) }
        if (fontFile?.isFile != true) return
        val snapshot = ConfigProviders.readFontsetPathMapSnapshot()
        if (snapshot.isFailure) {
            // 配置损坏时保持原样：拿空配置去写，会把用户其它字体设置一起抹掉。
            Timber.w(snapshot.exceptionOrNull(), "Skip theme kit font: failed to read fontset config")
            return
        }
        val current = snapshot.getOrNull()?.value.orEmpty()
        if (current[KEY_ALT_FONT] == listOf(fontName)) return
        UserJsonConfigStore.writeFontsetPathMap(current + (KEY_ALT_FONT to listOf(fontName)))
            .onSuccess { FontProviders.markNeedsRefresh() }
            .onFailure { Timber.w(it, "Failed to apply theme kit font") }
    }

    private fun editTheme(theme: Theme.Custom) {
        imageLauncher.launch(theme)
    }

    private fun editRandomTheme(theme: Theme.Custom) {
        imageLauncher.launch(theme)
    }

    private fun randomizeTheme() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = ThemeManager.randomizeTheme()
            requireContext().toast(
                getString(
                    R.string.random_theme_toast,
                    result.total,
                    result.contrast,
                    result.hue,
                    result.sat,
                    result.light
                )
            )
            updateSelectedThemes()
        }
    }

    private fun editMonetTheme(theme: Theme.Monet) {
        // 设备不支持自定义映射时原先是静默 return：用户点了编辑按钮、什么也没发生，
        // 既不知道被拒绝也不知道为什么。改成明确提示。
        if (!ThemeMonet.supportsCustomMappingEditor(requireContext())) {
            requireContext().toast(R.string.monet_editor_unavailable)
            return
        }
        monetEditorLauncher.launch(theme)
    }

    private fun exportTheme(theme: Theme.Custom) {
        beingExported = theme
        exportLauncher.launch(theme.name + ".zip")
    }

    private fun onThemeImported(newCreated: Boolean, theme: Theme.Custom, migrated: Boolean) {
        // refreshThemes() scans the theme directory on IO now (see D9).
        lifecycleScope.launch {
            ThemeManager.refreshThemes()
            if (migrated) {
                requireContext().toast(R.string.theme_migrated)
            }
        }
    }

    companion object {
        /** 「字体设定」里按键副字体的键名，与 FontsetEditorActivity 的 FontEntry 一致。 */
        private const val KEY_ALT_FONT = "key_alt_font"

        const val REQUEST_THEME_IMPORTED = "theme_list_request_imported"
        const val BUNDLE_THEME = "theme_list_bundle_theme"
        const val BUNDLE_NEW_CREATED = "theme_list_bundle_new_created"
        const val BUNDLE_MIGRATED = "theme_list_bundle_migrated"
    }

}
