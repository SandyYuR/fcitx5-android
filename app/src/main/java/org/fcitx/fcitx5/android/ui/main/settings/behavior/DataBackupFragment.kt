/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Build
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.buildDocumentsProviderIntent
import org.fcitx.fcitx5.android.utils.buildPrimaryStorageIntent
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.toast

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

    private var exportTimestamp = System.currentTimeMillis()

    private lateinit var exportLauncher: ActivityResultLauncher<String>

    private lateinit var importLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val cr = ctx.contentResolver
                lifecycleScope.withLoadingDialog(ctx) {
                    val name = cr.queryFileName(uri) ?: return@withLoadingDialog
                    if (!name.endsWith(".zip")) {
                        ctx.importErrorDialog(R.string.exception_user_data_filename, name)
                        return@withLoadingDialog
                    }
                    try {
                        // stop fcitx before overwriting files
                        FcitxDaemon.stopFcitx()
                        val metadata = withContext(Dispatchers.IO) {
                            val inputStream = cr.openInputStream(uri)!!
                            UserDataManager.import(inputStream).getOrThrow()
                        }
                        AppUtil.showRestartNotification(ctx)
                        val exportTime = formatDateTime(metadata.exportTime)
                        ctx.toast(getString(R.string.user_data_imported, exportTime))
                        // delay exit to ensure Notification and Toast has been created
                        lifecycleScope.launch {
                            delay(400L)
                            AppUtil.exit()
                        }
                    } catch (e: Exception) {
                        // restart fcitx in case importing failed
                        FcitxDaemon.startFcitx()
                        ctx.importErrorDialog(e)
                    }
                }
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(ctx) {
                    try {
                        withContext(Dispatchers.IO) {
                            val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                            UserDataManager.export(outputStream, exportTimestamp).getOrThrow()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        ctx.toast(e)
                    }
                }
            }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()

        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
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
                lifecycleScope.withLoadingDialog(ctx) {
                    // 导出前先让引擎把状态落盘，否则拿到的是上次保存的快照。
                    viewModel.fcitx.runOnReady { save() }
                    exportTimestamp = System.currentTimeMillis()
                    exportLauncher.launch("fcitx5-android_${iso8601UTCDateTime(exportTimestamp)}.zip")
                }
            }
            addPreference(R.string.import_user_data) {
                AlertDialog.Builder(ctx)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setTitle(R.string.import_user_data)
                    .setMessage(R.string.confirm_import_user_data)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        importLauncher.launch("application/zip")
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
