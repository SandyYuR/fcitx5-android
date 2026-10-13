/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.RimeProfileBackup
import org.fcitx.fcitx5.android.data.RimeProfileRestorePlan
import org.fcitx.fcitx5.android.utils.ExternalRimeStorage
import java.io.File
import kotlin.coroutines.resume

/** Register with the fragment before onCreate; waiting jobs are cancelled with its lifecycle. */
internal class RimeRestoreDestinationPicker(private val fragment: Fragment) {
    private var pendingDirectory: CompletableDeferred<Uri?>? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val directoryLauncher = fragment.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        pendingDirectory?.complete(it)
    }
    private val permissionLauncher = fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingPermission?.complete(it)
    }
    private val settingsLauncher = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingPermission?.complete(fragment.isAdded && hasStorageAccess())
    }

    suspend fun choose(manifest: RimeProfileBackup.Manifest,
                       validate: suspend (RimeProfileRestorePlan) -> Unit): RimeProfileRestorePlan? {
        val targets = mutableMapOf<String, File?>()
        for (name in manifest.externalProfiles) {
            val original = manifest.originalPaths[name]
            var selected = if (original == null) 2 else 0
            while (true) {
                selected = select(name, original, selected) ?: return null
                if (selected == 0 && original == null) {
                    explain(fragment.getString(R.string.rime_restore_original_unknown))
                    continue
                }
                if (selected != 2 && !requestStorageAccess()) {
                    explain(fragment.getString(R.string.rime_restore_permission_fallback))
                    continue
                }
                val target = when (selected) {
                    0 -> File(original!!)
                    1 -> {
                        val uri = pickDirectory(original) ?: continue
                        val path = runCatching {
                            ExternalRimeStorage.absolutePath(uri.authority, DocumentsContract.getTreeDocumentId(uri),
                                Environment.getExternalStorageDirectory().absolutePath)
                        }.getOrNull()
                        if (path == null) {
                            explain(fragment.getString(R.string.addon_dir_profile_external_dir_invalid))
                            continue
                        }
                        File(path)
                    }
                    else -> null
                }
                val single = RimeProfileRestorePlan(manifest.externalProfiles.associateWith {
                    if (it == name) target else null
                })
                try {
                    validate(single)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    explain(fragment.getString(R.string.rime_restore_target_unavailable, name,
                        target?.absolutePath ?: fragment.getString(R.string.rime_restore_app)))
                    continue
                }
                targets[name] = target?.canonicalFile
                break
            }
        }
        return RimeProfileRestorePlan(targets)
    }

    private suspend fun select(name: String, original: String?, initial: Int): Int? =
        suspendCancellableCoroutine { continuation ->
            var selected = initial
            val options = arrayOf(
                fragment.getString(R.string.rime_restore_original, original ?: fragment.getString(R.string.rime_restore_original_unknown)),
                fragment.getString(R.string.rime_restore_other),
                fragment.getString(R.string.rime_restore_app)
            )
            val dialog = AlertDialog.Builder(fragment.requireContext())
                .setTitle(fragment.getString(R.string.rime_restore_profile_title, name))
                .setSingleChoiceItems(options, initial) { _, which -> selected = which }
                .setPositiveButton(android.R.string.ok) { _, _ -> if (continuation.isActive) continuation.resume(selected) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> if (continuation.isActive) continuation.resume(null) }
                .setOnCancelListener { if (continuation.isActive) continuation.resume(null) }
                .show()
            continuation.invokeOnCancellation { dialog.dismiss() }
        }

    suspend fun explain(message: String) = suspendCancellableCoroutine<Unit> { continuation ->
        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.import_user_data).setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> if (continuation.isActive) continuation.resume(Unit) }
            .setOnCancelListener { if (continuation.isActive) continuation.resume(Unit) }
            .show()
        continuation.invokeOnCancellation { dialog.dismiss() }
    }

    suspend fun confirm(message: String): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setIconAttribute(android.R.attr.alertDialogIcon)
            .setTitle(R.string.import_user_data).setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> if (continuation.isActive) continuation.resume(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> if (continuation.isActive) continuation.resume(false) }
            .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
            .show()
        continuation.invokeOnCancellation { dialog.dismiss() }
    }

    private fun hasStorageAccess(): Boolean = when (ExternalRimeStorage.accessForSdk(Build.VERSION.SDK_INT)) {
        ExternalRimeStorage.Access.LegacyPermission -> ContextCompat.checkSelfPermission(fragment.requireContext(),
            Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ExternalRimeStorage.Access.Unsupported -> false
        ExternalRimeStorage.Access.AllFilesPermission -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
    }

    private suspend fun requestStorageAccess(): Boolean {
        if (hasStorageAccess()) return true
        val pending = CompletableDeferred<Boolean>()
        pendingPermission = pending
        try {
            when (ExternalRimeStorage.accessForSdk(Build.VERSION.SDK_INT)) {
                ExternalRimeStorage.Access.LegacyPermission -> permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                ExternalRimeStorage.Access.Unsupported -> return false
                ExternalRimeStorage.Access.AllFilesPermission -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${fragment.requireContext().packageName}"))
                    try { settingsLauncher.launch(intent) }
                    catch (_: Exception) { settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }
            }
            return pending.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        } finally { pendingPermission = null }
    }

    private suspend fun pickDirectory(original: String?): Uri? {
        val pending = CompletableDeferred<Uri?>()
        pendingDirectory = pending
        try {
            val initial = original?.let {
                ExternalRimeStorage.documentId(it, Environment.getExternalStorageDirectory().absolutePath)
            }?.let { DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", it) }
            directoryLauncher.launch(initial)
            return pending.await()
        } finally { pendingDirectory = null }
    }
}
