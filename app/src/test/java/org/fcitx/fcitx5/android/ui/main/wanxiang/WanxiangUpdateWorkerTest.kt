/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.work.*
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.fcitx.fcitx5.android.data.WanxiangProtection
import org.fcitx.fcitx5.android.data.WanxiangUpdateTransaction
import org.fcitx.fcitx5.android.ui.main.update.DownloadFiles
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
class WanxiangUpdateWorkerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var user: File
    private lateinit var server: MockWebServer
    private var verifies = 0
    private var installs = 0
    private var closes = 0
    private var changedDirectory = false
    private var protectAtInstall = false
    private val progress = ConcurrentLinkedQueue<Data>()
    private val foregrounds = ConcurrentLinkedQueue<ForegroundInfo>()
    private var progressGate: ((Data) -> ListenableFuture<Void>)? = null
    private var beforeResolve: ((WanxiangPackage) -> Unit)? = null

    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        user = File(context.filesDir, "user").apply { mkdirs() }
        File(user, "wanxiang.schema.yaml").writeText("old")
        server = MockWebServer().apply { start() }
    }
    @After fun cleanup() { server.shutdown(); WorkManagerTestInitHelper.closeWorkDatabase() }

    private val host = object : WanxiangUpdateHost {
        override fun client() = OkHttpClient()
        override fun url(asset: WanxiangPackage) = server.url("/${asset.name}").toString()
        override fun resolvedUrl(client: OkHttpClient, asset: WanxiangPackage): String {
            beforeResolve?.invoke(asset)
            return url(asset)
        }
        override suspend fun verify(target: WanxiangTarget) {
            verifies++
            check(!changedDirectory || verifies == 1) { "directory changed" }
        }
        override suspend fun install(target: WanxiangTarget, id: UUID) {
            installs++
            val payload = File(WanxiangUpdates.directory(context, id), "payload")
            if (protectAtInstall) {
                WanxiangProtection(".*").filter(payload, user)
                if (payload.walkTopDown().none { it.isFile }) return
            }
            WanxiangUpdateTransaction.install(user, payload, {}, {})
        }
        override fun close() { closes++ }
    }

    private fun immediate() = SettableFuture.create<Void>().apply { set(null) }

    private fun worker(selection: WanxiangSelection, id: UUID = UUID.randomUUID()): WanxiangUpdateWorker =
        TestListenableWorkerBuilder.from(context, WanxiangUpdateWorker::class.java).setId(id)
            .setInputData(workDataOf(WanxiangUpdateWorker.SELECTION to Json.encodeToString(selection),
                WanxiangUpdateWorker.TARGET to Json.encodeToString(WanxiangTarget("rime", user.canonicalPath))))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, parameters: WorkerParameters) =
                    WanxiangUpdateWorker(appContext, parameters, host)
            })
            .setProgressUpdater { _, _, data -> progress += data; progressGate?.invoke(data) ?: immediate() }
            .setForegroundUpdater { _, _, info -> foregrounds += info; immediate() }
            .build()

    private fun enqueuedId(): UUID {
        val request = OneTimeWorkRequestBuilder<WanxiangUpdateWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueue(request).result.get()
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(request.id).get()!!.state)
        return request.id
    }

    private fun modelDownload(id: UUID) = DownloadFiles(File(WanxiangUpdates.directory(context, id), "downloads"),
        UUID.nameUUIDFromBytes(WanxiangPackage.Kind.MODEL.name.toByteArray(Charsets.UTF_8)))

    private fun modelBytes() = "Rime::Grammar/1.0\u0000".toByteArray() + ByteArray(32 * 1024) { 65 }

    private suspend fun awaitPartial(files: DownloadFiles) = withTimeout(5000) {
        while (files.part.length() < 1024) delay(10)
    }

    private fun zip(vararg entries: Pair<String, String>) = Buffer().write(ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray())

    @Test fun eachPhaseReplacesOldProgressAndWaitsForTheFinalDownloadWrite() = runBlocking {
        val gate = SettableFuture.create<Void>()
        progressGate = { data ->
            if (data.getString(WanxiangUpdateWorker.PHASE) == "download" && data.getLong(WanxiangUpdateWorker.BYTES, 0) > 0)
                gate else immediate()
        }
        beforeResolve = { asset ->
            val current = progress.last()
            assertEquals("download", current.getString(WanxiangUpdateWorker.PHASE))
            assertEquals(asset.name, current.getString(WanxiangUpdateWorker.NAME))
            assertEquals(0L, current.getLong(WanxiangUpdateWorker.BYTES, -1))
            assertEquals(0L, current.getLong(WanxiangUpdateWorker.TOTAL, -1))
        }
        server.enqueue(MockResponse().setBody(zip("wanxiang.schema.yaml" to "new")))
        val running = async(Dispatchers.IO) { worker(WanxiangSelection(scope = WanxiangSelection.Scope.SCHEME)).doWork() }
        try {
            withTimeout(5000) {
                while (progress.none { it.getLong(WanxiangUpdateWorker.BYTES, 0) > 0 }) delay(10)
            }
            assertNull(withTimeoutOrNull(200) { running.await() })
            assertFalse(progress.any { it.getString(WanxiangUpdateWorker.PHASE) == "extract" })
            assertEquals(0, installs)
            gate.set(null)
            assertTrue(withTimeout(5000) { running.await() } is ListenableWorker.Result.Success)
        } finally {
            gate.set(null)
            running.cancelAndJoin()
        }
        assertEquals(listOf("preparing", "download", "extract", "install"),
            progress.mapNotNull { it.getString(WanxiangUpdateWorker.PHASE) }.distinct())
        progress.filter { it.getString(WanxiangUpdateWorker.PHASE) != "download" }.forEach {
            assertEquals(0L, it.getLong(WanxiangUpdateWorker.BYTES, -1))
            assertEquals(0L, it.getLong(WanxiangUpdateWorker.TOTAL, -1))
            assertEquals(0L, it.getLong(WanxiangUpdateWorker.SPEED, -1))
        }
        val stages = foregrounds.map { it.notification }
        assertEquals(4, stages.size)
        assertTrue(stages.all { it.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) })
        assertEquals(context.getString(org.fcitx.fcitx5.android.R.string.wanxiang_extracting),
            stages[2].extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(stages[2].extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
    }

    @Test fun retryClearsProgressAndReusesAnAlreadyVerifiedPackage() = runBlocking {
        val id = UUID.randomUUID()
        server.enqueue(MockResponse().setBody(zip("wanxiang.schema.yaml" to "new")))
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(worker(WanxiangSelection(), id).doWork() is ListenableWorker.Result.Retry)
        val waiting = progress.last()
        assertEquals("waiting", waiting.getString(WanxiangUpdateWorker.PHASE))
        assertNull(waiting.getString(WanxiangUpdateWorker.NAME))
        assertEquals(0L, waiting.getLong(WanxiangUpdateWorker.BYTES, -1))
        assertEquals(0L, waiting.getLong(WanxiangUpdateWorker.TOTAL, -1))
        assertEquals(0L, waiting.getLong(WanxiangUpdateWorker.SPEED, -1))
        assertFalse(File(WanxiangUpdates.directory(context, id), "payload").exists())
        server.enqueue(MockResponse().setBody(zip("zi.dict.yaml" to "dictionary")))
        server.enqueue(MockResponse().setBody(Buffer().write(modelBytes())))
        assertTrue(worker(WanxiangSelection(), id).doWork() is ListenableWorker.Result.Success)
        assertEquals(4, server.requestCount)
        assertEquals(1, installs)
        assertEquals("new", File(user, "wanxiang.schema.yaml").readText())
    }

    @Test fun aSystemStopKeepsThePartialAndTheNextAttemptResumesIt() = runBlocking {
        val id = enqueuedId()
        val selection = WanxiangSelection(scope = WanxiangSelection.Scope.MODEL)
        val bytes = modelBytes()
        val files = modelDownload(id)
        server.enqueue(MockResponse().setHeader("ETag", "\"model-v1\"").setBody(Buffer().write(bytes))
            .throttleBody(1024, 100, TimeUnit.MILLISECONDS))
        val running = launch(Dispatchers.IO) { worker(selection, id).doWork() }
        try { awaitPartial(files) } finally { running.cancelAndJoin() }
        val offset = files.part.length()
        assertTrue(offset >= 1024 && offset < bytes.size)
        assertFalse(files.read().verified)
        assertFalse(File(WanxiangUpdates.directory(context, id), "payload").exists())
        assertEquals(WorkInfo.State.ENQUEUED, WorkManager.getInstance(context).getWorkInfoById(id).get()!!.state)
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"model-v1\"")
            .setHeader("Content-Range", "bytes $offset-${bytes.size - 1}/${bytes.size}")
            .setBody(Buffer().write(bytes, offset.toInt(), bytes.size - offset.toInt())))
        assertTrue(worker(selection, id).doWork() is ListenableWorker.Result.Success)
        assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(bytes, File(user, WanxiangSelection.MODEL_NAME).readBytes())
    }

    @Test fun anExplicitWorkManagerCancellationRemovesThePartial() = runBlocking {
        val id = enqueuedId()
        val files = modelDownload(id)
        server.enqueue(MockResponse().setHeader("ETag", "\"model-v1\"").setBody(Buffer().write(modelBytes()))
            .throttleBody(1024, 100, TimeUnit.MILLISECONDS))
        val running = launch(Dispatchers.IO) { worker(WanxiangSelection(scope = WanxiangSelection.Scope.MODEL), id).doWork() }
        try {
            awaitPartial(files)
            WorkManager.getInstance(context).cancelWorkById(id).result.get()
        } finally { running.cancelAndJoin() }
        assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context).getWorkInfoById(id).get()!!.state)
        assertFalse(File(WanxiangUpdates.directory(context, id), "downloads").exists())
        assertFalse(File(WanxiangUpdates.directory(context, id), "payload").exists())
        assertEquals(0, installs)
    }

    @Test fun allDownloadsMustValidateBeforeOneDeploymentCanApplyThem() = runBlocking {
        server.enqueue(MockResponse().setBody(zip("wanxiang.schema.yaml" to "new", "wanxiang.custom.yaml" to "must skip")))
        server.enqueue(MockResponse().setBody(zip("zi.dict.yaml" to "new dictionary")))
        server.enqueue(MockResponse().setBody(Buffer().write("Rime::Grammar/1.0\u0000fixture".toByteArray())))
        File(user, "wanxiang.custom.yaml").writeText("personal")
        val result = worker(WanxiangSelection()).doWork()
        assertTrue(result.toString(), result is ListenableWorker.Result.Success)
        assertEquals(1, installs)
        assertEquals(2, verifies)
        assertEquals(1, closes)
        assertEquals("new", File(user, "wanxiang.schema.yaml").readText())
        assertEquals("new dictionary", File(user, "dicts/zi.dict.yaml").readText())
        assertEquals("personal", File(user, "wanxiang.custom.yaml").readText())
        assertFalse((result as ListenableWorker.Result.Success).outputData.getBoolean(WanxiangUpdateWorker.NO_CHANGES, true))
    }

    @Test fun aBadDictionaryPreventsAnAlreadyDownloadedSchemeFromBeingInstalled() = runBlocking {
        server.enqueue(MockResponse().setBody(zip("wanxiang.schema.yaml" to "new")))
        server.enqueue(MockResponse().setBody("<html>download failed</html>"))
        val result = worker(WanxiangSelection()).doWork()
        assertFalse(result is ListenableWorker.Result.Success)
        assertEquals(0, installs)
        assertEquals("old", File(user, "wanxiang.schema.yaml").readText())
    }

    @Test fun switchingTheDirectoryDuringDownloadRejectsInstallation() = runBlocking {
        changedDirectory = true
        server.enqueue(MockResponse().setBody(zip("wanxiang.schema.yaml" to "new")))
        val result = worker(WanxiangSelection(scope = WanxiangSelection.Scope.SCHEME)).doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(0, installs)
        assertEquals("old", File(user, "wanxiang.schema.yaml").readText())
    }

    @Test fun restartingCompletedWorkDoesNotDownloadOrInstallAgain() = runBlocking {
        server.enqueue(MockResponse().setBody(zip("wanxiang.schema.yaml" to "new")))
        val selection = WanxiangSelection(scope = WanxiangSelection.Scope.SCHEME)
        val id = UUID.randomUUID()
        assertTrue(worker(selection, id).doWork() is ListenableWorker.Result.Success)
        assertTrue(worker(selection, id).doWork() is ListenableWorker.Result.Success)
        assertEquals(1, server.requestCount)
        assertEquals(1, installs)
    }

    @Test fun customizedProtectionKeepsAnExistingSchemeWhileUpdatingOtherResources() = runBlocking {
        server.enqueue(MockResponse().setBody(zip("wanxiang.schema.yaml" to "replace", "default.yaml" to "new defaults")))
        val result = worker(WanxiangSelection(scope = WanxiangSelection.Scope.SCHEME, protection = "^wanxiang\\.schema\\.yaml$")).doWork()
        assertTrue(result.toString(), result is ListenableWorker.Result.Success)
        assertEquals("old", File(user, "wanxiang.schema.yaml").readText())
        assertEquals("new defaults", File(user, "default.yaml").readText())
    }

    @Test fun protectingEveryDownloadedFileReportsNoChangesEvenAfterWorkerRestart() = runBlocking {
        server.enqueue(MockResponse().setBody(zip("wanxiang.schema.yaml" to "replace")))
        val selection = WanxiangSelection(scope = WanxiangSelection.Scope.SCHEME, protection = ".*")
        val id = UUID.randomUUID()
        repeat(2) {
            val result = worker(selection, id).doWork()
            assertTrue(result is ListenableWorker.Result.Success)
            assertTrue((result as ListenableWorker.Result.Success).outputData.getBoolean(WanxiangUpdateWorker.NO_CHANGES, false))
        }
        assertEquals(1, server.requestCount)
        assertEquals(0, installs)
        assertEquals("old", File(user, "wanxiang.schema.yaml").readText())
    }

    @Test fun protectionRecheckedByTheOwnerAlsoReportsNoChanges() = runBlocking {
        protectAtInstall = true
        server.enqueue(MockResponse().setBody(zip("wanxiang.schema.yaml" to "replace")))
        val result = worker(WanxiangSelection(scope = WanxiangSelection.Scope.SCHEME)).doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue((result as ListenableWorker.Result.Success).outputData.getBoolean(WanxiangUpdateWorker.NO_CHANGES, false))
        assertEquals("old", File(user, "wanxiang.schema.yaml").readText())
    }

    @Test fun twoExtraDictionariesDownloadAndStageAsIndependentFiles() = runBlocking {
        server.enqueue(MockResponse().setBody("---\nname: medical\nversion: '1'\n...\n女儿\tnǚ ér\t100\n"))
        server.enqueue(MockResponse().setBody("---\nname: computer\nversion: '1'\n...\n电脑\tdiàn nǎo\t100\n"))
        val result = worker(WanxiangSelection(scope = WanxiangSelection.Scope.EXTRA, scheme = WanxiangSelection.Scheme.LITE,
            extraDictionaries = listOf("medical", "computer"))).doWork()
        assertTrue(result.toString(), result is ListenableWorker.Result.Success)
        assertEquals(1, installs)
        assertTrue(File(user, "dicts/medical.lite.dict.yaml").readText().contains("nv er"))
        assertTrue(File(user, "dicts/computer.lite.dict.yaml").readText().contains("dian nao"))
        assertEquals("old", File(user, "wanxiang.schema.yaml").readText())
    }

    @Test fun proWithoutItsCharacterTableFailsWithoutInstallingAnything() = runBlocking {
        server.enqueue(MockResponse().setBody("---\nname: medical\nversion: '1'\n...\n中\tzhong\t100\n"))
        val result = worker(WanxiangSelection(scope = WanxiangSelection.Scope.EXTRA, scheme = WanxiangSelection.Scheme.PRO,
            extraDictionaries = listOf("medical"))).doWork()
        assertTrue(result.toString(), result is ListenableWorker.Result.Failure)
        assertEquals(0, installs)
        assertEquals("old", File(user, "wanxiang.schema.yaml").readText())
    }

    @Test fun oneInvalidExtraDictionaryPreventsTheOtherOneFromBeingPartiallyInstalled() = runBlocking {
        server.enqueue(MockResponse().setBody("---\nname: medical\nversion: '1'\n...\n中\tzhong\t100\n"))
        server.enqueue(MockResponse().setBody("<html>server error</html>"))
        val result = worker(WanxiangSelection(scope = WanxiangSelection.Scope.EXTRA, extraDictionaries = listOf("medical", "computer"))).doWork()
        assertTrue(result.toString(), result is ListenableWorker.Result.Failure)
        assertEquals(0, installs)
        assertFalse(File(user, "dicts/medical.dict.yaml").exists())
    }
}
