/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.os.Bundle
import android.os.Looper
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fcitx.fcitx5.android.input.voice.VoiceInputProviderRegistry
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsVoiceProviderInvalidationTest {
    private class Provider : IVoiceInputProvider.Stub() {
        var stops = 0
        override fun isAvailable() = true
        override fun getPreferredConfig() = Bundle()
        override fun configure(params: Bundle?) = Unit
        override fun startSession(callback: IVoiceInputCallback?) = Unit
        override fun feedAudio(pcm: ByteArray?, offset: Int, len: Int, ptsMs: Long) = Unit
        override fun endStream() = Unit
        override fun cancelSession() = Unit
        override fun stopSession() { stops++ }
    }

    private val manager = VoiceInputProviderManager
    private val providerId = "js:llm-assistant"
    private val rawId = "llm-assistant"
    private val otherId = "js:invalidation-other"
    private val managerFields = listOf(
        "activeProvider", "activeProviderId", "activeCallback", "activeEditorSession",
        "sessionActive", "voiceSessionTerminalized", "finishing", "floatingFallbackActive",
        "activeAudioFeedQueue", "activeAudioFeedJob", "activeCapture", "finishWatchdogJob",
        "activeConnection", "keepAliveConnection", "floatingFallbackComponent",
        "warmConnectionReleaseGeneration",
    )
    private lateinit var savedManager: Map<String, Any?>
    private var savedProviderEntry: VoiceInputProviderRegistry.Entry? = null
    private var savedOtherEntry: VoiceInputProviderRegistry.Entry? = null

    @Before
    fun setUp() {
        savedManager = managerFields.associateWith { ReflectionHelpers.getField<Any?>(manager, it) }
        savedProviderEntry = VoiceInputProviderRegistry.get(providerId)
        savedOtherEntry = VoiceInputProviderRegistry.get(otherId)
        VoiceInputProviderRegistry.unregister(providerId)
        VoiceInputProviderRegistry.unregister(otherId)
        setWarmProvider(null, null)
    }

    @After
    fun tearDown() {
        JsVoicePluginStore.delete(rawId)
        shadowOf(Looper.getMainLooper()).idle()
        savedManager.forEach { (name, value) -> ReflectionHelpers.setField(manager, name, value) }
        VoiceInputProviderRegistry.unregister(providerId)
        VoiceInputProviderRegistry.unregister(otherId)
        savedProviderEntry?.let(VoiceInputProviderRegistry::register)
        savedOtherEntry?.let(VoiceInputProviderRegistry::register)
    }

    @Test
    fun invalidationStopsOnlyTheCapturedProviderAndCannotStopANewerSameIdInstance() {
        val target = Provider()
        val other = Provider()
        register(providerId, target)
        register(otherId, other)
        setWarmProvider(otherId, other)

        manager.invalidateProvider(providerId)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, target.stops)
        assertEquals(0, other.stops)
        assertSame(other, ReflectionHelpers.getField(manager, "activeProvider"))

        setWarmProvider(providerId, target)
        manager.invalidateProvider(providerId)
        assertEquals("teardown must be posted to the main executor", 0, target.stops)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, target.stops)

        val old = Provider()
        register(providerId, old)
        setWarmProvider(providerId, old)
        manager.invalidateProvider(providerId)
        val replacement = Provider()
        register(providerId, replacement)
        setWarmProvider(providerId, replacement)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, old.stops)
        assertEquals(0, replacement.stops)
        assertSame(replacement, ReflectionHelpers.getField(manager, "activeProvider"))
    }

    @Test
    fun saveRestoreAndDeleteEachInvalidateTheOldRegisteredProvider() {
        val meta = requireNotNull(JsVoicePluginMeta.parse("""
            {"id":"llm-assistant","name":"Custom AI","version":"0.1","hosts":[],"features":[],
             "apps":[{"id":"llm_assistant","permissions":[]}]}
        """.trimIndent()))
        val record = JsVoicePluginRecord(meta, "custom.js", 1, overridesBuiltIn = true)

        assertInvalidated {
            assertTrue(JsVoicePluginStore.save(record, "// custom assistant"))
        }
        assertInvalidated {
            assertTrue(JsVoicePluginStore.restoreBuiltIn(rawId))
        }
        assertInvalidated {
            assertTrue(JsVoicePluginStore.delete(rawId))
        }
    }

    private fun assertInvalidated(operation: () -> Unit) {
        val provider = Provider()
        register(providerId, provider)
        setWarmProvider(providerId, provider)
        operation()
        assertEquals(0, provider.stops)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, provider.stops)
    }

    private fun register(id: String, provider: Provider) {
        VoiceInputProviderRegistry.register(
            VoiceInputProviderRegistry.Entry(id, "fixture", appContext.packageName, provider),
        )
    }

    private fun setWarmProvider(id: String?, provider: Provider?) {
        val state = mapOf<String, Any?>(
            "activeProvider" to provider,
            "activeProviderId" to id,
            "activeCallback" to null,
            "activeEditorSession" to null,
            "sessionActive" to false,
            "voiceSessionTerminalized" to false,
            "finishing" to false,
            "floatingFallbackActive" to false,
            "activeAudioFeedQueue" to null,
            "activeAudioFeedJob" to null,
            "activeCapture" to null,
            "finishWatchdogJob" to null,
            "activeConnection" to null,
            "keepAliveConnection" to null,
            "floatingFallbackComponent" to null,
        )
        state.forEach { (name, value) -> ReflectionHelpers.setField(manager, name, value) }
    }
}
