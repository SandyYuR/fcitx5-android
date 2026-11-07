/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestShadow
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Exercise directory refresh and WorkManager observation through the real Activity. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, FcitxDaemonTestShadow::class])
class WanxiangUpdateActivityTest {
    @Before fun prepare() {
        FcitxDaemonTestShadow.install(WanxiangSettingsDaemonDelegate())
        val context = RuntimeEnvironment.getApplication()
        WanxiangSettingsFixture.context = context
        WanxiangSettingsFixture.root = File(context.filesDir, "updater-profile-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        WanxiangSettingsFixture.failDeploy = false
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After fun closeWorkManager() {
        try {
            WorkManager.getInstance(RuntimeEnvironment.getApplication()).cancelAllWork().result.get(5, TimeUnit.SECONDS)
            WorkManagerTestInitHelper.closeWorkDatabase()
        } finally { FcitxDaemonTestShadow.reset() }
    }

    @Test fun anUnavailableDirectoryClearsThePreviousTargetAndRetryRecoversWithoutTheOldError() {
        val controller = Robolectric.buildActivity(WanxiangUpdateActivity::class.java).setup().visible()
        val directory = WanxiangSettingsFixture.root
        val parked = File(directory.parentFile, "${directory.name}-unavailable")
        try {
            val activity = controller.get()
            val form = form(activity.findViewById(android.R.id.content))!!
            await { form.update.isEnabled }
            assertEquals("rime\n${directory.canonicalPath}", form.target.text.toString())
            assertEquals(View.GONE, form.retryDirectory.visibility)

            assertTrue(directory.renameTo(parked))
            controller.pause().stop().start().resume().visible()
            assertFalse(form.update.isEnabled)
            await { form.retryDirectory.isShown }
            assertEquals(activity.getString(R.string.wanxiang_loading_directory), form.target.text.toString())
            assertEquals(activity.getString(R.string.wanxiang_failed), form.status.heading.text.toString())
            assertTrue(form.status.detail.text.toString().contains("Cannot access the current Rime directory"))

            // A failed retry must not reactivate the target cached before onStart.
            assertTrue(form.retryDirectory.performClick())
            assertFalse(form.update.isEnabled)
            await { form.retryDirectory.isShown }
            assertFalse(form.update.isEnabled)
            assertEquals(activity.getString(R.string.wanxiang_loading_directory), form.target.text.toString())

            assertTrue(parked.renameTo(directory))
            assertTrue(form.retryDirectory.performClick())
            await { form.update.isEnabled }
            assertEquals(View.GONE, form.retryDirectory.visibility)
            assertEquals("rime\n${directory.canonicalPath}", form.target.text.toString())
            assertEquals(activity.getString(R.string.wanxiang_ready), form.status.heading.text.toString())
            assertEquals(activity.getString(R.string.wanxiang_current_target, "rime"), form.status.detail.text.toString())
        } finally {
            controller.get().finish()
            controller.pause().stop().destroy()
            if (parked.exists() && !directory.exists()) check(parked.renameTo(directory))
        }
    }

    @Test fun queuedWorkWithoutProgressDoesNotBorrowTheCurrentlySelectedDirectory() {
        val controller = Robolectric.buildActivity(WanxiangUpdateActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val form = form(activity.findViewById(android.R.id.content))!!
            await { form.update.isEnabled }
            val currentPath = WanxiangSettingsFixture.root.canonicalPath
            assertTrue(form.target.text.toString().contains(currentPath))

            // The test scheduler never releases this delay, so the real download worker cannot run.
            val queuedTarget = WanxiangTarget("another-profile", File(activity.filesDir, "another-profile").canonicalPath)
            val request = OneTimeWorkRequestBuilder<WanxiangUpdateWorker>()
                .setInitialDelay(1, TimeUnit.DAYS)
                .setInputData(workDataOf(WanxiangUpdateWorker.TARGET to Json.encodeToString(queuedTarget)))
                .build()
            val manager = WorkManager.getInstance(activity)
            manager.enqueueUniqueWork(WanxiangUpdates.WORK, ExistingWorkPolicy.KEEP, request).result.get(5, TimeUnit.SECONDS)
            await { !form.update.isEnabled && form.cancel.isShown }
            val info = requireNotNull(manager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS))
            assertEquals(WorkInfo.State.ENQUEUED, info.state)
            assertEquals(Data.EMPTY, info.progress)
            assertEquals(activity.getString(R.string.wanxiang_waiting_directory), form.target.text.toString())
            assertFalse(form.target.text.toString().contains(currentPath))
            assertEquals(activity.getString(R.string.wanxiang_waiting), form.status.heading.text.toString())
            assertEquals(View.GONE, form.retryDirectory.visibility)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun runningDownloadShowsBytesAndPerFileProgressIncludingZeroAndOneHundred() = withReadyActivity { activity, form ->
        val firstTitle = activity.getString(R.string.wanxiang_downloading, "方案", 1, 2)
        renderWork(activity, WorkInfo.State.RUNNING, downloadData(firstTitle, bytes = 1024, total = 4096))
        assertProgress(form, 25)
        assertEquals(firstTitle, form.status.heading.text.toString())
        assertTrue(form.status.detail.text.contains("25%"))
        assertTrue(form.status.detail.text.contains("scheme.zip"))
        assertTrue(form.status.detail.text.contains(Formatter.formatFileSize(activity, 1024)))
        assertTrue(form.status.detail.text.contains(Formatter.formatFileSize(activity, 4096)))
        assertTrue(form.status.detail.text.contains(activity.getString(R.string.wanxiang_speed, Formatter.formatFileSize(activity, 128))))
        assertFalse(form.update.isEnabled)
        assertTrue(form.cancel.isEnabled)

        renderWork(activity, WorkInfo.State.RUNNING, downloadData(firstTitle, bytes = 4096, total = 4096))
        assertProgress(form, 100)
        assertEquals(firstTitle, form.status.heading.text.toString())
        assertTrue(form.status.detail.text.contains("100%"))
        assertFalse(form.update.isEnabled)

        val nextTitle = activity.getString(R.string.wanxiang_downloading, "词库", 2, 2)
        renderWork(activity, WorkInfo.State.RUNNING,
            downloadData(nextTitle, bytes = 0, total = 8192, name = "dictionary.zip"))
        assertProgress(form, 0)
        assertEquals(nextTitle, form.status.heading.text.toString())
        assertTrue(form.status.detail.text.contains("0%"))
        assertTrue(form.status.detail.text.contains("dictionary.zip"))
        assertFalse(form.status.detail.text.contains("scheme.zip"))
        assertFalse(form.status.detail.text.contains("100%"))
        assertTrue(form.status.detail.text.contains(Formatter.formatFileSize(activity, 0)))
        assertTrue(form.status.detail.text.contains(Formatter.formatFileSize(activity, 8192)))
    }

    @Test fun unknownOrInconsistentDownloadLengthsRemainIndeterminateAfterKnownProgress() = withReadyActivity { activity, form ->
        val title = activity.getString(R.string.wanxiang_downloading, "方案", 1, 1)
        for ((bytes, total) in listOf(0L to 0L, 1024L to -1L, -1L to 4096L, 8192L to 4096L)) {
            renderWork(activity, WorkInfo.State.RUNNING, downloadData(title, bytes = 1024, total = 4096))
            assertProgress(form, 25)
            renderWork(activity, WorkInfo.State.RUNNING, downloadData(title, bytes = bytes, total = total))
            assertProgress(form, null)
            assertFalse(form.status.detail.text.contains("25%"))
            assertFalse(form.status.detail.text.contains("100%"))
            if (total <= 0) assertTrue(form.status.detail.text.contains(activity.getString(R.string.wanxiang_size_unknown)))
        }
    }

    @Test fun queuedRetriesAndLaterPhasesCannotReuseTheDownloadPercentage() = withReadyActivity { activity, form ->
        val title = activity.getString(R.string.wanxiang_downloading, "方案", 1, 1)
        val oldProgress = downloadData(title, bytes = 1024, total = 4096)
        renderWork(activity, WorkInfo.State.RUNNING, oldProgress)
        assertProgress(form, 25)
        renderWork(activity, WorkInfo.State.ENQUEUED, oldProgress)
        assertProgress(form, null)
        assertEquals(activity.getString(R.string.wanxiang_waiting), form.status.heading.text.toString())
        assertFalse(form.status.detail.text.contains("25%"))
        assertFalse(form.status.detail.text.contains("scheme.zip"))
        assertFalse(form.status.detail.text.contains(Formatter.formatFileSize(activity, 1024)))

        for ((phase, titleId) in listOf("extract" to R.string.wanxiang_extracting, "install" to R.string.wanxiang_installing)) {
            renderWork(activity, WorkInfo.State.RUNNING, oldProgress)
            assertProgress(form, 25)
            val phaseTitle = activity.getString(titleId)
            val progress = Data.Builder().putAll(oldProgress)
                .putString(WanxiangUpdateWorker.PHASE, phase)
                .putString(WanxiangUpdateWorker.TITLE, phaseTitle).build()
            renderWork(activity, WorkInfo.State.RUNNING, progress)
            assertProgress(form, null)
            assertEquals(phaseTitle, form.status.heading.text.toString())
            assertFalse(form.status.detail.text.contains("25%"))
            assertEquals(phase != "install", form.cancel.isEnabled)
        }
    }

    private fun withReadyActivity(block: (WanxiangUpdateActivity, WanxiangUpdateForm) -> Unit) {
        val controller = Robolectric.buildActivity(WanxiangUpdateActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val form = form(activity.findViewById(android.R.id.content))!!
            await { form.update.isEnabled }
            assertTrue(form.isAttachedToWindow)
            block(activity, form)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    private fun downloadData(title: String, bytes: Long, total: Long, name: String = "scheme.zip") = workDataOf(
        WanxiangUpdateWorker.PHASE to "download",
        WanxiangUpdateWorker.TITLE to title,
        WanxiangUpdateWorker.NAME to name,
        WanxiangUpdateWorker.BYTES to bytes,
        WanxiangUpdateWorker.TOTAL to total,
        WanxiangUpdateWorker.SPEED to 128L,
        WanxiangUpdateWorker.TARGET to Json.encodeToString(WanxiangTarget("rime", WanxiangSettingsFixture.root.canonicalPath))
    )

    private fun renderWork(activity: WanxiangUpdateActivity, state: WorkInfo.State, progress: Data) {
        val info = WorkInfo(UUID.randomUUID(), state, emptySet(), Data.EMPTY, progress)
        // Drive the real Activity renderer without starting the network worker or sleeping on the UI thread.
        WanxiangUpdateActivity::class.java.getDeclaredField("work").apply { isAccessible = true }.set(activity, info)
        WanxiangUpdateActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
    }

    private fun assertProgress(form: WanxiangUpdateForm, expected: Int?) {
        form.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        form.layout(0, 0, form.measuredWidth, form.measuredHeight)
        val indicator = progressIndicator(form.status)!!
        assertTrue(indicator.isAttachedToWindow)
        assertEquals(View.VISIBLE, indicator.visibility)
        if (expected == null) assertTrue(indicator.isIndeterminate)
        else {
            assertFalse(indicator.isIndeterminate)
            assertEquals(100, indicator.max)
            assertEquals(expected.toInt(), indicator.progress)
        }
    }

    private fun progressIndicator(view: View): LinearProgressIndicator? = when (view) {
        is LinearProgressIndicator -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { progressIndicator(view.getChildAt(it)) }
        else -> null
    }

    private fun form(view: View): WanxiangUpdateForm? = when (view) {
        is WanxiangUpdateForm -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { form(view.getChildAt(it)) }
        else -> null
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "Updater Activity did not reach the expected state" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
