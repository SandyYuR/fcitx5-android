/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.update

import android.content.Context
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object HostsDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        timber.log.Timber.w(e, "Hosts download command failed")
    })
    private val sequenceLock = Any()
    private var tail: Job = CompletableDeferred(Unit)
    /** Queue order is captured on the caller; database work always dispatches to IO. */
    internal fun <T> submit(block: suspend () -> T): Deferred<T> = synchronized(sequenceLock) {
        val previous = tail
        scope.async(start = CoroutineStart.LAZY) { previous.join(); block() }.also {
            tail = it
            it.start()
        }
    }
    private fun name(asset: ReleaseAsset): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(asset).toByteArray())
        return "hosts-update-" + digest.joinToString("") { "%02x".format(it) }
    }

    fun enqueue(context: Context, asset: ReleaseAsset, url: String): Deferred<UUID> {
        val application = context.applicationContext
        return submit {
                withContext(Dispatchers.IO) {
                    val manager = WorkManager.getInstance(application)
                    val key = name(asset)
                    manager.getWorkInfosForUniqueWork(key).get().firstOrNull { !it.state.isFinished }?.let { return@withContext it.id }
                    val request = OneTimeWorkRequestBuilder<HostsDownloadWorker>()
                        .setInputData(workDataOf(HostsDownloadWorker.ASSET to Json.encodeToString(asset),
                            HostsDownloadWorker.URL to url, HostsDownloadWorker.NAME to asset.name))
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .addTag("hosts-update-download").build()
                    // All old work is terminal here. REPLACE removes old history, so observation
                    // cannot let a previous download's result overwrite this attempt.
                    manager.enqueueUniqueWork(key, ExistingWorkPolicy.REPLACE, request).result.get()
                    request.id
                }
        }
    }

    fun observe(context: Context, asset: ReleaseAsset): Flow<WorkInfo?> =
        WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWorkFlow(name(asset))
            .map { works -> works.firstOrNull { !it.state.isFinished } ?: works.lastOrNull() }

    fun cancel(context: Context, asset: ReleaseAsset): Job {
        val application = context.applicationContext
        return submit {
                withContext(Dispatchers.IO) {
                    val manager = WorkManager.getInstance(application)
                    val work = manager.getWorkInfosForUniqueWork(name(asset)).get().filter { !it.state.isFinished }
                    work.forEach { HostsDownloadWorker.files(application, it.id).markCancelled() }
                    manager.cancelUniqueWork(name(asset)).result.get()
                    work.forEach { cleanup(application, it.id) }
                }
        }
    }

    fun cleanup(context: Context, id: UUID) {
        if (Build.VERSION.SDK_INT < 29) return
        val files = HostsDownloadWorker.files(context.applicationContext, id)
        runCatching {
            if (files.tryLocked { DownloadPublisher(context.applicationContext).removePending(files); files.removePrivateContent() })
                files.removeEmptyDirectory()
        }
    }

    fun prune(context: Context) {
        val application = context.applicationContext
        scope.launch {
            val root = java.io.File(application.noBackupFilesDir, "hosts-downloads")
            val directories = root.listFiles()?.take(512).orEmpty()
            if (directories.isEmpty()) return@launch
            val manager = WorkManager.getInstance(application)
            for (directory in directories) {
                val id = runCatching { UUID.fromString(directory.name) }.getOrNull() ?: continue
                val state = manager.getWorkInfoById(id).get()?.state
                if (state?.isFinished == true || (state == null &&
                        System.currentTimeMillis() - directory.lastModified() > TimeUnit.DAYS.toMillis(7))) cleanup(application, id)
            }
        }
    }
}
