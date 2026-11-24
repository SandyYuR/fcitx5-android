/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.content.Context
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.DirectRimeConfig
import org.fcitx.fcitx5.android.core.DirectRimeStatusMenu
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.core.engine.RimeEngineStore
import org.fcitx.fcitx5.android.core.engine.RimeEvoEngines
import org.fcitx.fcitx5.android.core.engine.RimeInputTypes
import org.fcitx.fcitx5.android.core.engine.RimeInputTypesRepository
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.toast
import java.io.File
import timber.log.Timber

class InputMethodConfigFragment : FcitxPreferenceFragment() {
    val args by lazyRoute<SettingsRoute.InputMethodConfig>()

    override fun getPageTitle(): String = args.name

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig {
        return fcitx.getImConfig(args.uniqueName)
    }

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        fcitx.setImConfig(args.uniqueName, newConfig)
    }

    /**
     * 「中州韵」页由引擎的 `desc` 树生成，本应用自己的条目在引擎配置项之后追加：
     *
     * - 「输入类型」紧跟在「启用的输入方案」后面，仅 evo 引擎 + 声明了 `input_types` 的方案可用；
     * - 「密码框是否交给 Rime」放到页面末尾，它只对 Rime 生效，其它输入法的配置页不受影响。
     */
    override fun postDecoratePreferenceScreen(screen: PreferenceScreen) {
        if (args.uniqueName != RIME_UNIQUE_NAME) return
        val context = requireContext()
        val inputTypes = Preference(context).apply {
            key = KEY_INPUT_TYPES
            setTitle(R.string.rime_input_types)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            isEnabled = false
            setSummary(R.string.rime_input_types_loading)
        }
        screen.findPreference<Preference>(SCHEMA_SELECTOR_KEY)?.let { anchor ->
            // 引擎配置项可能挂在某个 PreferenceCategory 下，追加位置以它的父组为准。
            (anchor.parent ?: screen).insertAfter(anchor, inputTypes)
        }
        addPasswordSwitch(screen)
        lifecycleScope.launch { refreshInputTypes(inputTypes, context.applicationContext) }
    }

    private fun addPasswordSwitch(screen: PreferenceScreen) {
        val pref = AppPrefs.getInstance().keyboard.allowImeInPasswordFields
        val switch = MySwitchPreference(requireContext()).apply {
            key = pref.key
            setTitle(R.string.allow_ime_in_password_fields)
            setSummary(R.string.allow_ime_in_password_fields_summary)
            setDefaultValue(pref.defaultValue)
            isIconSpaceReserved = false
            isSingleLineTitle = false
        }
        screen.addPreference(switch)
    }

    private suspend fun refreshInputTypes(preference: Preference, appContext: Context) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { loadInputTypes(appContext) }.getOrNull()
        }
        if (!isAdded) return
        val types = loaded?.types
        when {
            loaded == null || !loaded.evo -> {
                preference.isEnabled = false
                preference.setSummary(R.string.rime_input_types_evo_only)
            }
            types == null -> {
                preference.isEnabled = false
                preference.setSummary(R.string.rime_input_types_unsupported)
            }
            else -> {
                preference.isEnabled = true
                preference.setSummary(types.summary())
                preference.setOnPreferenceClickListener {
                    showInputTypeDialog(types, loaded.user)
                    true
                }
            }
        }
    }

    private fun loadInputTypes(appContext: Context): Availability {
        val repository = RimeEngineStore.repository(appContext)
        val evo = RimeEvoEngines.isEvo(repository.state())
        if (!evo) return Availability(evo = false, types = null, user = appContext.cacheDir)
        val config = DirectRimeConfig(appContext)
        config.load()
        val user = repository.activeExternalProfile()?.let(config::profileDir) ?: config.user
        return Availability(evo = true, types = RimeInputTypesRepository.read(user), user = user)
    }

    private fun showInputTypeDialog(types: RimeInputTypes, user: File) {
        val items = types.types.map { it.label }.toTypedArray()
        val checked = types.types.indexOfFirst { it.option == types.current?.option }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rime_input_types)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                dialog.dismiss()
                val option = types.types[which].option
                if (option != types.current?.option) {
                    switchInputType(option, user)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Persists the choice, switches the running option, then redeploys so prisms match. */
    private fun switchInputType(option: String, user: File) {
        val preference = findPreference<Preference>(KEY_INPUT_TYPES) ?: return
        val ctx = requireContext()
        preference.isEnabled = false
        lifecycleScope.withLoadingDialog(
            ctx, R.string.rime_input_types_switching
        ) {
            val written = runCatching {
                withContext(Dispatchers.IO) { RimeInputTypesRepository.select(user, option) }
            }
            written.onSuccess { state ->
                preference.setSummary(state.summary())
                val engineError = runCatching {
                    val name = "settings-input-type-${System.nanoTime()}"
                    try {
                        FcitxDaemon.connect(name).runOnReady {
                            setRimeOption(option, true)
                            activateAction(DirectRimeStatusMenu.DEPLOY_ID)
                        }
                    } finally {
                        FcitxDaemon.disconnect(name)
                    }
                }.exceptionOrNull()
                if (engineError == null) {
                    ctx.toast(R.string.rime_input_types_switched)
                } else {
                    Timber.w(engineError, "Failed to redeploy after switching to $option")
                    ctx.toast(R.string.rime_input_types_saved)
                }
            }.onFailure {
                Timber.w(it, "Failed to persist input type $option")
                ctx.toast(R.string.rime_input_types_failed)
            }
            if (isAdded) refreshInputTypes(preference, requireContext().applicationContext)
        }
    }

    private data class Availability(val evo: Boolean, val types: RimeInputTypes?, val user: File)

    companion object {
        private const val RIME_UNIQUE_NAME = "rime"
        private const val SCHEMA_SELECTOR_KEY = "SchemaSelector"
        private const val KEY_INPUT_TYPES = "rime_input_types"
    }
}

/**
 * Inserts [preference] directly after [anchor], keeping the engine-provided order intact.
 *
 * [PreferenceGroup.addPreference] places a child by [Preference.compareTo]: order first, then
 * title. Engine-provided entries carry increasing orders, so taking the anchor's order makes the
 * new entry sort right after it while everything that follows keeps its own order.
 */
private fun PreferenceGroup.insertAfter(anchor: Preference, preference: Preference) {
    preference.order = anchor.order
    addPreference(preference)
}
