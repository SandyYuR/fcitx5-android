/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.RimeDataDirectoryState
import org.fcitx.fcitx5.android.data.WanxiangPayload
import org.fcitx.fcitx5.android.ui.main.update.DownloadFiles
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
internal data class WanxiangTarget(val profile: String, val path: String) {
    companion object {
        fun from(context: Context, raw: RawConfig): WanxiangTarget {
            require(raw.findByName("Error")?.value.isNullOrBlank()) { raw.findByName("Error")!!.value }
            val state = RimeDataDirectoryState.fromConfig(raw, File(context.getExternalFilesDir(null) ?: context.filesDir, "data"))
            val directory = File(state.paths.getValue(state.current)).canonicalFile
            require(directory.isDirectory && directory.canRead() && directory.canWrite()) { "Cannot access the current Rime directory" }
            return WanxiangTarget(state.current, directory.path)
        }
    }
}

internal object WanxiangUpdates {
    const val WORK = "wanxiang-update"
    private val commands get() = org.fcitx.fcitx5.android.data.RimeUpdateCoordinator.commands
    fun root(context: Context) = File(context.noBackupFilesDir, "wanxiang-updates")
    fun directory(context: Context, id: UUID) = org.fcitx.fcitx5.android.data.WanxiangPayload.child(root(context).canonicalFile, id.toString())
    fun observe(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK)

    suspend fun enqueue(context: Context, selection: WanxiangSelection, target: WanxiangTarget) = commands.withLock {
        withContext(Dispatchers.IO) {
            org.fcitx.fcitx5.android.data.RimeUpdateCoordinator.requireOtherIdle(context, WORK)
            require(selection.packages().isNotEmpty()) { "请先选择附加词库" }
            val manager = WorkManager.getInstance(context)
            val old = manager.getWorkInfosForUniqueWork(WORK).get()
            if (old.any { !it.state.isFinished }) return@withContext
            old.forEach { info -> runCatching {
                val files = DownloadFiles(root(context), info.id)
                if (files.tryLocked {
                    listOf("payload", "downloads", "completed", "completed.tmp", "failure.json", "failure.json.tmp",
                        "selection.json", "selection.json.tmp", "auxiliary.json", "auxiliary.json.tmp").forEach {
                        FileUtil.removeFile(WanxiangPayload.child(files.directory, it)).getOrThrow()
                    }
                }) files.removeEmptyDirectory()
            } }
            val request = OneTimeWorkRequestBuilder<WanxiangUpdateWorker>()
                .setInputData(workDataOf(WanxiangUpdateWorker.TARGET to Json.encodeToString(target)))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            FileUtil.writeAtomically(WanxiangPayload.child(directory(context, request.id), "selection.json"), Json.encodeToString(selection))
            manager.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request).result.get()
        }
    }

    fun preferences(context: Context) = context.getSharedPreferences(WORK, Context.MODE_PRIVATE)
    fun jobSelection(context: Context, id: UUID, legacy: String? = null): WanxiangSelection {
        val text = legacy ?: WanxiangPayload.child(directory(context, id), "selection.json").let {
            require(it.isFile && it.length() <= 64 * 1024) { "万象更新任务配置缺失或过大，请重新开始更新" }
            it.readText()
        }
        return Json.decodeFromString<WanxiangSelection>(text).also { it.packages() }
    }
    fun selection(context: Context): WanxiangSelection = runCatching {
        Json.decodeFromString<WanxiangSelection>(preferences(context).getString("selection", "")!!).also { it.packages() }
    }.getOrDefault(WanxiangSelection())
}
