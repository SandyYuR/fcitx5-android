/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.rimeupdate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.clipboardsync.network.SyncTransportPolicy
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.RimeSchemePayload
import org.fcitx.fcitx5.android.data.RimeSchemeTask
import org.fcitx.fcitx5.android.data.WanxiangPayload
import org.fcitx.fcitx5.android.ui.main.update.*
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangDownloadProgress
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangTarget
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal interface RimeSchemeUpdateHost {
    fun client(): OkHttpClient
    suspend fun verify(target: WanxiangTarget)
    suspend fun install(id: UUID)
    fun close()
}

private class EngineSchemeUpdateHost(private val context: Context, id: UUID) : RimeSchemeUpdateHost {
    private val name = "RimeSchemeUpdate-$id"
    private val connection by lazy { FcitxDaemon.connect(name) }
    override fun client(): OkHttpClient = UpdateRepository.httpClientWithConfiguredHosts(context).newBuilder()
        .callTimeout(0, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(SyncTransportPolicy).build()
    override suspend fun verify(target: WanxiangTarget) {
        val actual = connection.runOnReady { getAddonSubConfig("rime", "profile-manager") }
        require(WanxiangTarget.from(context, actual) == target) { context.getString(R.string.wanxiang_target_changed) }
    }
    override suspend fun install(id: UUID) {
        connection.runOnReady { setAddonSubConfig("rime", "rime-custom-update", RawConfig(arrayOf(RawConfig("RequestId", id.toString())))) }
    }
    override fun close() = FcitxDaemon.disconnect(name)
}

/** Downloads survive navigation/constraint loss. Only the engine owner writes the live profile. */
class RimeSchemeUpdateWorker internal constructor(context: Context, parameters: WorkerParameters,
    private val testHost: RimeSchemeUpdateHost?) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, null)
    private val notificationId = 0x53000000 or (id.hashCode() and 0x00ffffff)
    private val notifications get() = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    override suspend fun getForegroundInfo() = foreground("", "", null, false)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        DownloadFiles(RimeSchemeUpdates.root(applicationContext), id).locked { execute() }
    }

    private suspend fun execute(): Result {
        val directory = RimeSchemeUpdates.directory(applicationContext, id)
        val payload = WanxiangPayload.child(directory, "payload")
        val scratch = WanxiangPayload.child(directory, "scratch")
        val prepared = WanxiangPayload.child(directory, "prepared")
        val complete = WanxiangPayload.child(directory, "completed")
        var host: RimeSchemeUpdateHost? = null
        var installing = false
        var retryable = false
        var keepDownloads = false
        var index = 0
        var total = 0
        var label = ""
        fun data(phase: String, bytes: Long = 0, size: Long = 0, speed: Long = 0) = workDataOf(
            PHASE to phase, INDEX to index, COUNT to total, NAME to label,
            BYTES to bytes, TOTAL to size, SPEED to speed, TARGET to inputData.getString(TARGET))
        suspend fun phase(value: String) {
            setProgress(data(value))
            setForeground(foreground(applicationContext.getString(phaseLabel(value)), label, null, installing))
        }
        try {
            if (complete.isFile) return Result.success(workDataOf(TARGET to inputData.getString(TARGET), FILES to complete.readText().toInt()))
            val request = RimeSchemeUpdates.request(applicationContext, id)
            require(inputData.getString(TARGET) == Json.encodeToString(request.target)) { "更新目标与任务快照不一致" }
            total = request.tasks.size
            val runtime = (testHost ?: EngineSchemeUpdateHost(applicationContext, id)).also { host = it }
            phase("prepare")
            runtime.verify(request.target)
            listOf(payload, scratch, prepared).forEach { FileUtil.removeFile(it).getOrThrow() }
            check(payload.mkdirs())
            val client by lazy { runtime.client() }
            request.tasks.forEachIndexed { ordinal, task ->
                currentCoroutineContext().ensureActive()
                index = ordinal + 1; label = task.name
                val files = DownloadFiles(File(directory, "downloads"), UUID.fromString(task.id))
                files.locked {
                    retryable = task.remote
                    phase(if (retryable) "download" else "local")
                    var pending: ListenableFuture<Void>? = null
                    val publish: (AssetDownloadProgress) -> Unit = { progress ->
                        pending = setProgressAsync(data(if (retryable) "download" else "local", progress.bytes, progress.total, progress.speed))
                        runCatching { notifications.notify(notificationId, notification(
                            applicationContext.getString(if (retryable) R.string.scheme_update_downloading else R.string.scheme_update_local),
                            label, WanxiangDownloadProgress.percent(progress.bytes, progress.total), false)) }
                    }
                    try {
                        if (retryable) ResumableAssetDownload.run(client, task.source, ReleaseAsset(task.fileName, task.source, 0, null), files, publish)
                        else copyLocal(task, files, publish)
                    } finally { withContext(NonCancellable) { pending?.get() } }
                    retryable = false
                    phase(if (task.unpack) "extract" else "copy")
                    val coroutine = currentCoroutineContext()
                    RimeSchemePayload.stage(files.part, task, scratch, payload) { coroutine.ensureActive() }
                }
            }
            currentCoroutineContext().ensureActive()
            runtime.verify(request.target)
            val count = payload.walkTopDown().count(File::isFile)
            if (count > 0) {
                FileUtil.writeAtomically(prepared, "1")
                installing = true
                phase("install")
                withContext(NonCancellable) {
                    runtime.install(id)
                    FileUtil.writeAtomically(complete, count.toString())
                }
            } else FileUtil.writeAtomically(complete, "0")
            return Result.success(workDataOf(TARGET to inputData.getString(TARGET), FILES to count))
        } catch (error: CancellationException) {
            keepDownloads = !installing
            if (keepDownloads) withContext(NonCancellable) { runCatching { setProgress(data("waiting")) } }
            throw error
        } catch (error: Exception) {
            if (retryable && error is IOException && error !is InvalidAssetDownload && runAttemptCount < 2) {
                keepDownloads = true
                phase("waiting")
                return Result.retry()
            }
            return Result.failure(workDataOf(ERROR to (error.message ?: error.javaClass.simpleName).take(1000),
                TARGET to inputData.getString(TARGET), INDEX to index, COUNT to total, NAME to label))
        } finally {
            host?.close()
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { listOf(payload, scratch, prepared).forEach { FileUtil.removeFile(it).getOrThrow() } }
                if (keepDownloads) keepDownloads = runCatching {
                    WorkManager.getInstance(applicationContext).getWorkInfoById(id).get()?.state != WorkInfo.State.CANCELLED
                }.getOrDefault(true)
                if (!keepDownloads) runCatching { FileUtil.removeFile(WanxiangPayload.child(directory, "downloads")).getOrThrow() }
            }
        }
    }

    private suspend fun copyLocal(task: RimeSchemeTask, files: DownloadFiles, progress: (AssetDownloadProgress) -> Unit) {
        val uri = if (task.source.startsWith('/')) Uri.fromFile(File(task.source)) else Uri.parse(task.source)
        val file = if (uri.scheme == "file") File(java.net.URI(uri.toString())) else null
        require(file == null || file.isFile && file.canRead()) { "无法读取本地文件，请通过文件选择器重新授权" }
        val size = if (file != null) file.length() else runCatching {
            applicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).coerceAtLeast(0) else 0
            } ?: 0
        }.getOrDefault(0)
        require(size <= DownloadFiles.MAX_BYTES) { "本地文件超过512MiB限制" }
        files.requireSpace(size * 2)
        val input = if (file != null) file.inputStream()
            else applicationContext.contentResolver.openInputStream(uri) ?: error("无法读取本地文件，请重新选择并授权")
        input.use { source ->
            files.part.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var bytes = 0L
                var sampledBytes = 0L
                var sampledAt = System.nanoTime()
                var spaceAt = 0L
                progress(AssetDownloadProgress(0, size, 0))
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = source.read(buffer); if (n < 0) break
                    bytes += n
                    require(bytes <= DownloadFiles.MAX_BYTES) { "本地文件超过512MiB限制" }
                    output.write(buffer, 0, n)
                    if (bytes - spaceAt >= 1024 * 1024) { files.requireSpace(bytes); spaceAt = bytes }
                    val now = System.nanoTime()
                    if (now - sampledAt >= 1_000_000_000L) {
                        progress(AssetDownloadProgress(bytes, size, (bytes - sampledBytes) * 1_000_000_000L / (now - sampledAt)))
                        sampledBytes = bytes; sampledAt = now
                    }
                }
                require(bytes > 0 && (size == 0L || bytes == size)) { "本地文件为空或读取不完整" }
                output.fd.sync()
                progress(AssetDownloadProgress(bytes, bytes, 0))
            }
        }
    }

    private fun notification(title: String, name: String, percent: Int?, installing: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(NotificationChannel(WORK_CHANNEL,
            applicationContext.getString(R.string.scheme_update_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, notificationId,
            Intent(applicationContext, RimeSchemeUpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(applicationContext, WORK_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(applicationContext.getString(R.string.scheme_update_title))
            .setContentText(title).setSubText(if (percent == null) name else "$name · $percent%")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, percent ?: 0, percent == null).apply {
                if (!installing) addAction(android.R.drawable.ic_menu_close_clear_cancel,
                    applicationContext.getString(android.R.string.cancel), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            }.build()
    }
    private fun foreground(title: String, name: String, percent: Int?, installing: Boolean): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification(title, name, percent, installing), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification(title, name, percent, installing))

    internal companion object {
        const val TARGET = "target"
        const val PHASE = "phase"
        const val INDEX = "index"
        const val COUNT = "count"
        const val NAME = "name"
        const val BYTES = "bytes"
        const val TOTAL = "total"
        const val SPEED = "speed"
        const val ERROR = "error"
        const val FILES = "files"
        private const val WORK_CHANNEL = "rime-custom-updates"
        fun phaseLabel(phase: String?) = when (phase) {
            "download" -> R.string.scheme_update_downloading
            "local" -> R.string.scheme_update_local
            "extract" -> R.string.scheme_update_extracting
            "copy" -> R.string.scheme_update_copying
            "install" -> R.string.scheme_update_installing
            "waiting" -> R.string.scheme_update_waiting
            else -> R.string.scheme_update_preparing
        }
    }
}
