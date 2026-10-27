package org.fcitx.fcitx5.android.ui.main.update

import android.app.Application
import android.content.Context
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class HostsDownloadsLifecycleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val ready = CompletableDeferred<UUID>()
    private val finish = CompletableDeferred<Unit>()
    private val asset = ReleaseAsset("fixture.bin", "https://fixture.invalid/asset", 7, null)
    private class ControlledWorker(context: Context, params: WorkerParameters,
                                   val ready: CompletableDeferred<UUID>, val finish: CompletableDeferred<Unit>) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            ready.complete(id); finish.await()
            return Result.success(workDataOf(HostsDownloadWorker.URI to "content://media/external/downloads/1", HostsDownloadWorker.BYTES to 7L))
        }
    }
    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, parameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == HostsDownloadWorker::class.java.name) ControlledWorker(appContext, parameters, ready, finish) else null
            }).build())
    }
    @After fun close() {
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
    }
    @Test fun replacingTheUiObserverDoesNotCancelThePersistentWorker() = runBlocking {
        val id = HostsDownloads.enqueue(context, asset, asset.browserDownloadUrl).await()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
        assertEquals(id, withTimeout(10000) { ready.await() })
        val firstUi = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = firstUi.async { HostsDownloads.observe(context, asset).first { it?.state == WorkInfo.State.RUNNING } }
        assertEquals(id, withTimeout(10000) { first.await() }!!.id)
        firstUi.cancel()
        val reopened = withTimeout(10000) { HostsDownloads.observe(context, asset).first { it?.state == WorkInfo.State.RUNNING } }
        assertEquals(id, reopened!!.id)
        finish.complete(Unit)
        val completed = withTimeout(10000) { HostsDownloads.observe(context, asset).first { it?.state == WorkInfo.State.SUCCEEDED } }!!
        assertEquals(id, completed.id)
        assertEquals(AssetStatus.FINISHED, HostsDownloadUiState.apply(AssetUiState(asset), completed).status)
    }
    @Test fun duplicateEnqueueKeepsOneActiveWorkRecord() = runBlocking {
        val first = HostsDownloads.enqueue(context, asset, asset.browserDownloadUrl)
        val second = HostsDownloads.enqueue(context, asset, asset.browserDownloadUrl)
        assertEquals(first.await(), second.await())
    }
    @Test fun databaseCommandsDispatchOffTheCallerAndKeepSubmissionOrder() = runBlocking {
        val caller = Thread.currentThread()
        val actual = java.util.concurrent.atomic.AtomicReference<Thread>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val order = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val first = HostsDownloads.submit {
            actual.set(Thread.currentThread()); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)); order.add(1)
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertNotSame(caller, actual.get())
            val second = HostsDownloads.submit { order.add(2) }
            assertFalse(second.isCompleted)
            release.countDown()
            withTimeout(5000) { first.await(); second.await() }
            assertEquals(listOf(1, 2), order.toList())
        } finally { release.countDown() }
    }
    @Test fun cancelCannotOvertakeEnqueueAndANewAttemptGetsANewIdentity() = runBlocking {
        val pending = HostsDownloads.enqueue(context, asset, asset.browserDownloadUrl)
        val cancel = HostsDownloads.cancel(context, asset)
        val id = pending.await(); withTimeout(10000) { cancel.join() }
        assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context).getWorkInfoById(id).get()!!.state)
        val next = HostsDownloads.enqueue(context, asset, asset.browserDownloadUrl).await()
        assertNotEquals(id, next)
    }
}
