/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings

import android.net.Uri
import android.os.Parcelable
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.createGraph
import androidx.navigation.fragment.fragment
import androidx.savedstate.SavedState
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.ui.main.AboutFragment
import org.fcitx.fcitx5.android.ui.main.DeveloperFragment
import org.fcitx.fcitx5.android.ui.main.LicensesFragment
import org.fcitx.fcitx5.android.ui.main.MainFragment
import org.fcitx.fcitx5.android.ui.main.settings.addon.AddonConfigFragment
import org.fcitx.fcitx5.android.ui.main.settings.addon.AddonListFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.AdvancedSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.CandidatesSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ClipboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.DataBackupFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardGroupFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardModesFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SymbolSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.global.GlobalConfigFragment
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupFragment
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupSpecs
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodConfigFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodListFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeFragment
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor
import org.fcitx.fcitx5.android.utils.parcelable
import kotlin.reflect.KType
import kotlin.reflect.typeOf

@Parcelize
sealed class SettingsRoute : Parcelable {

    /* ========== Index ========== */

    @Serializable
    data object Index : SettingsRoute()

    /* ========== Fcitx ========== */

    @Serializable
    data object GlobalConfig : SettingsRoute()

    @Serializable
    data object InputMethodList : SettingsRoute()

    @Serializable
    data class InputMethodConfig(val name: String, val uniqueName: String) : SettingsRoute()

    @Serializable
    data object AddonList : SettingsRoute()

    @Serializable
    data class AddonConfig(val name: String, val uniqueName: String) : SettingsRoute()

    /* ========== Android ========== */

    /**
     * 首页一级入口下的分组页，由 [SettingsGroupSpecs] 驱动。
     * 用 id 标识分组，避免为每个分组各建一个路由类与 Fragment。
     */
    @Serializable
    data class SettingsGroup(val id: String) : SettingsRoute()

    @Serializable
    data object Theme : SettingsRoute()

    @Serializable
    data object IconTheme : SettingsRoute()

    @Serializable
    data object VirtualKeyboard : SettingsRoute()

    @Serializable
    data class KeyboardGroup(val group: Int) : SettingsRoute()

    @Serializable
    data object CandidatesWindow : SettingsRoute()

    @Serializable
    data object Clipboard : SettingsRoute()

    @Serializable
    data object Symbol : SettingsRoute()

    /** 浮动键盘与单手键盘。原先没有任何设置入口，只能靠工具栏按钮触发。 */
    @Serializable
    data object KeyboardModes : SettingsRoute()

    /** 数据与备份：从「高级」拆出，避免与兼容性开关混在一起。 */
    @Serializable
    data object DataBackup : SettingsRoute()

    @Serializable
    data object Advanced : SettingsRoute()

    @Serializable
    data object Developer : SettingsRoute()

    @Serializable
    data object License : SettingsRoute()

    @Serializable
    data object About : SettingsRoute()

    /* ========== External ========== */

    @Serializable
    data class ListConfig(val params: Params) : SettingsRoute() {
        @Parcelize
        @Serializable
        data class Params(val cfg: RawConfig, val desc: ConfigDescriptor<*, *>) : Parcelable {
            companion object {
                // https://developer.android.com/guide/navigation/design/kotlin-dsl#custom-types
                val NavType = object : NavType<Params>(isNullableAllowed = false) {
                    override fun put(bundle: SavedState, key: String, value: Params) {
                        bundle.putParcelable(key, value)
                    }

                    override fun get(bundle: SavedState, key: String): Params? {
                        return bundle.parcelable<Params>(key)
                    }

                    override fun serializeAsValue(value: Params): String {
                        // Serialized values must always be Uri encoded
                        return Uri.encode(Json.encodeToString(value))
                    }

                    override fun parseValue(value: String): Params {
                        // Navigation decodes the string before passing it to parseValue()
                        return Json.decodeFromString(value)
                    }
                }

                val TypeMap: Map<KType, NavType<*>> =
                    mapOf(typeOf<Params>() to NavType)
            }
        }

        constructor(cfg: RawConfig, desc: ConfigDescriptor<*, *>) : this(Params(cfg, desc))

        val desc: ConfigDescriptor<*, *>
            get() = params.desc
        val cfg: RawConfig
            get() = params.cfg
    }

    @Serializable
    data class Punctuation(val title: String, val lang: String? = null) : SettingsRoute()

    @Serializable
    data class MultiSelect(
        val title: String,
        val addon: String,
        val path: String,
        val option: String,
        val min: Int = 0
    ) : SettingsRoute()

    @Serializable
    data class AddonDirProfileManager(
        val title: String,
        val addon: String,
        val path: String
    ) : SettingsRoute()

    companion object {
        fun createGraph(controller: NavController) = controller.createGraph(Index) {
            val ctx = controller.context
            val appLabel = AppUtil.appLabel(ctx)

            /* ========== Index ========== */

            fragment<MainFragment, Index> {
                label = appLabel
            }

            /* ========== Fcitx ========== */

            fragment<GlobalConfigFragment, GlobalConfig>()
            fragment<InputMethodListFragment, InputMethodList> {
                label = ctx.getString(R.string.input_methods)
            }
            fragment<InputMethodConfigFragment, InputMethodConfig>()
            fragment<AddonListFragment, AddonList> {
                label = ctx.getString(R.string.addons)
            }
            fragment<AddonConfigFragment, AddonConfig>()

            /* ========== Android ========== */

            fragment<ThemeFragment, Theme> {
                label = ctx.getString(R.string.theme)
            }
            fragment<KeyboardSettingsFragment, VirtualKeyboard> {
                label = ctx.getString(R.string.virtual_keyboard)
            }
            fragment<KeyboardGroupFragment, KeyboardGroup>()
            fragment<KeyboardModesFragment, KeyboardModes> {
                label = ctx.getString(R.string.keyboard_modes_title)
            }
            // 分组页标题由规格表给出，这里不设 label，由 Fragment 在 onResume 里设置。
            fragment<SettingsGroupFragment, SettingsGroup>()
            fragment<CandidatesSettingsFragment, CandidatesWindow> {
                label = ctx.getString(R.string.candidates_window)
            }
            fragment<ClipboardSettingsFragment, Clipboard> {
                label = ctx.getString(R.string.clipboard)
            }
            fragment<SymbolSettingsFragment, Symbol> {
                label = ctx.getString(R.string.emoji_and_symbols)
            }
            fragment<AdvancedSettingsFragment, Advanced> {
                label = ctx.getString(R.string.advanced)
            }
            // 数据与备份从「高级」拆出，见 DataBackupFragment 的说明。
            fragment<DataBackupFragment, DataBackup> {
                label = ctx.getString(R.string.settings_group_data)
            }
            fragment<DeveloperFragment, Developer> {
                label = ctx.getString(R.string.developer)
            }
            fragment<LicensesFragment, License> {
                label = ctx.getString(R.string.license)
            }
            fragment<AboutFragment, About> {
                label = ctx.getString(R.string.about)
            }

            /* ========== External ========== */

            fragment<ListFragment, ListConfig>(
                typeMap = ListConfig.Params.TypeMap
            )
            fragment<PunctuationEditorFragment, Punctuation>()
            fragment<GenericMultiSelectFragment, MultiSelect>()
            fragment<AddonDirProfileManagerFragment, AddonDirProfileManager>()
        }
    }
}
