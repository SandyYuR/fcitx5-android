/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.clipboardsync.network.SyncTransportPolicy
import java.io.File
import java.io.IOException
import java.util.UUID

/** No Activity or view is retained. WorkManager persists/restarts the job independently. */
class HostsDownloadWorker internal constructor(context: Context, parameters: WorkerParameters,
                                                private val testClient: OkHttpClient?) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, null)

    private val notificationId = 0x20000000 or (id.hashCode() and 0x0fffffff)
    private val manager get() = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val workManager get() = WorkManager.getInstance(applicationContext)

    override suspend fun getForegroundInfo() = foreground(0, 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext Result.failure()
        val files = files(applicationContext, id)
        val publisher = DownloadPublisher(applicationContext)
        try {
            files.locked {
                try {
                    ensureActive()
                    if (files.cancelled) throw CancellationException("Download cancelled")
                    val asset = Json.decodeFromString<ReleaseAsset>(inputData.getString(ASSET) ?: error("Missing release asset"))
                    val url = inputData.getString(URL) ?: error("Missing download URL")
                    setForeground(foreground(files.part.length(), asset.sizeBytes))
                    val client = testClient ?: UpdateRepository.httpClientWithConfiguredHosts(applicationContext).newBuilder()
                        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .addNetworkInterceptor(SyncTransportPolicy).build()
                    ResumableAssetDownload.run(client, url, asset, files) { progress ->
                        setProgressAsync(workDataOf(BYTES to progress.bytes, TOTAL to progress.total, SPEED to progress.speed))
                        // WorkManager owns the foreground notification; progress does not need an Activity.
                        runCatching { manager.notify(notificationId, notification(progress.bytes, progress.total)) }
                    }
                    ensureActive()
                    val uri = publisher.publish(files, asset) {
                        ensureActive()
                        if (files.cancelled) throw CancellationException("Download cancelled")
                    }
                    ensureActive()
                    runCatching {
                        manager.notify(notificationId xor 0x10000000,
                            NotificationCompat.Builder(applicationContext, CHANNEL)
                                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                                .setContentTitle(asset.name).setContentText(applicationContext.getString(R.string.update_asset_finished))
                                .setContentIntent(openPage()).setAutoCancel(true).setOnlyAlertOnce(true).build())
                    }
                    Result.success(workDataOf(URI to uri.toString(), BYTES to files.read().total!!))
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val cancelled = files.cancelled || runCatching {
                            workManager.getWorkInfoById(id).get()?.state == WorkInfo.State.CANCELLED
                        }.getOrDefault(false)
                        if (cancelled) {
                            publisher.removePending(files)
                            files.removePrivateContent()
                        }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val retry = e is IOException && e !is InvalidAssetDownload && runAttemptCount < MAX_RETRIES
            if (retry) Result.retry()
            else {
                runCatching { files.tryLocked { publisher.removePending(files); files.removePrivateContent() } }
                Result.failure(workDataOf(ERROR to applicationContext.getString(R.string.update_asset_failed)))
            }
        }
    }

    private fun openPage() = PendingIntent.getActivity(applicationContext, notificationId,
        Intent(applicationContext, UpdateCheckActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(bytes: Long, total: Long): android.app.Notification {
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL,
            applicationContext.getString(R.string.update_downloading), NotificationManager.IMPORTANCE_LOW))
        val percent = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(inputData.getString(NAME).orEmpty())
            .setContentText(applicationContext.getString(R.string.update_downloading)).setContentIntent(openPage())
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100, percent, total <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, applicationContext.getString(android.R.string.cancel),
                workManager.createCancelPendingIntent(id)).build()
    }
    private fun foreground(bytes: Long, total: Long): ForegroundInfo = if (Build.VERSION.SDK_INT >= 29)
        ForegroundInfo(notificationId, notification(bytes, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else ForegroundInfo(notificationId, notification(bytes, total))

    internal companion object {
        const val ASSET = "asset"
        const val URL = "url"
        const val NAME = "name"
        const val URI = "uri"
        const val BYTES = "bytes"
        const val TOTAL = "total"
        const val SPEED = "speed"
        const val ERROR = "error"
        const val CHANNEL = "update-downloads"
        const val MAX_RETRIES = 5
        fun files(context: Context, id: UUID) = DownloadFiles(File(context.noBackupFilesDir, "hosts-downloads"), id)
    }
}
