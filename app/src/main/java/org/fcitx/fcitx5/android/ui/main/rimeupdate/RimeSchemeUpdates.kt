/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.rimeupdate

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.RimeSchemeTask
import org.fcitx.fcitx5.android.data.RimeUpdateCoordinator
import org.fcitx.fcitx5.android.data.WanxiangPayload
import org.fcitx.fcitx5.android.ui.main.update.DownloadFiles
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangTarget
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
internal data class RimeSchemeRequest(val target: WanxiangTarget, val tasks: List<RimeSchemeTask>) {
    fun validate(): RimeSchemeRequest {
        require(tasks.size in 1..64 && tasks.map { it.id }.distinct().size == tasks.size) { "任务数量无效" }
        tasks.forEach { it.validated() }
        require(target.path.length <= 2048 && target.profile.length <= 120)
        require(Json.encodeToString(target).toByteArray().size <= 4096) { "工作目录信息过长" }
        return this
    }
}

internal object RimeSchemeUpdates {
    const val WORK = RimeUpdateCoordinator.CUSTOM
    private const val TASKS = "tasks"
    private const val MAX_JSON = 2 * 1024 * 1024
    private fun preferences(context: Context) = context.getSharedPreferences(WORK, Context.MODE_PRIVATE)
    fun root(context: Context) = File(context.noBackupFilesDir, "rime-custom-updates")
    fun directory(context: Context, id: UUID) = WanxiangPayload.child(root(context).canonicalFile, id.toString())
    fun observe(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK)

    fun tasks(context: Context): List<RimeSchemeTask> {
        val text = preferences(context).getString(TASKS, null) ?: return emptyList()
        require(text.toByteArray().size <= MAX_JSON) { "任务配置过大" }
        return Json.decodeFromString<List<RimeSchemeTask>>(text).also(::validateTasks)
    }
    private fun validateTasks(tasks: List<RimeSchemeTask>) {
        require(tasks.size <= 64 && tasks.map { it.id }.distinct().size == tasks.size) { "最多保存64个不同任务" }
        tasks.forEach { it.validated() }
    }
    fun save(context: Context, tasks: List<RimeSchemeTask>) {
        validateTasks(tasks)
        val text = Json.encodeToString(tasks)
        require(text.toByteArray().size <= MAX_JSON) { "任务配置过大" }
        check(preferences(context).edit().putString(TASKS, text).commit()) { "无法保存任务" }
    }
    fun reset(context: Context) {
        preferences(context).getString(TASKS, null)?.let { text ->
            FileUtil.writeAtomically(File(context.noBackupFilesDir, "rime-custom-tasks-recovery-${System.currentTimeMillis()}.json"), text)
        }
        check(preferences(context).edit().remove(TASKS).commit())
    }
    fun request(context: Context, id: UUID): RimeSchemeRequest {
        val file = WanxiangPayload.child(directory(context, id), "request.json")
        require(file.isFile && file.length() <= MAX_JSON) { "更新任务快照缺失或过大" }
        return Json.decodeFromString<RimeSchemeRequest>(file.readText()).validate()
    }

    suspend fun enqueue(context: Context, request: RimeSchemeRequest) = RimeUpdateCoordinator.commands.withLock {
        withContext(Dispatchers.IO) {
            request.validate()
            val text = Json.encodeToString(request)
            require(text.toByteArray().size <= MAX_JSON)
            RimeUpdateCoordinator.requireOtherIdle(context, WORK)
            val manager = WorkManager.getInstance(context)
            val previous = manager.getWorkInfosForUniqueWork(WORK).get()
            require(previous.none { !it.state.isFinished }) { "自定义更新正在进行" }
            previous.forEach { info -> runCatching {
                val files = DownloadFiles(root(context), info.id)
                if (files.tryLocked {
                    listOf("downloads", "payload", "scratch", "request.json", "prepared", "completed").forEach {
                        FileUtil.removeFile(WanxiangPayload.child(files.directory, it)).getOrThrow()
                    }
                }) files.removeEmptyDirectory()
            } }
            val work = OneTimeWorkRequestBuilder<RimeSchemeUpdateWorker>()
                .setInputData(workDataOf(RimeSchemeUpdateWorker.TARGET to Json.encodeToString(request.target)))
                .setConstraints(Constraints.Builder().apply {
                    if (request.tasks.any { it.remote })
                        setRequiredNetworkType(NetworkType.CONNECTED)
                }.build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            FileUtil.writeAtomically(WanxiangPayload.child(directory(context, work.id), "request.json"), text)
            manager.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, work).result.get()
        }
    }
}
