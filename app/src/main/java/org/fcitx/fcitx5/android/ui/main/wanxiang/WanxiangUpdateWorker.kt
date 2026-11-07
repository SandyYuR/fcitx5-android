/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.WanxiangPayload
import org.fcitx.fcitx5.android.data.WanxiangProtection
import org.fcitx.fcitx5.android.ui.main.update.DownloadFiles
import org.fcitx.fcitx5.android.ui.main.update.InvalidAssetDownload
import org.fcitx.fcitx5.android.ui.main.update.ReleaseAsset
import org.fcitx.fcitx5.android.ui.main.update.ResumableAssetDownload
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.io.IOException
import java.text.NumberFormat
import java.util.UUID

internal interface WanxiangUpdateHost {
    fun client(): OkHttpClient
    fun url(asset: WanxiangPackage): String = asset.url
    fun resolvedUrl(client: OkHttpClient, asset: WanxiangPackage): String = url(asset)
    suspend fun verify(target: WanxiangTarget)
    suspend fun install(target: WanxiangTarget, id: UUID)
    fun close()
}

private class EngineWanxiangUpdateHost(private val context: Context, id: UUID) : WanxiangUpdateHost {
    private val name = "WanxiangUpdate-$id"
    private val connection by lazy { FcitxDaemon.connect(name) }
    override fun client(): OkHttpClient = WanxiangGithub.client(context)
    override fun resolvedUrl(client: OkHttpClient, asset: WanxiangPackage) = WanxiangGithub.downloadUrl(client, asset)
    override suspend fun verify(target: WanxiangTarget) {
        val raw = connection.runOnReady { getAddonSubConfig("rime", "profile-manager") }
        require(WanxiangTarget.from(context, raw) == target) { context.getString(R.string.wanxiang_target_changed) }
    }
    override suspend fun install(target: WanxiangTarget, id: UUID) {
        connection.runOnReady {
            setAddonSubConfig("rime", "wanxiang-update", RawConfig(arrayOf(
                RawConfig("Profile", target.profile), RawConfig("Path", target.path), RawConfig("RequestId", id.toString())
            )))
        }
    }
    override fun close() = FcitxDaemon.disconnect(name)
}

