/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.app.Activity
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsAppShortcutIntegrationTest {
    private inner class Harness(inputType: Int = InputType.TYPE_CLASS_TEXT) : AutoCloseable {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val engineJobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val connection = BaseInputConnection(View(appContext), true)

        init {
            service.fcitx = object : FcitxConnection {
                override val lifecycleScope get() = engineJobs
                override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T =
                    error("No native runtime in this fixture")
                override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T =
                    error("No native runtime in this fixture")
                override fun runIfReady(block: suspend FcitxAPI.() -> Unit) = Unit
            }
            val editor = EditorInfo().apply {
                packageName = "fixture.app"
                fieldId = 1
                this.inputType = inputType
            }
            ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
            ReflectionHelpers.setField(service, "mInputEditorInfo", editor)
            ReflectionHelpers.setField(service, "decorView", host.get().window.decorView)
            service.editorSession.start(
                EditorSessionTracker.Editor(editor.packageName, editor.fieldId, editor.inputType, editor.imeOptions),
                false,
            )
        }

        override fun close() {
            service.jsAppDismiss?.invoke()
            shadowOf(Looper.getMainLooper()).idle()
            ReflectionHelpers.setField(JsApps, "activeSession", null)
            ReflectionHelpers.getField<Channel<Job>>(service, "jobs").cancel()
            engineJobs.cancel()
            host.pause().stop().destroy()
        }
    }

    @Test fun exactShortcutOpensOnlyItsBoundAppAndSecondPressClosesIt() {
        Harness().use { harness ->
            val spec = JsVoiceProviders.specOf("js:youdao-free-translate")!!
            val app = spec.apps.single { it.id == "youdao_translate" }
            JsAppPermissions.grant(spec.id, app.id, app.permissions)
            try {
                JsApps.toggleExact(harness.service, spec.id, app.id)
                val session = activeSession()
                assertNotNull(session)
                assertSame(spec, ReflectionHelpers.getField(session, "spec"))
                assertEquals(app.id, ReflectionHelpers.getField<JsPluginApp>(session, "app").id)

                JsApps.toggleExact(harness.service, spec.id, app.id)
                assertNull(activeSession())
                assertNull(harness.service.jsAppInputConnection)
            } finally {
                JsAppPermissions.revoke(spec.id)
            }
        }
    }

    @Test fun missingOrPasswordBoundTargetsNeverFallBackToAnotherApp() {
        Harness().use { harness ->
            JsApps.toggleExact(harness.service, "js:missing", "llm_assistant")
            assertNull(activeSession())
        }
        Harness(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).use { harness ->
            val spec = JsVoiceProviders.specOf("js:youdao-free-translate")!!
            val app = spec.apps.single()
            JsAppPermissions.grant(spec.id, app.id, app.permissions)
            try {
                JsApps.toggleExact(harness.service, spec.id, app.id)
                assertNull(activeSession())
            } finally {
                JsAppPermissions.revoke(spec.id)
            }
        }
    }

    @Test fun pressingADifferentBoundAppSwitchesToThatExactCurrentCatalogEntry() {
        Harness().use { harness ->
            val assistantSpec = JsVoiceProviders.specOf("js:llm-assistant")!!
            val assistant = assistantSpec.apps.single { it.id == "llm_assistant" }
            val translationSpec = JsVoiceProviders.specOf("js:youdao-free-translate")!!
            val translation = translationSpec.apps.single { it.id == "youdao_translate" }
            JsAppPermissions.grant(assistantSpec.id, assistant.id, assistant.permissions)
            JsAppPermissions.grant(translationSpec.id, translation.id, translation.permissions)
            try {
                JsApps.toggleExact(harness.service, assistantSpec.id, assistant.id)
                assertEquals(assistant.id, ReflectionHelpers.getField<JsPluginApp>(activeSession(), "app").id)

                JsApps.toggleExact(harness.service, translationSpec.id, translation.id)
                val current = activeSession()
                assertNotNull(current)
                assertSame(translationSpec, ReflectionHelpers.getField(current, "spec"))
                assertEquals(translation.id, ReflectionHelpers.getField<JsPluginApp>(current, "app").id)
            } finally {
                JsAppPermissions.revoke(assistantSpec.id)
                JsAppPermissions.revoke(translationSpec.id)
            }
        }
    }

    private fun activeSession(): Any? = ReflectionHelpers.getField(JsApps, "activeSession")
}
