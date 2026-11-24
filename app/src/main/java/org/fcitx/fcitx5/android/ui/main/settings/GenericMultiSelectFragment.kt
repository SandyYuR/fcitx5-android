/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.DirectRimeConfig
import org.fcitx.fcitx5.android.core.DirectRimeStatusMenu
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.core.engine.RimeEngineStore
import org.fcitx.fcitx5.android.core.engine.RimeEvoEngines
import org.fcitx.fcitx5.android.core.engine.RimeInputTypes
import org.fcitx.fcitx5.android.core.engine.RimeInputTypesRepository
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.withSettingsPage
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.toast
import java.io.File
import timber.log.Timber

class GenericMultiSelectFragment : Fragment() {
    private data class MultiItem(
        val selected: Boolean,
        val id: String,
        val label: String,
        val inputTypes: RimeInputTypes? = null,
        val inputTypesEditable: Boolean = false,
        val current: Boolean = false,
    )

    private val args by lazyRoute<SettingsRoute.MultiSelect>()
    private val viewModel: MainViewModel by activityViewModels()

    private var containerView: FrameLayout? = null
    private var ui: BaseDynamicListUi<MultiItem>? = null
    private var dirty = false
    private var isLoading = true

    private val isSchemaSelector: Boolean
        get() = args.addon == ADDON_RIME && args.path == PATH_SCHEMA_SELECTOR &&
                args.option == OPTION_SCHEMA_ITEMS

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(requireContext())
        containerView = root
        lifecycleScope.launch {
            val loaded = viewModel.fcitx.runOnReady {
                val raw = getAddonSubConfig(args.addon, args.path)
                val cfg = raw.findByName("cfg") ?: raw
                val payload = cfg.findByName(args.option)?.value.orEmpty()
                val items = parseItems(payload)
                val current = if (isSchemaSelector) readCurrentSchema() else null
                Timber.i(
                    "GenericMultiSelect load addon=%s path=%s option=%s rawItems=%s payloadLen=%d",
                    args.addon,
                    args.path,
                    args.option,
                    cfg.subItems?.joinToString(",") { it.name },
                    payload.length
                )
                if (isSchemaSelector) annotateSchemes(items, current) else items
            }

            var uiRef: BaseDynamicListUi<MultiItem>? = null
            val listUi = object : BaseDynamicListUi<MultiItem>(
                requireContext(),
                Mode.Custom(),
                loaded,
                enableOrder = false,
                initCheckBox = { item ->
                    visibility = View.VISIBLE
                    // Do not set text, checkbox width is only dp(30), text will overflow
                    // nameText already displays the item label
                    contentDescription = item.label
                    isChecked = item.selected
                    setOnCheckedChangeListener { _, checked ->
                        if (isLoading) return@setOnCheckedChangeListener
                        val idx = uiRef?.entries?.indexOfFirst { it.id == item.id } ?: -1
                        if (idx >= 0) {
                            uiRef?.updateItem(idx, item.copy(selected = checked))
                            dirty = true
                        }
                    }
                },
                initActionButton = { item ->
                    val types = item.inputTypes
                    if (types == null) {
                        visibility = View.GONE
                    } else {
                        visibility = View.VISIBLE
                        text = types.summary()
                        isEnabled = item.inputTypesEditable
                        contentDescription = if (item.inputTypesEditable) {
                            getString(R.string.rime_input_types)
                        } else {
                            getString(R.string.rime_input_types_evo_only)
                        }
                        setOnClickListener { showInputTypeDialog(item) }
                    }
                }
            ) {
                override fun showEntry(x: MultiItem): String = x.label

                override fun showSubEntry(x: MultiItem): CharSequence? =
                    if (x.current) getString(R.string.rime_schema_current) else null
            }
            uiRef = listUi
            ui = listUi
            viewModel.disableToolbarEditButton()
            withContext(Dispatchers.Main) {
                containerView?.removeAllViews()
                containerView?.addView(listUi.root)
            }
            isLoading = false
        }
        return withSettingsPage(root)
    }

    /**
     * Reads the scheme list's current selection and every scheme's effective input types.
     *
     * Only the EVO engine understands "input_types", but the declaration itself lives in the
     * scheme YAML and is readable under any engine. The button is therefore shown whenever a
     * scheme declares types (so the declaration is discoverable) and is only enabled under EVO.
     */
    private suspend fun annotateSchemes(
        items: List<MultiItem>,
        current: String?,
    ): List<MultiItem> = withContext(Dispatchers.IO) {
        val appContext = requireContext().applicationContext
        try {
            val repository = RimeEngineStore.repository(appContext)
            val evo = RimeEvoEngines.isEvo(repository.state())
            val config = DirectRimeConfig(appContext)
            config.load()
            val user = repository.activeExternalProfile()?.let(config::profileDir) ?: config.user
            items.map { item ->
                val types = try {
                    RimeInputTypesRepository.read(user, item.id)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    Timber.w(e, "Failed to read input types for scheme %s", item.id)
                    null
                }
                item.copy(
                    inputTypes = types,
                    inputTypesEditable = evo,
                    current = !current.isNullOrBlank() && item.id == current
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Timber.w(e, "Failed to annotate schemes with input types")
            items
        }
    }

    private suspend fun org.fcitx.fcitx5.android.core.FcitxAPI.readCurrentSchema(): String? = try {
        getAddonSubConfig(ADDON_RIME, PATH_CURRENT_SCHEMA)
            .findByName("Schema")?.value?.takeIf { it.isNotBlank() }
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Timber.w(e, "Failed to read the active scheme")
        null
    }

    private fun showInputTypeDialog(item: MultiItem) {
        val types = item.inputTypes ?: return
        if (!item.inputTypesEditable) return
        val labels = types.types.map { it.label }.toTypedArray()
        val checked = types.types.indexOfFirst { it.option == types.current?.option }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.rime_input_types_for_scheme, item.label))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val option = types.types[which].option
                if (option != types.current?.option) {
                    switchInputType(item, types, option)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Persists the choice for this scheme and redeploys so its prisms match. */
    private fun switchInputType(item: MultiItem, types: RimeInputTypes, option: String) {
        val ctx = requireContext()
        val app = ctx.applicationContext as FcitxApplication
        val user = types.customFile.parentFile ?: return
        val index = ui?.entries?.indexOfFirst { it.id == item.id } ?: -1
        lifecycleScope.withLoadingDialog(ctx, R.string.rime_input_types_switching) {
            // The transaction is owned by the application scope: leaving this page may stop the
            // progress dialog, but must not leave the file written and the engine not switched.
            val outcome = app.coroutineScope.async(Dispatchers.IO) {
                performInputTypeSwitch(
                    appContext = ctx.applicationContext,
                    option = option,
                    user = user,
                    schemaId = types.schemaId
                )
            }.await()
            withContext(Dispatchers.Main) {
                when {
                    outcome.persistError != null -> {
                        Timber.w(outcome.persistError, "Failed to persist input type %s", option)
                        if (isAdded) ctx.toast(R.string.rime_input_types_failed)
                    }
                    outcome.engineError != null -> {
                        Timber.w(
                            outcome.engineError,
                            "Failed to redeploy after switching to %s",
                            option
                        )
                        if (isAdded) ctx.toast(R.string.rime_input_types_saved)
                    }
                    else -> if (isAdded) ctx.toast(R.string.rime_input_types_switched)
                }
                val updated = outcome.state
                if (isAdded && updated != null && index >= 0) {
                    ui?.updateItemSilently(index, item.copy(inputTypes = updated))
                }
            }
        }
    }

    private suspend fun performInputTypeSwitch(
        appContext: Context,
        option: String,
        user: File,
        schemaId: String?,
    ): SwitchOutcome {
        val state = try {
            withContext(Dispatchers.IO) {
                // The page may have been opened before the engine or its working directory
                // changed. Re-check both before touching a file, so a stale row can never write
                // into a directory that is no longer the active one.
                val repository = RimeEngineStore.repository(appContext)
                require(RimeEvoEngines.isEvo(repository.state())) { "当前引擎不是 librime-evo" }
                val config = DirectRimeConfig(appContext)
                config.load()
                val currentUser =
                    repository.activeExternalProfile()?.let(config::profileDir) ?: config.user
                require(currentUser.canonicalFile == user.canonicalFile) {
                    "输入方案工作目录已改变"
                }
                RimeInputTypesRepository.select(user, option, schemaId)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return SwitchOutcome(state = null, engineError = null, persistError = e)
        }
        val engineError = try {
            val name = "scheme-input-type-${System.nanoTime()}"
            try {
                FcitxDaemon.connect(name).runOnReady {
                    // set_option only affects the scheme the engine currently runs. Other
                    // schemes receive the choice on their next activation via reset. Ask the
                    // engine right before applying instead of trusting the row's load-time flag.
                    val running = getAddonSubConfig(ADDON_RIME, PATH_CURRENT_SCHEMA)
                        .findByName("Schema")?.value
                    if (running == schemaId) setRimeOption(option, true)
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

    override fun onStop() {
        super.onStop()
        val listUi = ui ?: return
        if (!dirty) return
        val selectedCount = listUi.entries.count { it.selected }
        if (selectedCount < args.min) {
            requireContext().toast(getString(R.string.generic_multiselect_min, args.min))
            return
        }

        lifecycleScope.launch(NonCancellable + Dispatchers.IO) {
            val payload = encodeItems(listUi.entries)
            Timber.i(
                "GenericMultiSelect save addon=%s path=%s option=%s rows=%d payloadLen=%d",
                args.addon,
                args.path,
                args.option,
                listUi.entries.size,
                payload.length
            )
            val config = RawConfig(arrayOf(RawConfig(args.option, payload)))
            viewModel.fcitx.runOnReady {
                setAddonSubConfig(args.addon, args.path, config)
            }
            dirty = false
        }
    }

    override fun onDestroy() {
        containerView = null
        ui = null
        isLoading = true
        super.onDestroy()
    }

    private fun parseItems(text: String): List<MultiItem> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            MultiItem(
                selected = parts[0] == "1",
                id = parts[1],
                label = sanitizeLabel(parts[2])
            )
        }.toList()
    }

    private fun sanitizeLabel(raw: String): String {
        val cleaned = raw.replace('\t', ' ').replace('|', ' ').trim()
        return cleaned.ifEmpty { raw.trim() }
    }

    private fun encodeItems(items: List<MultiItem>): String =
        items.joinToString("\n") { "${if (it.selected) "1" else "0"}\t${it.id}\t${it.label}" }

    private data class SwitchOutcome(
        val state: RimeInputTypes?,
        val engineError: Throwable?,
        val persistError: Throwable?
    )

    companion object {
        private const val ADDON_RIME = "rime"
        private const val PATH_SCHEMA_SELECTOR = "schema-selector"
        private const val PATH_CURRENT_SCHEMA = "current-schema"
        private const val OPTION_SCHEMA_ITEMS = "Items"
    }
}