/** WorkManager retains downloads across page recreation and process restarts. */
class WanxiangUpdateWorker internal constructor(context: Context, parameters: WorkerParameters,
    private val testHost: WanxiangUpdateHost?) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, null)
    private val notificationId = 0x57000000 or (id.hashCode() and 0x00ffffff)
    private val manager get() = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    override suspend fun getForegroundInfo() = foreground("", 0, 0, false)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // A cancelled WorkInfo can be terminal before its writer/installer has unwound.
        DownloadFiles(WanxiangUpdates.root(applicationContext), id).locked { execute() }
    }

    private suspend fun execute(): Result = withContext(Dispatchers.IO) {
        val directory = WanxiangUpdates.directory(applicationContext, id)
        val payload = WanxiangPayload.child(directory, "payload")
        val completed = WanxiangPayload.child(directory, "completed")
        var host: WanxiangUpdateHost? = null
        var keepDownloads = false
        var installing = false
        try {
            require(directory.isDirectory || directory.mkdirs())
            fun success(noChanges: Boolean) = Result.success(workDataOf(
                TARGET to inputData.getString(TARGET), NO_CHANGES to noChanges))
            fun complete(noChanges: Boolean): Result {
                FileUtil.writeAtomically(completed, if (noChanges) COMPLETED_NO_CHANGES else "1")
                return success(noChanges)
            }
            if (completed.isFile) return@withContext success(
                completed.length() == COMPLETED_NO_CHANGES.length.toLong() && completed.readText() == COMPLETED_NO_CHANGES)
            val target = Json.decodeFromString<WanxiangTarget>(inputData.getString(TARGET) ?: error("Missing Rime target"))
            val selection = WanxiangUpdates.jobSelection(applicationContext, id, inputData.getString(SELECTION))
            FileUtil.writeAtomically(WanxiangPayload.child(directory, "selection.json"), Json.encodeToString(selection))
            val runtime = (testHost ?: EngineWanxiangUpdateHost(applicationContext, id)).also { host = it }
            phase("preparing", applicationContext.getString(R.string.wanxiang_preparing))
            runtime.verify(target)
            FileUtil.removeFile(payload).getOrThrow()
            require(payload.mkdirs())
            val packages = selection.packages()
            require(packages.isNotEmpty()) { "请先选择附加词库" }
            val client = runtime.client()
            val protection = WanxiangProtection(selection.protection)
            var preparedExtras = false
            var auxiliary = emptyMap<String, String>()
            for ((index, asset) in packages.withIndex()) {
                ensureActive()
                val downloadKey = if (asset.kind == WanxiangPackage.Kind.EXTRA) "${asset.kind}:${asset.name}" else asset.kind.name
                val downloadId = UUID.nameUUIDFromBytes(downloadKey.toByteArray(Charsets.UTF_8))
                val files = DownloadFiles(File(directory, "downloads"), downloadId)
                files.locked {
                    val title = applicationContext.getString(R.string.wanxiang_downloading,
                        applicationContext.getString(kindLabel(asset.kind)), index + 1, packages.size)
                    // Publish the new file before resolving metadata or waiting for HTTP headers.
                    phase("download", title, asset.name)
                    var pendingProgress: ListenableFuture<Void>? = null
                    try {
                        ResumableAssetDownload.run(client, runtime.resolvedUrl(client, asset), ReleaseAsset(asset.name, asset.url, 0, null), files) { progress ->
                            pendingProgress = setProgressAsync(workDataOf(PHASE to "download", TITLE to title, NAME to asset.name,
                                BYTES to progress.bytes, TOTAL to progress.total, SPEED to progress.speed, TARGET to inputData.getString(TARGET)))
                            runCatching { manager.notify(notificationId, notification(title, progress.bytes, progress.total, false, asset.name)) }
                        }
                    } finally {
                        // The HTTP callback has finished; wait on IO for its last queued DB update
                        // before a following phase can replace the download progress.
                        withContext(NonCancellable) { pendingProgress?.get() }
                    }
                    phase("extract", applicationContext.getString(R.string.wanxiang_extracting), asset.name)
                    when (asset.kind) {
                        WanxiangPackage.Kind.MODEL -> WanxiangPayload.model(files.part, payload)
                        WanxiangPackage.Kind.EXTRA -> {
                            if (!preparedExtras) {
                                protection.filter(payload, File(target.path))
                                if (selection.scheme == WanxiangSelection.Scheme.PRO) auxiliary = WanxiangExtraDictionaries.prepareAuxiliary(directory, payload, File(target.path)) { ensureActive() }
                                preparedExtras = true
                            }
                            WanxiangExtraDictionaries.stage(files.part, payload, asset, selection.scheme, auxiliary) { ensureActive() }
                        }
                        else -> WanxiangPayload.extract(files.part, payload, asset.kind == WanxiangPackage.Kind.DICTIONARY, allowOverrides = true) { ensureActive() }
                    }
                }
            }
            ensureActive()
            runtime.verify(target)
            protection.filter(payload, File(target.path))
            WanxiangExtraDictionaries.verifyAuxiliary(directory, payload, File(target.path)) { ensureActive() }
            if (payload.walkTopDown().none { it.isFile }) {
                return@withContext complete(noChanges = true)
            }
            installing = true
            phase("install", applicationContext.getString(R.string.wanxiang_installing), installing = true)
            // Finishing or rolling back an installation must outlive cancellation of the worker.
            withContext(NonCancellable) {
                runtime.install(target, id)
                // Protection is checked again by the owner immediately before installation.
                complete(noChanges = payload.walkTopDown().none { it.isFile })
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                // Constraint loss stops and reschedules a CoroutineWorker too. Only an explicit
                // cancellation is terminal; retain verified files and resumable partials otherwise.
                keepDownloads = !installing
                if (keepDownloads) runCatching { setProgress(phaseData("waiting", applicationContext.getString(R.string.wanxiang_waiting))) }
            }
            throw error
        }
        catch (error: Exception) {
            if (!installing && error is IOException && error !is InvalidAssetDownload && runAttemptCount < 2) {
                keepDownloads = true
                phase("waiting", applicationContext.getString(R.string.wanxiang_waiting))
                Result.retry()
            } else Result.failure(workDataOf(ERROR to error.stackTraceToString().take(2000), TARGET to inputData.getString(TARGET)))
        } finally {
            host?.close()
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { FileUtil.removeFile(payload).getOrThrow() }
                if (keepDownloads) keepDownloads = runCatching {
                    WorkManager.getInstance(applicationContext).getWorkInfoById(id).get()?.state != WorkInfo.State.CANCELLED
                }.getOrDefault(true)
                if (!keepDownloads) runCatching { FileUtil.removeFile(WanxiangPayload.child(directory, "downloads")).getOrThrow() }
            }
        }
    }

    private fun phaseData(phase: String, title: String, name: String? = null) = workDataOf(
        PHASE to phase, TITLE to title, NAME to name, BYTES to 0L, TOTAL to 0L, SPEED to 0L,
        TARGET to inputData.getString(TARGET))

    private suspend fun phase(phase: String, title: String, name: String? = null, installing: Boolean = false) {
        setProgress(phaseData(phase, title, name))
        setForeground(foreground(title, 0, 0, installing, name.takeIf { phase == "download" }))
    }

    private fun notification(title: String, bytes: Long, total: Long, installing: Boolean, name: String? = null): Notification {
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL,
            applicationContext.getString(R.string.wanxiang_update), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, notificationId,
            Intent(applicationContext, WanxiangUpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val percent = WanxiangDownloadProgress.percent(bytes, total)
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.wanxiang_update))
            .setContentText(title).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setSubText(name?.let { if (percent == null) it else applicationContext.getString(R.string.wanxiang_file_progress,
                it, NumberFormat.getPercentInstance().format(percent / 100.0)) })
            .setProgress(100, percent ?: 0, percent == null)
            .apply { if (!installing) addAction(android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(android.R.string.cancel), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)) }
            .build()
    }
    private fun foreground(title: String, bytes: Long, total: Long, installing: Boolean, name: String? = null): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification(title, bytes, total, installing, name), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification(title, bytes, total, installing, name))

    internal companion object {
        const val SELECTION = "selection"
        const val TARGET = "target"
        const val PHASE = "phase"
        const val TITLE = "title"
        const val NAME = "name"
        const val BYTES = "bytes"
        const val TOTAL = "total"
        const val SPEED = "speed"
        const val ERROR = "error"
        const val NO_CHANGES = "no_changes"
        private const val COMPLETED_NO_CHANGES = "no-changes"
        private const val CHANNEL = "wanxiang-updates"
        fun kindLabel(kind: WanxiangPackage.Kind) = when (kind) {
            WanxiangPackage.Kind.SCHEME -> R.string.wanxiang_scope_scheme
            WanxiangPackage.Kind.DICTIONARY -> R.string.wanxiang_scope_dictionary
            WanxiangPackage.Kind.MODEL -> R.string.wanxiang_scope_model
            WanxiangPackage.Kind.EXTRA -> R.string.wanxiang_extra
        }
    }
}
