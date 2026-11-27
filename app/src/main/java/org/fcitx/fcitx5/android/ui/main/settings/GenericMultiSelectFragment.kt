/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.DirectRimeInputTypeOperations
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.core.engine.RimeInputTypes
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.withSettingsPage
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

class GenericMultiSelectFragment : Fragment() {
    private data class MultiItem(
        val selected: Boolean,
        val id: String,
        val label: String,
        val inputTypes: RimeInputTypes? = null,
        val inputTypesEditable: Boolean = false,
        val inputTypeTarget: RawConfig? = null,
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

    /** Load declarations and deployed choices together on the engine's existing owner. */
    private suspend fun org.fcitx.fcitx5.android.core.FcitxAPI.annotateSchemes(
        items: List<MultiItem>,
        current: String?,
    ): List<MultiItem> = items.map { item ->
        try {
            val raw = getAddonSubConfig(ADDON_RIME, "input-types/" + item.id)
            item.copy(
                inputTypes = DirectRimeInputTypeOperations.decode(raw),
                inputTypesEditable = raw.findByName("Editable")?.value == "True",
                inputTypeTarget = raw.findByName("Target"),
                current = !current.isNullOrBlank() && item.id == current,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Failed to read input types for scheme %s", item.id)
            item.copy(current = !current.isNullOrBlank() && item.id == current)
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
        val choices = types.selectableTypes
        if (choices.isEmpty()) return
        val labels = choices.map { it.label }.toTypedArray()
        val checked = choices.indexOfFirst { it.option == types.current?.option }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.rime_input_types_for_scheme, item.label))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val option = choices[which].option
                if (option != types.current?.option) {
                    switchInputType(item, types, option)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Persist reset and activate an already deployed input type as one owner transaction. */
    private fun switchInputType(item: MultiItem, types: RimeInputTypes, option: String) {
        val ctx = requireContext()
        val target = item.inputTypeTarget ?: return
        lifecycleScope.withLoadingDialog(ctx, R.string.rime_input_types_switching) {
            try {
                val raw = withContext(Dispatchers.IO) {
                    val name = "scheme-input-type-${System.nanoTime()}"
                    try {
                        FcitxDaemon.connect(name).runOnReady {
                            setAddonSubConfig(ADDON_RIME, "input-type", RawConfig(arrayOf(
                                RawConfig("Schema", types.schemaId), RawConfig("Option", option), target,
                            )))
                            getAddonSubConfig(ADDON_RIME, "input-types/" + types.schemaId)
                        }
                    } finally {
                        FcitxDaemon.disconnect(name)
                    }
                }
                val updated = DirectRimeInputTypeOperations.decode(raw)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        ctx.toast(R.string.rime_input_types_switched)
                        val index = ui?.entries?.indexOfFirst { it.id == item.id } ?: -1
                        if (updated != null && index >= 0) {
                            val currentItem = ui?.entries?.getOrNull(index) ?: item
                            ui?.updateItemSilently(index, currentItem.copy(
                                inputTypes = updated,
                                inputTypeTarget = raw.findByName("Target"),
                                inputTypesEditable = raw.findByName("Editable")?.value == "True",
                            ))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Failed to switch input type %s", option)
                if (isAdded) ctx.toast(R.string.rime_input_types_failed)
            }
        }
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

    companion object {
        private const val ADDON_RIME = "rime"
        private const val PATH_SCHEMA_SELECTOR = "schema-selector"
        private const val PATH_CURRENT_SCHEMA = "current-schema"
        private const val OPTION_SCHEMA_ITEMS = "Items"
    }
}
