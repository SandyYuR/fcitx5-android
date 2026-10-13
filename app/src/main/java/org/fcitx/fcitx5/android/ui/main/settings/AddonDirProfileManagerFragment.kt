/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDisconnectedException
import org.fcitx.fcitx5.android.data.RimeDataDirectoryState
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.ExternalRimeStorage
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

class AddonDirProfileManagerFragment : Fragment() {

    private data class ProfileEntry(val name: String, val active: Boolean, val path: String?)

    private data class ManagerState(
        val current: String,
        val profiles: List<String>,
        // profile name -> resolved absolute path, reported by the engine
        val paths: Map<String, String>,
        val error: String
    )

    private val args by lazyRoute<SettingsRoute.AddonDirProfileManager>()
    private val viewModel: MainViewModel by activityViewModels()

    private var containerView: FrameLayout? = null
    private var latestState: ManagerState? = null

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (hasExternalStorageAccess()) pickDirectoryLauncher.launch(null)
            else requireContext().toast(R.string.addon_dir_profile_all_files_permission_required)
        }

    private val legacyStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pickDirectoryLauncher.launch(null)
            else requireContext().toast(R.string.addon_dir_profile_storage_permission_required)
        }

    private val pickDirectoryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val path = treeUriToAbsolutePath(uri)
            if (path == null) {
                requireContext().toast(R.string.addon_dir_profile_external_dir_invalid)
                return@registerForActivityResult
            }
            val initialName = path.trimEnd('/').substringAfterLast('/')
            showCreateDialog(externalPath = path, initialName = initialName)
        }

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
        // Bind to viewLifecycleOwner instead of fragment lifecycle: `lifecycleScope` survives
        // `onDestroyView`, so a render job waiting on fcitx could resume after the view was torn
        // down and crash on `requireContext()` (crash report 2026-09-17, "create rime dir").
        viewLifecycleOwner.lifecycleScope.launch {
            renderLatestState()
        }
    }

    override fun onDestroyView() {
        containerView = null
        super.onDestroyView()
    }

    private fun hasExternalStorageAccess(): Boolean = when (ExternalRimeStorage.accessForSdk(Build.VERSION.SDK_INT)) {
        ExternalRimeStorage.Access.LegacyPermission -> ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        ExternalRimeStorage.Access.Unsupported -> false
        ExternalRimeStorage.Access.AllFilesPermission ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

    private fun pickExternalDirectory() {
        if (hasExternalStorageAccess()) {
            pickDirectoryLauncher.launch(null)
            return
        }
        when (ExternalRimeStorage.accessForSdk(Build.VERSION.SDK_INT)) {
            ExternalRimeStorage.Access.LegacyPermission ->
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            ExternalRimeStorage.Access.Unsupported ->
                requireContext().toast(R.string.addon_dir_profile_android10_unsupported)
            ExternalRimeStorage.Access.AllFilesPermission -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) requestAllFilesAccess()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestAllFilesAccess() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${requireContext().packageName}")
        )
        runCatching { allFilesAccessLauncher.launch(intent) }.onFailure {
            runCatching {
                allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.onFailure {
                requireContext().toast(it)
            }
        }
    }

    /**
     * Convert an external-storage document tree URI (picked via SAF) to a real
     * absolute path. Only works for local volumes; returns null otherwise.
     */
    private fun treeUriToAbsolutePath(uri: Uri): String? {
        val documentId = runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null
        return ExternalRimeStorage.absolutePath(
            uri.authority, documentId, Environment.getExternalStorageDirectory().absolutePath
        )
    }

    private suspend fun loadState(): ManagerState {
        val raw = viewModel.fcitx.runOnReady {
            getAddonSubConfig(args.addon, args.path)
        }
        val cfg = raw.findByName("cfg") ?: raw
        val state = RimeDataDirectoryState.fromConfig(
            raw, requireContext().getExternalFilesDir(null)!!.resolve("data")
        )
        val error = cfg.findByName("Error")?.value.orEmpty()
        return ManagerState(
            current = state.current,
            profiles = state.profiles,
            paths = state.paths,
            error = error
        )
    }

    private suspend fun renderLatestState() {
        val state = try {
            loadState()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to load addon dir profile state")
            if (isAdded) requireContext().toast(e)
            return
        }
        latestState = state
        if (state.error.isNotBlank()) {
            requireContext().toast(state.error)
        }
        val entries = state.profiles.map {
            ProfileEntry(it, it == state.current, state.paths[it])
        }
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
                    viewLifecycleOwner.lifecycleScope.launch {
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

            override fun showSubEntry(x: ProfileEntry): CharSequence? {
                return x.path
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

    private fun showCreateDialog(externalPath: String? = null, initialName: String = "") {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.name)
            if (initialName.isNotEmpty()) {
                setText(initialName)
                setSelection(initialName.length)
            }
            setSingleLine(true)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.addon_dir_profile_create)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            // Neutral button sits at the bottom-left of the dialog.
            .setNeutralButton(R.string.addon_dir_profile_use_external_path) { _, _ ->
                pickExternalDirectory()
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    if (externalPath != null && !hasExternalStorageAccess()) {
                        pickExternalDirectory()
                        return@setPositiveButton
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        runAction(action = "create", name = value, externalPath = externalPath)
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
                    viewLifecycleOwner.lifecycleScope.launch {
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
                viewLifecycleOwner.lifecycleScope.launch {
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
                    0 -> viewLifecycleOwner.lifecycleScope.launch {
                        runAction(action = "switch", name = item.name)
                    }
                    1 -> showRenameDialog(item.name)
                    2 -> showDeleteDialog(item.name)
                }
            }
            .show()
    }

    private suspend fun runAction(
        action: String,
        name: String = "",
        newName: String = "",
        externalPath: String? = null
    ) {
        val items = mutableListOf(RawConfig("Action", action))
        if (name.isNotBlank()) {
            items.add(RawConfig("Name", name))
        }
        if (newName.isNotBlank()) {
            items.add(RawConfig("NewName", newName))
        }
        if (!externalPath.isNullOrBlank()) {
            items.add(RawConfig("ExternalPath", externalPath))
        }
        Timber.d("Profile action: $action, external=${externalPath != null}")
        try {
            viewModel.fcitx.runOnReady {
                setAddonSubConfig(args.addon, args.path, RawConfig(items.toTypedArray()))
            }
        } catch (e: FcitxDisconnectedException) {
            Timber.w("Skip rendering after disconnected action '$action': $e")
            return
        } catch (e: Exception) {
            // surface the failure instead of crashing on the follow-up render
            requireContext().toast(e)
            return
        }
        renderLatestState()
    }
}
