/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.rimeupdate

import android.app.Application
import android.content.Context
import androidx.work.*
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.fcitx.fcitx5.android.data.RimeSchemeTask
import org.fcitx.fcitx5.android.data.WanxiangUpdateTransaction
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangTarget
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = Application::class)
class RimeSchemeUpdateWorkerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var user: File
    private lateinit var server: MockWebServer
    private var verifies = 0
    private var installs = 0
    private var failDeploy = false
    private var changeTarget = false
    private var gate: SettableFuture<Void>? = null
    private val progress = ConcurrentLinkedQueue<Data>()
    private fun immediate() = SettableFuture.create<Void>().apply { set(null) }
    private val host = object : RimeSchemeUpdateHost {
        override fun client() = OkHttpClient()
        override suspend fun verify(target: WanxiangTarget) { verifies++; check(!(changeTarget && verifies > 1)) { "directory changed" } }
        override suspend fun install(id: UUID) {
            installs++
            WanxiangUpdateTransaction.install(user, File(RimeSchemeUpdates.directory(context, id), "payload"),
                { if (failDeploy) error("deployment failed") }, {}, generic = true)
        }
        override fun close() = Unit
    }
    @Before fun prepare() {
        user = File(context.filesDir, UUID.randomUUID().toString()).apply { mkdirs() }
        File(user, "ice.schema.yaml").writeText("old")
        File(user, "default.custom.yaml").writeText("personal")
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        server = MockWebServer().apply { start() }
    }
    @After fun close() { server.shutdown(); WorkManagerTestInitHelper.closeWorkDatabase() }
    private fun zip(path: String, value: String) = ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use {
        it.putNextEntry(ZipEntry(path)); it.write(value.toByteArray()); it.closeEntry()
    } }.toByteArray()
    private fun recipe(path: String = "/scheme") = RimeSchemeTask(name = path, source = server.url(path).toString(), fileName = "scheme.zip")
    private fun worker(tasks: List<RimeSchemeTask>, id: UUID = UUID.randomUUID()): RimeSchemeUpdateWorker {
        val request = RimeSchemeRequest(WanxiangTarget("rime", user.canonicalPath), tasks)
        val directory = RimeSchemeUpdates.directory(context, id).apply { mkdirs() }
        File(directory, "request.json").writeText(Json.encodeToString(request))
        return TestListenableWorkerBuilder.from(context, RimeSchemeUpdateWorker::class.java).setId(id)
            .setInputData(workDataOf(RimeSchemeUpdateWorker.TARGET to Json.encodeToString(request.target)))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    RimeSchemeUpdateWorker(appContext, workerParameters, host)
            })
            .setProgressUpdater { _, _, data ->
                progress += data
                if (data.getLong(RimeSchemeUpdateWorker.BYTES, 0) > 0) gate ?: immediate() else immediate()
            }
            .setForegroundUpdater { _, _, _ -> immediate() }.build()
    }

    @Test fun allTasksPrepareBeforeOneDeploymentAndCompletedJobsDoNotDeployTwice() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(zip("repo/ice.schema.yaml", "new"))))
        server.enqueue(MockResponse().setBody("lua module"))
        val tasks = listOf(recipe(), recipe("/lua").copy(unpack = false, fileName = "filter.lua", destinations = listOf("lua")))
        val id = UUID.randomUUID()
        assertTrue(worker(tasks, id).doWork() is ListenableWorker.Result.Success)
        assertEquals(1, installs)
        assertEquals("new", File(user, "ice.schema.yaml").readText())
        assertEquals("lua module", File(user, "lua/filter.lua").readText())
        assertEquals("personal", File(user, "default.custom.yaml").readText())
        assertEquals(2, verifies)
        assertTrue(worker(tasks, id).doWork() is ListenableWorker.Result.Success)
        assertEquals(1, installs)
        assertEquals(2, server.requestCount)
    }

    @Test fun aFailedSecondTaskDoesNotInstallTheFirstAndRetryReusesItsDownload() = runBlocking {
        val id = UUID.randomUUID()
        val tasks = listOf(recipe(), recipe("/second"))
        server.enqueue(MockResponse().setBody(Buffer().write(zip("repo/ice.schema.yaml", "new"))))
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(worker(tasks, id).doWork() is ListenableWorker.Result.Retry)
        assertEquals(0, installs)
        assertEquals("old", File(user, "ice.schema.yaml").readText())
        assertEquals("waiting", progress.last().getString(RimeSchemeUpdateWorker.PHASE))
        server.enqueue(MockResponse().setBody(Buffer().write(zip("repo/lua/filter.lua", "new"))))
        assertTrue(worker(tasks, id).doWork() is ListenableWorker.Result.Success)
        assertEquals(3, server.requestCount)
        assertEquals(1, installs)
    }

    @Test fun downloadProgressFinishesBeforeExtractionAndTheNextFileStartsAtZero() = runBlocking {
        val pending = SettableFuture.create<Void>()
        gate = pending
        server.enqueue(MockResponse().setBody(Buffer().write(zip("repo/ice.schema.yaml", "new"))))
        val running = async(Dispatchers.IO) { worker(listOf(recipe())).doWork() }
        try {
            withTimeout(5000) { while (progress.none { it.getLong(RimeSchemeUpdateWorker.BYTES, 0) > 0 }) delay(10) }
            assertFalse(progress.any { it.getString(RimeSchemeUpdateWorker.PHASE) == "extract" })
            assertEquals(0, installs)
            pending.set(null)
            assertTrue(withTimeout(5000) { running.await() } is ListenableWorker.Result.Success)
        } finally { pending.set(null); running.cancelAndJoin() }
        progress.filter { it.getString(RimeSchemeUpdateWorker.PHASE) !in setOf("download", "local") }.forEach {
            assertEquals(0L, it.getLong(RimeSchemeUpdateWorker.BYTES, -1))
            assertEquals(0L, it.getLong(RimeSchemeUpdateWorker.TOTAL, -1))
        }
    }

    @Test fun aMissingTaskExhaustsIts404BudgetWithoutInstallingOrRetryingTheBatch() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(zip("repo/ice.schema.yaml", "new"))))
        repeat(4) { server.enqueue(MockResponse().setResponseCode(404)) }
        val id = UUID.randomUUID()
        val result = worker(listOf(recipe(), recipe("/missing")), id).doWork()
        assertTrue(result.toString(), result is ListenableWorker.Result.Failure)
        assertEquals(5, server.requestCount)
        assertEquals(0, installs)
        assertEquals("old", File(user, "ice.schema.yaml").readText())
        assertFalse(File(RimeSchemeUpdates.directory(context, id), "downloads").exists())
        assertFalse(File(RimeSchemeUpdates.directory(context, id), "payload").exists())
    }

    @Test fun localFileCopyDoesNotRequireNetworkAndHonorsExclusions() = runBlocking {
        val local = File(context.cacheDir, "module.lua").apply { writeText("local") }
        val task = recipe().copy(source = local.toURI().toString(), unpack = false, fileName = "module.lua", destinations = listOf("lua"))
        val result = worker(listOf(task)).doWork()
        assertTrue(result.toString(), result is ListenableWorker.Result.Success)
        assertEquals("local", File(user, "lua/module.lua").readText())
        assertEquals(0, server.requestCount)
        val onlyExcluded = task.copy(excludes = ".*")
        assertTrue(worker(listOf(onlyExcluded)).doWork() is ListenableWorker.Result.Success)
        assertEquals(1, installs)
    }

    @Test fun targetChangesAndDeploymentFailuresKeepTheOldResources() = runBlocking {
        changeTarget = true
        server.enqueue(MockResponse().setBody(Buffer().write(zip("repo/ice.schema.yaml", "new"))))
        assertTrue(worker(listOf(recipe())).doWork() is ListenableWorker.Result.Failure)
        assertEquals(0, installs)
        assertEquals("old", File(user, "ice.schema.yaml").readText())
        changeTarget = false; failDeploy = true
        server.enqueue(MockResponse().setBody(Buffer().write(zip("repo/ice.schema.yaml", "new"))))
        assertTrue(worker(listOf(recipe())).doWork() is ListenableWorker.Result.Failure)
        assertEquals("old", File(user, "ice.schema.yaml").readText())
    }

    @Test fun aRunningWanxiangUpdateBlocksCustomSubmissionWithoutChangingTheSavedTasks() = runBlocking {
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork("wanxiang-update", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RimeSchemeUpdateWorker>().setInitialDelay(1, TimeUnit.DAYS).build()).result.get()
        val tasks = listOf(recipe())
        RimeSchemeUpdates.save(context, tasks)
        try { RimeSchemeUpdates.enqueue(context, RimeSchemeRequest(WanxiangTarget("rime", user.canonicalPath), tasks)); fail("Concurrent update was accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(tasks, RimeSchemeUpdates.tasks(context))
        assertTrue(manager.getWorkInfosForUniqueWork(RimeSchemeUpdates.WORK).get().isEmpty())
    }
}
