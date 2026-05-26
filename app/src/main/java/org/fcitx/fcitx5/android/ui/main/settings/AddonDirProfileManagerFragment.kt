/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.toast

class AddonDirProfileManagerFragment : Fragment() {

    private data class ProfileEntry(val name: String, val active: Boolean)

    private data class ManagerState(
        val current: String,
        val profiles: List<String>,
        val error: String
    )

    private val args by lazyRoute<SettingsRoute.AddonDirProfileManager>()
    private val viewModel: MainViewModel by activityViewModels()

    private var containerView: FrameLayout? = null
    private var latestState: ManagerState? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FrameLayout(requireContext()).also { containerView = it }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.setToolbarTitle(args.title)
        viewModel.disableToolbarEditButton()
        lifecycleScope.launch {
            renderLatestState()
        }
    }

    override fun onDestroyView() {
        containerView = null
        super.onDestroyView()
    }

    private suspend fun loadState(): ManagerState {
        val raw = viewModel.fcitx.runOnReady {
            getAddonSubConfig(args.addon, args.path)
        }
        val cfg = raw.findByName("cfg") ?: raw
        val current = cfg.findByName("Current")?.value.orEmpty().trim()
        val profiles = cfg.findByName("Profiles")?.value.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        val error = cfg.findByName("Error")?.value.orEmpty()
        return ManagerState(
            current = current,
            profiles = profiles,
            error = error
        )
    }

    private suspend fun renderLatestState() {
        val state = loadState()
        latestState = state
        if (state.error.isNotBlank()) {
            requireContext().toast(state.error)
        }
        val entries = state.profiles.map { ProfileEntry(it, it == state.current) }
        val ui = object : BaseDynamicListUi<ProfileEntry>(
            requireContext(),
            Mode.Custom(),
            entries,
            enableOrder = false,
            initCheckBox = { item ->
                visibility = View.VISIBLE
                isChecked = item.active
                setOnCheckedChangeListener { _, checked ->
                    if (!checked || item.active) return@setOnCheckedChangeListener
                    lifecycleScope.launch {
                        runAction(action = "switch", name = item.name)
                    }
                }
            },
            initSettingsButton = { item ->
                visibility = View.VISIBLE
                setImageResource(R.drawable.ic_baseline_more_horiz_24)
                setOnClickListener {
                    showItemMenu(item)
                }
            }
        ) {
            override fun showEntry(x: ProfileEntry): String {
                return if (x.active) {
                    getString(R.string.addon_dir_profile_current_format, x.name)
                } else {
                    x.name
                }
            }

            override fun updateFAB() {
                shouldShowFab = true
                fab.show()
                fab.setOnClickListener {
                    showCreateDialog()
                }
            }
        }
        ui.updateFAB()

        containerView?.removeAllViews()
        containerView?.addView(ui.root)
    }

    private fun showCreateDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.name)
            setSingleLine(true)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.addon_dir_profile_create)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    lifecycleScope.launch {
                        runAction(action = "create", name = value)
                    }
                }
            }
            .show()
    }

    private fun showRenameDialog(oldName: String) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.name)
            setText(oldName)
            setSelection(oldName.length)
            setSingleLine(true)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.addon_dir_profile_rename)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty() && newName != oldName) {
                    lifecycleScope.launch {
                        runAction(action = "rename", name = oldName, newName = newName)
                    }
                }
            }
            .show()
    }

    private fun showDeleteDialog(name: String) {
        if ((latestState?.profiles?.size ?: 0) <= 1) {
            requireContext().toast(R.string.addon_dir_profile_delete_last_forbidden)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.addon_dir_profile_delete_confirm, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    runAction(action = "delete", name = name)
                }
            }
            .show()
    }

    private fun showItemMenu(item: ProfileEntry) {
        val options = arrayOf(
            getString(R.string.addon_dir_profile_switch),
            getString(R.string.addon_dir_profile_rename),
            getString(R.string.delete)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch { runAction(action = "switch", name = item.name) }
                    1 -> showRenameDialog(item.name)
                    2 -> showDeleteDialog(item.name)
                }
            }
            .show()
    }

    private suspend fun runAction(action: String, name: String = "", newName: String = "") {
        val items = mutableListOf(RawConfig("Action", action))
        if (name.isNotBlank()) {
            items.add(RawConfig("Name", name))
        }
        if (newName.isNotBlank()) {
            items.add(RawConfig("NewName", newName))
        }
        viewModel.fcitx.runOnReady {
            setAddonSubConfig(args.addon, args.path, RawConfig(items.toTypedArray()))
        }
        renderLatestState()
    }
}
