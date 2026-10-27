/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.content.BroadcastReceiver
import android.content.Intent
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc
import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class FloatingVoiceReceiverTest {
    private val manager = VoiceInputProviderManager
    private lateinit var service: FcitxInputMethodService
    private lateinit var receiver: BroadcastReceiver
    private lateinit var gate: FloatingVoiceResultGate
    private val fields = listOf("floatingFallbackActive", "floatingFallbackComponent", "activeEditorSession",
        "voiceSessionTerminalized", "finishing", "sessionActive", "floatingCommitListener",
        "sessionEndedCallback", "voiceFinishedCallback")
    private lateinit var saved: Map<String, Any?>
    private var received = 0

    @Before fun setUp() {
        service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        service.editorSession.start(EditorSessionTracker.Editor("app", 1, 1, 0), false)
        receiver = ReflectionHelpers.getField(service, "voiceInputCommitReceiver")
        gate = ReflectionHelpers.getField(manager, "floatingResultGate")
        gate.clear()
        saved = fields.associateWith { ReflectionHelpers.getField<Any?>(manager, it) }
        ReflectionHelpers.setField(manager, "floatingFallbackActive", true)
        ReflectionHelpers.setField(manager, "floatingFallbackComponent", null)
        ReflectionHelpers.setField(manager, "sessionEndedCallback", null)
        ReflectionHelpers.setField(manager, "voiceFinishedCallback", null)
        ReflectionHelpers.setField(manager, "activeEditorSession", service.editorSession.capture())
        manager.floatingCommitListener = { received++ }
    }
    @After fun restore() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        gate.clear(); saved.forEach { (name, value) -> ReflectionHelpers.setField(manager, name, value) }
    }
    private fun send(token: String?) = receiver.onReceive(service,
        Intent(BuildConfig.APPLICATION_ID + VoiceInputIpc.COMMIT_ACTION_SUFFIX)
            .putExtra(VoiceInputIpc.EXTRA_COMMIT_TEXT, "fixture")
            .putExtra(VoiceInputIpc.EXTRA_FLOATING_SESSION_TOKEN, token))

    @Test fun noTokenOrWrongTokenDoesNotReachTheRealCommitReceiverBody() {
        val token = gate.begin(service.editorSession.capture()!!)
        send(null); send("wrong"); assertEquals(0, received)
        send(token); send(token); assertEquals(1, received)
    }

    @Test fun lateBroadcastsCannotFinishTheNewEditorsVoiceUi() {
        val token = gate.begin(service.editorSession.capture()!!)
        service.editorSession.start(EditorSessionTracker.Editor("app", 2, 1, 0), false)
        send(token); assertEquals(0, received)
        assertTrue(ReflectionHelpers.getField(manager, "floatingFallbackActive"))
    }

    @Test fun anAidlSessionDoesNotAcceptFloatingBroadcasts() {
        val token = gate.begin(service.editorSession.capture()!!)
        ReflectionHelpers.setField(manager, "floatingFallbackActive", false)
        send(token); assertEquals(0, received)
    }

    @Test fun aMalformedTextExtraDoesNotCrashOrConsumeTheActiveRequest() {
        val token = gate.begin(service.editorSession.capture()!!)
        receiver.onReceive(service, Intent(BuildConfig.APPLICATION_ID + VoiceInputIpc.COMMIT_ACTION_SUFFIX)
            .putExtra(VoiceInputIpc.EXTRA_FLOATING_SESSION_TOKEN, token)
            .putExtra(VoiceInputIpc.EXTRA_COMMIT_TEXT, android.os.Bundle()))
        assertEquals(0, received)
        send(token); assertEquals(1, received)
    }

    private fun installFloatingService(supportsToken: Boolean): android.content.ComponentName {
        val app = android.content.pm.ApplicationInfo().apply { packageName = "fixture.voice"; enabled = true }
        val info = android.content.pm.ServiceInfo().apply {
            name = "fixture.voice.FloatingService"; packageName = app.packageName
            applicationInfo = app; enabled = true; exported = true
            metaData = android.os.Bundle().apply { putBoolean(VoiceInputIpc.META_FLOATING_SESSION_TOKEN, supportsToken) }
        }
        org.robolectric.Shadows.shadowOf(service.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = app.packageName; applicationInfo = app; services = arrayOf(info)
        })
        return android.content.ComponentName(app.packageName, "fixture.voice.Provider")
    }

    private fun startFloating(provider: android.content.ComponentName): android.content.ComponentName? =
        ReflectionHelpers.callInstanceMethod(manager, "startFloatingFallback",
            ReflectionHelpers.ClassParameter.from(FcitxInputMethodService::class.java, service),
            ReflectionHelpers.ClassParameter.from(android.content.ComponentName::class.java, provider))

    @Test fun anOldPluginWithoutTheCapabilityDoesNotStartAnUnverifiableFallback() {
        assertNull(startFloating(installFloatingService(false)))
        assertNull(gate.currentToken)
    }

    @Test fun aCapablePluginReceivesTheNewOpaqueTokenAndHostPackage() {
        assertNotNull(startFloating(installFloatingService(true)))
        val app: android.app.Application = org.robolectric.RuntimeEnvironment.getApplication()
        val started = org.robolectric.Shadows.shadowOf(app).nextStartedService!!
        assertEquals(gate.currentToken, started.getStringExtra(VoiceInputIpc.EXTRA_FLOATING_SESSION_TOKEN))
        assertFalse(requireNotNull(gate.currentToken).isBlank())
        assertEquals(service.packageName, started.getStringExtra(VoiceInputIpc.EXTRA_HOST_PACKAGE))
    }
}
