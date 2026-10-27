package org.fcitx.fcitx5.android.input.voice

import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class VoiceFinishIntegrationTest {
    private class Provider : IVoiceInputProvider.Stub() {
        var ends = 0
        override fun isAvailable() = true
        override fun getPreferredConfig() = Bundle()
        override fun configure(params: Bundle?) {}
        override fun startSession(callback: IVoiceInputCallback?) {}
        override fun feedAudio(pcm: ByteArray?, offset: Int, len: Int, ptsMs: Long) {}
        override fun endStream() { ends++ }
        override fun cancelSession() {}
        override fun stopSession() {}
    }
    private val callback = object : IVoiceInputCallback.Stub() {
        override fun onReady() {}
        override fun onVolumeLevel(rms: Int) {}
        override fun onPartialResult(text: String?) {}
        override fun onSegmentFinal(text: String?) {}
        override fun onSessionEnded() {}
        override fun onError(code: Int, message: String?) {}
    }
    private lateinit var service: FcitxInputMethodService
    private val manager = VoiceInputProviderManager
    private val fields = listOf("activeProvider", "activeCallback", "activeEditorSession", "sessionGeneration",
        "sessionActive", "voiceSessionTerminalized", "finishing", "floatingFallbackActive",
        "activeAudioFeedQueue", "activeAudioFeedJob", "finishWatchdogJob")
    private lateinit var saved: Map<String, Any?>
    private val feed = Job()
    private val queue = Channel<Any>(2)
    private val original = Provider()

    @Before fun setUp() {
        service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        service.editorSession.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)
        saved = fields.associateWith { ReflectionHelpers.getField<Any?>(manager, it) }
        val state = mapOf("activeProvider" to original, "activeCallback" to callback,
            "activeEditorSession" to service.editorSession.capture(), "sessionGeneration" to 10,
            "sessionActive" to true, "voiceSessionTerminalized" to false, "finishing" to false,
            "floatingFallbackActive" to false, "activeAudioFeedQueue" to queue,
            "activeAudioFeedJob" to feed, "finishWatchdogJob" to null)
        state.forEach { (name, value) -> ReflectionHelpers.setField(manager, name, value) }
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<Job?>(manager, "finishWatchdogJob")?.cancel()
        service.lifecycleScope.cancel()
        feed.cancel(); queue.close()
        saved.forEach { (name, value) -> ReflectionHelpers.setField(manager, name, value) }
    }

    private fun beginFinish() {
        manager.javaClass.getDeclaredMethod("finish", android.content.Context::class.java).apply {
            isAccessible = true
        }.invoke(manager, service)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(queue.isClosedForSend)
        assertEquals(0, original.ends)
    }

    @Test fun actualDrainCompletionDoesNotEndTheNewProvider() {
        beginFinish()
        val replacement = Provider()
        ReflectionHelpers.setField(manager, "activeProvider", replacement)
        ReflectionHelpers.setField(manager, "sessionGeneration", 11)
        feed.complete()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, replacement.ends)
        assertEquals(0, original.ends)
        assertNull(ReflectionHelpers.getField<Job?>(manager, "finishWatchdogJob"))
    }

    @Test fun editorChangeDuringDrainPreventsEndStreamAndWatchdog() {
        beginFinish()
        service.editorSession.start(EditorSessionTracker.Editor("app", 2, 1, 0), false)
        feed.complete()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, original.ends)
        assertNull(ReflectionHelpers.getField<Job?>(manager, "finishWatchdogJob"))
    }

    @Test fun unchangedSessionFinishesAfterItsAudioDrains() {
        beginFinish()
        feed.complete()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, original.ends)
        assertNotNull(ReflectionHelpers.getField<Job?>(manager, "finishWatchdogJob"))
    }
}
