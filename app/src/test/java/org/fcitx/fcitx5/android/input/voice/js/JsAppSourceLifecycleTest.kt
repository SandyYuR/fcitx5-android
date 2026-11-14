/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.os.Looper
import android.view.View
import android.view.inputmethod.BaseInputConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsAppSourceLifecycleTest {
    @Test fun sourceChangeRejectsOldCallbacksImmediatelyAndRestoresPreviewOnMainThread() = withSession { session, editor, connection ->
        editor.call("setComposition", "temporary")
        assertEquals("before temporary after", connection.editable.toString())

        runBlocking(Dispatchers.IO) { JsApps.onPluginSourceChanging("js:llm-assistant") }
        assertFalse(ReflectionHelpers.getField<Boolean>(session, "sourceCurrent"))
        assertTrue(runCatching { editor.call("commit", "late response") }.isFailure)
        assertFalse(ReflectionHelpers.getField<CoroutineScope>(session, "scope").isActive)

        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(session, "closed"))
        assertEquals("before selected after", connection.editable.toString())
        assertNull(ReflectionHelpers.getField<Any?>(JsApps, "activeSession"))
    }

    @Test fun changingAnotherPluginDoesNotInvalidateTheActiveSourceUntilCatalogReload() = withSession { session, editor, _ ->
        runBlocking(Dispatchers.IO) { JsApps.onPluginSourceChanging("js:another-plugin") }
        assertTrue(ReflectionHelpers.getField<Boolean>(session, "sourceCurrent"))
        assertEquals("selected", editor.call("getSelection", ""))

        JsApps.onPluginsChanged()
        assertFalse(ReflectionHelpers.getField<Boolean>(session, "sourceCurrent"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(session, "closed"))
    }

    private fun withSession(check: (Any, JsAppEditor, BaseInputConnection) -> Unit) {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val engineJobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        service.fcitx = object : FcitxConnection {
            override val lifecycleScope get() = engineJobs
            override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = error("No native runtime in this fixture")
            override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = error("No native runtime in this fixture")
            override fun runIfReady(block: suspend FcitxAPI.() -> Unit) = Unit
        }
        val connection = BaseInputConnection(View(appContext), true).apply {
            commitText("before selected after", 1)
            setSelection(7, 15)
        }
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
        service.editorSession.start(EditorSessionTracker.Editor("fixture.app", 1, 1, 0), false)
        val spec = requireNotNull(JsVoiceProviders.specOf("js:llm-assistant"))
        val app = spec.apps.single { it.id == "llm_assistant" }
        JsAppPermissions.grant(spec.id, app.id, app.permissions)
        val sessionClass = Class.forName("org.fcitx.fcitx5.android.input.voice.js.JsApps\$Session")
        val session = sessionClass.getDeclaredConstructor(
            FcitxInputMethodService::class.java, JsVoiceProviders.Spec::class.java, JsPluginApp::class.java,
        ).apply { isAccessible = true }.newInstance(service, spec, app)
        ReflectionHelpers.setField(JsApps, "activeSession", session)
        val editor = ReflectionHelpers.getField<JsAppEditor>(session, "editor")
        try {
            check(session, editor, connection)
        } finally {
            JsApps.onPluginSourceChanging(spec.id)
            shadowOf(Looper.getMainLooper()).idle()
            JsAppPermissions.revoke(spec.id)
            ReflectionHelpers.getField<Channel<Job>>(service, "jobs").cancel()
            engineJobs.cancel()
        }
    }
}
