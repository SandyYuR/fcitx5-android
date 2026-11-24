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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
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

    /** Schema the engine is actually running, read when the page is created. */
    private var activeSchema: String? = null

    override fun getPageTitle(): String = args.name

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig {
        if (args.uniqueName == RIME_UNIQUE_NAME) {
            // input_types lives in the schema, so bind this page to the schema the engine runs
            // instead of guessing a file name from the directory listing.
            activeSchema = try {
                fcitx.getAddonSubConfig("rime", "current-schema")
                    .findByName("Schema")?.value?.takeIf { it.isNotBlank() }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Timber.w(e, "Failed to read the active schema")
                null
            }
        }
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
            try {
                loadInputTypes(appContext)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Timber.w(e, "Failed to load input types")
                null
            }
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
        return Availability(
            evo = true,
            types = RimeInputTypesRepository.read(user, activeSchema),
            user = user
        )
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
                    switchInputType(option, user, types.schemaId)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Persists the choice, switches the running option, then redeploys so prisms match. */
    private fun switchInputType(option: String, user: File, schemaId: String?) {
        val preference = findPreference<Preference>(KEY_INPUT_TYPES) ?: return
        val ctx = requireContext()
        val app = ctx.applicationContext as FcitxApplication
        preference.isEnabled = false
        lifecycleScope.withLoadingDialog(
            ctx, R.string.rime_input_types_switching
        ) {
            // The transaction is owned by the application scope: leaving this page may stop the
            // progress dialog, but must not leave the file written and the engine not switched.
            val outcome = app.coroutineScope.async(Dispatchers.IO) {
                performInputTypeSwitch(option, user, schemaId)
            }.await()
            withContext(Dispatchers.Main) {
                when {
                    outcome.persistError != null -> {
                        Timber.w(outcome.persistError, "Failed to persist input type $option")
                        if (isAdded) ctx.toast(R.string.rime_input_types_failed)
                    }
                    outcome.engineError != null -> {
                        Timber.w(outcome.engineError, "Failed to redeploy after switching to $option")
                        if (isAdded) ctx.toast(R.string.rime_input_types_saved)
                    }
                    else -> if (isAdded) ctx.toast(R.string.rime_input_types_switched)
                }
                if (isAdded) {
                    outcome.state?.let { preference.setSummary(it.summary()) }
                    refreshInputTypes(preference, requireContext().applicationContext)
                }
            }
        }
    }

    private suspend fun performInputTypeSwitch(
        option: String, user: File, schemaId: String?
    ): SwitchOutcome {
        val state = try {
            withContext(Dispatchers.IO) {
                RimeInputTypesRepository.select(user, option, schemaId)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return SwitchOutcome(state = null, engineError = null, persistError = e)
        }
        val engineError = try {
            val name = "settings-input-type-${System.nanoTime()}"
            try {
                FcitxDaemon.connect(name).runOnReady {
                    setRimeOption(option, true)
                    activateAction(DirectRimeStatusMenu.DEPLOY_ID)
                }
            } finally {
                FcitxDaemon.disconnect(name)
            }
            null
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            e
        }
        return SwitchOutcome(state, engineError, null)
    }

    private data class Availability(val evo: Boolean, val types: RimeInputTypes?, val user: File)

    private data class SwitchOutcome(
        val state: RimeInputTypes?,
        val engineError: Throwable?,
        val persistError: Throwable?
    )

    companion object {
        private const val RIME_UNIQUE_NAME = "rime"
        private const val SCHEMA_SELECTOR_KEY = "SchemaSelector"
        private const val KEY_INPUT_TYPES = "rime_input_types"
    }
}

/**
 * Inserts [preference] directly after [anchor].
 *
 * [PreferenceGroup.addPreference] places a child by [Preference.compareTo]: order first, then
 * title. Engine-provided orders are not guaranteed to be contiguous, so renumber the children
 * with gaps and give [preference] an odd order inside the gap; sorted order then puts it exactly
 * after [anchor] regardless of the titles.
 */
private fun PreferenceGroup.insertAfter(anchor: Preference, preference: Preference) {
    val children = (0 until preferenceCount).mapNotNull { getPreference(it) }
    val index = children.indexOf(anchor)
    require(index >= 0) { "anchor ${anchor.key} is not a child of this group" }
    children.forEachIndexed { i, child -> child.order = i * 2 }
    preference.order = index * 2 + 1
    addPreference(preference)
}
