package org.fcitx.fcitx5.android.ui.main.update

import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class HostsDownloadWorkerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var media: DownloadMediaFixture
    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        media = DownloadMediaFixture().also { it.attachInfo(context, ProviderInfo().apply { authority = "media" }) }
        ShadowContentResolver.registerProviderInternal("media", media)
    }
    @After fun closeDatabase() { WorkManagerTestInitHelper.closeWorkDatabase() }
    private fun worker(asset: ReleaseAsset, id: UUID = UUID.randomUUID(), attempt: Int = 0): HostsDownloadWorker =
        TestListenableWorkerBuilder.from(context, HostsDownloadWorker::class.java).setId(id).setRunAttemptCount(attempt)
            .setInputData(workDataOf(HostsDownloadWorker.ASSET to Json.encodeToString(asset),
                HostsDownloadWorker.URL to asset.browserDownloadUrl, HostsDownloadWorker.NAME to asset.name))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, parameters: WorkerParameters) =
                    HostsDownloadWorker(appContext, parameters, OkHttpClient())
            }).build()

    @Test fun actualWorkerVerifiesThenPublishesAndReturnsADurableOutputUri() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("payload"))
            val result = worker(ReleaseAsset("fixture.bin", server.url("/f").toString(), 7, null)).doWork()
            assertTrue(result.toString(), result is ListenableWorker.Result.Success)
            val uri = (result as ListenableWorker.Result.Success).outputData.getString(HostsDownloadWorker.URI)
            assertNotNull(uri); assertEquals(1, media.rows.size)
            assertEquals("payload", media.rows.values.single().file.readText())
            assertEquals(0, media.rows.values.single().values.getAsInteger("is_pending"))
        }
    }
    @Test fun restartingWorkerAfterPublicationDoesNotDownloadOrPublishTwice() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("payload"))
            val asset = ReleaseAsset("fixture.bin", server.url("/f").toString(), 7, null)
            val id = UUID.randomUUID(); media.crashAfterPublish = true
            assertEquals(ListenableWorker.Result.retry(), worker(asset, id).doWork())
            assertTrue(worker(asset, id, 1).doWork() is ListenableWorker.Result.Success)
            assertEquals(1, server.requestCount); assertEquals(1, media.insertions)
        }
    }
    @Test fun badChecksumProducesNoPublicOrPendingFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("payload"))
            val id = UUID.randomUUID()
            val result = worker(ReleaseAsset("fixture.bin", server.url("/f").toString(), 7, "0".repeat(64)), id).doWork()
            assertTrue(result.toString(), result is ListenableWorker.Result.Failure)
            assertTrue(media.rows.isEmpty()); assertFalse(HostsDownloadWorker.files(context, id).part.exists())
        }
    }
    @Test fun explicitCancellationCleansThePartialAfterTheNetworkWriterStops() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(100_000)))
                .setHeader("ETag", "\"v1\"").throttleBody(1024, 100, TimeUnit.MILLISECONDS))
            val id = UUID.randomUUID(); val files = HostsDownloadWorker.files(context, id)
            val task = async(Dispatchers.IO) { worker(ReleaseAsset("fixture.bin", server.url("/f").toString(), 100_000, null), id).doWork() }
            withTimeout(10000) { while (files.part.length() < 1024) delay(10) }
            files.markCancelled()
            withTimeout(3000) { task.cancelAndJoin() }
            assertFalse(files.part.exists()); assertTrue(media.rows.isEmpty())
        }
    }
}
