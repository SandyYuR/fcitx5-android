/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.app.Activity
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.ListPopupWindow
import android.widget.TextView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.input.EditorSessionTracker
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test fun headerShortcutSwitchesToolsWithoutChangingTheEditorOrDefault() {
        Harness().use { harness ->
            val source = JsVoiceProviders.specOf(JsAppSelection.DEFAULT_PLUGIN_ID)!!
            val destination = JsVoiceProviders.specOf("js:bilibili-translate")!!
            val app = destination.apps.single()
            JsAppPermissions.grant(source.id, source.apps.single().id, source.apps.single().permissions)
            JsAppPermissions.grant(destination.id, app.id, app.permissions)
            try {
                val preferred = JsAppSelection.preferred()
                JsApps.openExact(harness.service, source.id, source.apps.single().id)
                val previous = requireNotNull(activeSession())
                val dialog = renderPanel(previous, widthDp = 320)
                val switcher = ReflectionHelpers.getField<JsAppSwitcher>(dialog, "switcher")
                val label = harness.service.getString(R.string.js_app_switch_to, app.label)
                val button = visibleChildren(switcher).single { it.contentDescription == label }
                assertTrue(button.performClick())
                val switched = requireNotNull(activeSession())
                assertSame(destination, ReflectionHelpers.getField(switched, "spec"))
                assertSame(harness.connection, ReflectionHelpers.getField(switched, "connection"))
                assertTrue(ReflectionHelpers.getField(previous, "closed"))
                assertEquals(preferred, JsAppSelection.preferred())

                button.performClick()
                assertSame("A detached shortcut must not open a second session", switched, activeSession())
            } finally {
                JsAppPermissions.revoke(source.id)
                JsAppPermissions.revoke(destination.id)
            }
        }
    }

    @Test fun narrowLegacyPanelListsImportedToolAndOpensItByExactIdentity() {
        val id = "shortcut-imported"
        val imported = JsPluginApp("youdao_translate", "My translator", emptySet())
        val meta = JsVoicePluginMeta(id, "Imported tools", "1", emptyList(), emptyList(), emptyList(), listOf(imported))
        val script = """
            foxy.plugin.register({id:'shortcut-imported',hosts:[],features:[]});
            foxy.app.register({id:'youdao_translate',permissions:[],onOpen:function(){
              return {title:'My translator',fields:[{id:'input'}]};
            }});
        """.trimIndent()
        assertTrue(JsVoicePluginStore.save(JsVoicePluginRecord(meta, "$id.js", 1), script))
        JsVoiceProviders.reload()
        try {
            Harness().use { harness ->
                val source = JsVoiceProviders.specOf(JsAppSelection.DEFAULT_PLUGIN_ID)!!
                val sourceApp = source.apps.single()
                JsAppPermissions.grant(source.id, sourceApp.id, sourceApp.permissions)
                try {
                    JsApps.openExact(harness.service, source.id, sourceApp.id)
                    shadowOf(Looper.getMainLooper()).idle()
                    val dialog = renderPanel(requireNotNull(activeSession()), widthDp = 200, legacy = true)
                    val switcher = ReflectionHelpers.getField<JsAppSwitcher>(dialog, "switcher")
                    val menuLabel = harness.service.getString(R.string.js_app_switch_tools)
                    val overflow = visibleChildren(switcher).single { it.contentDescription == menuLabel }
                    val anchor = switcher.parent as ViewGroup
                    assertTrue("Menu anchor should be attached and measured", anchor.isAttachedToWindow && anchor.width > 0)
                    assertEquals("Assistant", (anchor.getChildAt(0) as TextView).text.toString())
                    val previous = requireNotNull(activeSession())
                    dialog.setValue("input", "Keep this draft")
                    assertTrue(overflow.performClick())
                    val initialMenu = checkNotNull(ReflectionHelpers.getField<ListPopupWindow?>(switcher, "popup"))
                    assertTrue(harness.service.onKeyUp(KeyEvent.KEYCODE_BACK,
                        KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
                    assertFalse(initialMenu.isShowing)
                    assertSame(previous, activeSession())
                    assertEquals("Keep this draft", dialog.values().text("input"))

                    assertTrue(overflow.performClick())
                    val popup = checkNotNull(ReflectionHelpers.getField<ListPopupWindow?>(switcher, "popup"))
                    assertFalse(popup.isModal)
                    val list = requireNotNull(popup.listView)
                    val position = (0 until list.adapter.count).single {
                        (list.adapter.getItem(it) as JsAppPanelEntry).target.pluginId == "js:$id"
                    }
                    assertTrue(popup.performItemClick(position))
                    val selected = requireNotNull(activeSession())
                    assertEquals("js:$id", ReflectionHelpers.getField<JsVoiceProviders.Spec>(selected, "spec").id)
                    assertEquals(imported.id, ReflectionHelpers.getField<JsPluginApp>(selected, "app").id)
                    assertSame(harness.connection, ReflectionHelpers.getField(selected, "connection"))
                } finally {
                    JsAppPermissions.revoke(source.id)
                }
            }
        } finally {
            JsVoicePluginStore.delete(id)
            JsVoiceProviders.reload()
        }
    }

    private fun renderPanel(session: Any, widthDp: Int, legacy: Boolean = false): JsAppDialog {
        val dialog = ReflectionHelpers.getField<JsAppDialog>(session, "dialog")
        val spec = if (legacy) """{"title":"Assistant","fields":[{"id":"input"}],"maxWidth":$widthDp}"""
        else """{"maxWidth":$widthDp,"layout":{"type":"column","children":[
            {"type":"header","children":[{"type":"text","text":"Assistant","weight":1},
                {"type":"button","id":"close","label":"Close","icon":"close","iconOnly":true}]},
            {"type":"input","id":"input"}]}}"""
        dialog.render(Json.parseToJsonElement(spec).jsonObject)
        val panel = dialog.findViewById<View>(android.R.id.content)
        val width = (widthDp * panel.resources.displayMetrics.density).toInt()
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
        panel.layout(0, 0, width, panel.measuredHeight)
        return dialog
    }

    private fun visibleChildren(parent: ViewGroup) =
        (0 until parent.childCount).map(parent::getChildAt).filter { it.visibility == View.VISIBLE }

    private fun activeSession(): Any? = ReflectionHelpers.getField(JsApps, "activeSession")
}
