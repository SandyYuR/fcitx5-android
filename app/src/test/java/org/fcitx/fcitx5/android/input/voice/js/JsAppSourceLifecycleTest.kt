/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.os.Looper
import android.view.View
import android.view.inputmethod.BaseInputConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
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

    @Test fun revokingAuthorizationInvalidatesTheMatchingActiveSession() = withSession { session, editor, _ ->
        JsApps.onPluginAuthorizationChanging("js:llm-assistant")
        assertFalse(ReflectionHelpers.getField<Boolean>(session, "sourceCurrent"))
        assertTrue(runCatching { editor.call("commit", "late response") }.isFailure)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(session, "closed"))
    }

    @Test fun sourceInvalidationPreventsSettingsPersistenceFromAnOldCallback() = withSession { session, _, _ ->
        val key = "reviewLifecycleValue"
        ReflectionHelpers.setField(session, "fields", listOf(JsVoiceField("text", key, key, "", "")))
        val write = session.javaClass.getDeclaredMethod("writeSetting", String::class.java, String::class.java)
            .apply { isAccessible = true }
        try {
            write.invoke(session, key, "current")
            assertEquals("current", JsVoiceSettingsStore.value("js:llm-assistant", key))
            assertEquals("current", ReflectionHelpers.getField<Map<String, String>>(session, "settings")[key])
            JsApps.onPluginSourceChanging("js:llm-assistant")
            assertTrue(runCatching { write.invoke(session, key, "stale") }.isFailure)
            assertEquals("current", JsVoiceSettingsStore.value("js:llm-assistant", key))
        } finally { JsVoiceSettingsStore.setValue("js:llm-assistant", key, null) }
    }

    @Test fun changingSettingsDuringCredentialReadRejectsTheOldHttpDestination() = withSession { session, _, _ ->
        val key = "reviewEndpoint"
        val host = "relay.example"
        ReflectionHelpers.setField(session, "fields", listOf(JsVoiceField("text", key, key, "", "")))
        ReflectionHelpers.setField(session, "settings", mapOf(key to "https://$host/v1"))
        val write = session.javaClass.getDeclaredMethod("writeSetting", String::class.java, String::class.java)
            .apply { isAccessible = true }
        val checkUrl = session.javaClass.getDeclaredMethod("checkHttpUrl", String::class.java, String::class.java)
            .apply { isAccessible = true }
        val previousApprovals = JsVoiceHostPermissions.approvedHosts("js:llm-assistant")
        JsVoiceHostPermissions.approve("js:llm-assistant", previousApprovals + host)
        var sends = 0
        val client = OkHttpClient.Builder().addInterceptor { sends++; error("An obsolete endpoint must not be sent") }.build()
        try {
            val http = JsAppHttp(
                checkUrl = { checkUrl.invoke(session, it, host) as okhttp3.HttpUrl },
                secret = { write.invoke(session, key, "https://new.example/v1"); "fixture-secret" },
                client = client,
            )
            val result = runBlocking { runCatching { http.request(buildJsonObject {
                put("url", "https://$host/v1"); put("credential", "fixtureKey")
            }) } }
            assertTrue(result.isFailure)
            assertEquals(0, sends)
        } finally {
            JsVoiceSettingsStore.setValue("js:llm-assistant", key, null)
            if (previousApprovals.isEmpty()) JsVoiceHostPermissions.revoke("js:llm-assistant")
            else JsVoiceHostPermissions.approve("js:llm-assistant", previousApprovals)
        }
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
