/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.BackupFileChangedException
import org.fcitx.fcitx5.android.data.UserDataBackupArchive
import org.fcitx.fcitx5.android.data.UserDataBackupManager
import org.fcitx.fcitx5.android.data.RimeProfileRestorePlan
import org.fcitx.fcitx5.android.data.ProtectedBackup
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.common.ProgressBarDialogIndeterminate
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.buildDocumentsProviderIntent
import org.fcitx.fcitx5.android.utils.buildPrimaryStorageIntent
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.toast
import java.io.File
import java.util.UUID

/**
 * 「数据与备份」页。
 *
 * 内容原属「高级」页。拆出的理由：导入/导出是用户主动执行的一次性任务，
 * 而「高级」剩下的都是开关（兼容性变通、引擎配置入口）；两类操作的心智模型
 * 不同，混在一页会让「高级」既不像是「危险操作区」也不像是「设置区」。
 *
 * 本页没有 managed preference（三项都是纯动作，不对应任何 AppPrefs 字段），
 * 因此直接继承 [PaddingPreferenceFragment] 手工建条目，不必绕道
 * `ManagedPreferenceFragment` 再删掉它注入的项。
 */
class DataBackupFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private val restorePicker = RimeRestoreDestinationPicker(this)
    private var importInProgress = false
    private var exportInProgress = false
    private var pendingExportArchive: File? = null
    private var operationPreference: Preference? = null
    private var operationMessage: String? = null
    private var operationDetails: String? = null
    private var operationBusy = false

    private var exportTimestamp = System.currentTimeMillis()

    private lateinit var exportLauncher: ActivityResultLauncher<String>

    private lateinit var importLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportArchive = UserDataBackupArchive.pending(savedInstanceState?.getString("pendingExportArchive"), requireContext().cacheDir)
        operationMessage = savedInstanceState?.getString("operationMessage")
        operationDetails = savedInstanceState?.getString("operationDetails")
        renderOperation()
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                beginImport(uri)
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                publishExport(uri)
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExportArchive", pendingExportArchive?.absolutePath)
        if (!operationBusy) {
            outState.putString("operationMessage", operationMessage)
            outState.putString("operationDetails", operationDetails)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (activity?.isChangingConfigurations != true) {
            pendingExportArchive?.delete()
            pendingExportArchive = null
        }
        super.onDestroy()
    }

    private fun beginExport() {
        if (importInProgress || exportInProgress || pendingExportArchive != null) return
        exportInProgress = true
        val ctx = requireContext()
        lifecycleScope.launch {
            var archive: File? = null
            var password: CharArray? = null
            try {
                val protection = ctx.chooseBackupProtection(export = true) ?: run {
                    showOperation(R.string.ui_operation_cancelled)
                    return@launch
                }
                password = protection.password
                exportTimestamp = System.currentTimeMillis()
                showOperation(R.string.ui_backup_preparing, busy = true)
                loading(R.string.ui_backup_preparing) {
                    // Record ownership before cancellation can dispatch back to this fragment.
                    withContext(NonCancellable) { archive = UserDataBackupManager.prepare(exportTimestamp) }
                    password?.let { secret ->
                        withContext(Dispatchers.IO) {
                            val encrypted = File.createTempFile(UserDataBackupArchive.PREFIX, ".zip", ctx.cacheDir)
                            try {
                                archive!!.inputStream().use { input ->
                                    encrypted.outputStream().use { output ->
                                        ProtectedBackup.encrypt(input, output, secret) { coroutineContext.ensureActive() }
                                    }
                                }
                                archive!!.delete()
                                archive = encrypted
                            } catch (e: Throwable) { encrypted.delete(); throw e }
                        }
                    }
                }
                coroutineContext.ensureActive()
                pendingExportArchive = archive
                showOperation(R.string.ui_backup_destination)
                val extension = if (password != null) "fcbak" else "zip"
                exportLauncher.launch("fcitx5-android_${iso8601UTCDateTime(exportTimestamp)}.$extension")
                archive = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                pendingExportArchive = null
                showOperation(R.string.ui_operation_failed, e.localizedMessage.orEmpty())
                ctx.toast(if (e is BackupFileChangedException) ctx.getString(R.string.user_data_export_changed) else e.localizedMessage.orEmpty())
            } finally {
                password?.fill('\u0000')
                withContext(NonCancellable + Dispatchers.IO) { archive?.delete() }
                exportInProgress = false
            }
        }
    }

    private fun publishExport(uri: Uri?) {
        val archive = pendingExportArchive
        pendingExportArchive = null
        if (uri == null) { archive?.delete(); showOperation(R.string.ui_operation_cancelled); return }
        val ctx = requireContext()
        exportInProgress = true
        showOperation(R.string.ui_backup_saving, busy = true)
        lifecycleScope.withLoadingDialog(ctx, R.string.ui_backup_saving) {
            withContext(NonCancellable) {
                try {
                    withContext(Dispatchers.IO) {
                        require(archive != null && archive.isFile) { "The prepared backup is no longer available." }
                        ctx.contentResolver.openOutputStream(uri)!!.use { output ->
                            archive.inputStream().use { it.copyTo(output) }
                        }
                    }
                    ctx.toast(R.string.user_data_exported)
                    showOperation(R.string.user_data_exported)
                } catch (e: Exception) {
                    // CreateDocument creates a new document. Remove only that failed output.
                    val removed = withContext(Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }.getOrDefault(false)
                    }
                    if (removed) ctx.toast(e) else ctx.toast(R.string.user_data_export_cleanup_failed)
                    showOperation(R.string.ui_operation_failed,
                        if (removed) e.localizedMessage.orEmpty() else ctx.getString(R.string.user_data_export_cleanup_failed))
                } finally {
                    withContext(Dispatchers.IO) { archive?.delete() }
                    exportInProgress = false
                }
            }
        }
    }

    private suspend fun <T> loading(title: Int = R.string.loading, action: suspend () -> T): T {
        val dialog = requireContext().ProgressBarDialogIndeterminate(title).show()
        return try { action() } finally { dialog.dismiss() }
    }

    private fun beginImport(uri: Uri) {
        if (importInProgress || exportInProgress || pendingExportArchive != null) return
        importInProgress = true
        showOperation(R.string.ui_backup_checking, busy = true)
        val ctx = requireContext()
        lifecycleScope.launch {
            var prepared: UserDataManager.PreparedImport? = null
            var decrypted: File? = null
            var password: CharArray? = null
            try {
                val protected = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use(ProtectedBackup::isProtected)
                }
                if (protected) password = (ctx.chooseBackupProtection(export = false) ?: run {
                    showOperation(R.string.ui_operation_cancelled)
                    return@launch
                }).password
                val backup = loading(R.string.ui_backup_checking) {
                    // Close even if cancellation arrives just as the IO result is dispatched.
                    withContext(Dispatchers.IO) {
                        if (protected) {
                            ctx.contentResolver.openInputStream(uri)!!.use {
                                decrypted = ProtectedBackup.decrypt(it, ctx.cacheDir, password!!) { coroutineContext.ensureActive() }
                            }
                        }
                        val source = decrypted?.inputStream() ?: ctx.contentResolver.openInputStream(uri)!!
                        source.use {
                            UserDataManager.prepareImport(it).also { result -> prepared = result }
                        }
                    }
                }
                var plan: RimeProfileRestorePlan
                showOperation(R.string.import_user_data)
                while (true) {
                    plan = restorePicker.choose(backup.rimeProfiles) { selected ->
                        loading { withContext(Dispatchers.IO) { UserDataManager.validateRestore(backup, selected) } }
                    } ?: run {
                        showOperation(R.string.ui_operation_cancelled)
                        return@launch
                    }
                    try {
                        loading { withContext(Dispatchers.IO) { UserDataManager.validateRestore(backup, plan) } }
                        break
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { restorePicker.explain(getString(R.string.rime_restore_conflicting_targets)) }
                }
                val targets = plan.destinations.map { (profile, target) ->
                    val path = target ?: File(ctx.getExternalFilesDir(null), "data/$profile")
                    getString(R.string.rime_restore_target_line, profile, path.absolutePath)
                }.joinToString("\n\n")
                val message = getString(R.string.confirm_import_user_data) +
                    if (targets.isEmpty()) "" else "\n\n" + getString(R.string.rime_restore_replace_warning, targets)
                if (!restorePicker.confirm(message)) {
                    showOperation(R.string.ui_operation_cancelled)
                    return@launch
                }
                showOperation(R.string.import_user_data, busy = true)
                loading {
                    // Once confirmed, finish replacement/rollback and restart handling even
                    // when the fragment is destroyed. Never leave an interrupted engine stopped.
                    withContext(NonCancellable) {
                        var stopped = false
                        val connectionName = "UserDataRestore-${UUID.randomUUID()}"
                        val connection = FcitxDaemon.connect(connectionName)
                        try {
                            connection.runOnReady { save() }
                            val token = withContext(Dispatchers.IO) {
                                UserDataManager.validateRestore(backup, plan)
                                FcitxDaemon.beginUserDataRestore()
                                stopped = true
                                org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.prepare(ctx, backup, plan)
                            }
                            ctx.startActivity(org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.intent(ctx, token))
                            // The isolated process now owns this quarantine and stops this process
                            // before copying, so cached preferences/background writers cannot win.
                            prepared = null
                        } catch (e: Exception) {
                            if (stopped) FcitxDaemon.finishFailedUserDataRestore()
                            throw e
                        } finally { FcitxDaemon.disconnect(connectionName) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                showOperation(R.string.ui_operation_failed, e.localizedMessage.orEmpty())
                ctx.importErrorDialog(e)
            }
            finally {
                password?.fill('\u0000')
                withContext(NonCancellable + Dispatchers.IO) { prepared?.close(); decrypted?.delete() }
                importInProgress = false
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()

        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addPreference(PreferenceCategory(ctx).apply {
                setTitle(R.string.ui_operation_status)
                isIconSpaceReserved = false
            })
            operationPreference = Preference(ctx).apply {
                isPersistent = false
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setTitle(R.string.ui_backup_ready)
                isSelectable = false
            }.also { addPreference(it) }
            addPreference(PreferenceCategory(ctx).apply {
                setTitle(R.string.settings_group_data)
                isIconSpaceReserved = false
            })
            addPreference(
                R.string.browse_user_data_dir,
                onClick = {
                    try {
                        ctx.startActivity(buildDocumentsProviderIntent())
                    } catch (e: Exception) {
                        ctx.toast(e)
                    }
                },
                onLongClick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ({
                    try {
                        ctx.startActivity(buildPrimaryStorageIntent())
                    } catch (e: Exception) {
                        ctx.toast(e)
                    }
                }) else null
            )
            addPreference(R.string.export_user_data) {
                beginExport()
            }
            addPreference(R.string.import_user_data) {
                if (!importInProgress && !exportInProgress && pendingExportArchive == null) importLauncher.launch("*/*")
            }
        }
        renderOperation()
    }

    private fun showOperation(message: Int, details: String? = null, busy: Boolean = false) {
        operationMessage = context?.getString(message) ?: return
        operationDetails = details?.takeIf { it.isNotBlank() }
        operationBusy = busy
        renderOperation()
    }

    private fun renderOperation() {
        operationPreference?.apply {
            title = operationMessage ?: context.getString(R.string.ui_backup_ready)
            summary = operationDetails?.let { context.getString(R.string.ui_operation_details) }
            isSelectable = operationDetails != null
            setOnPreferenceClickListener {
                operationDetails?.let { details ->
                    MaterialAlertDialogBuilder(context).setTitle(title).setMessage(details)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                true
            }
        }
    }
}
