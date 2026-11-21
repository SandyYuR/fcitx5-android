/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.descendants
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.action.ButtonAction
import org.fcitx.fcitx5.android.input.action.SettingsInputStatsAction
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeUpdateActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.FlowLayout
import org.fcitx.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutProfileManagerActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.MacroEditorDialogFragment
import org.fcitx.fcitx5.android.ui.main.settings.voice.JsVoicePluginsActivity
import org.fcitx.fcitx5.android.ui.main.stats.InputStatsActivity
import org.fcitx.fcitx5.android.utils.parcelable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], qualifiers = "w360dp-h640dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class MacroAppActionTest {
    @Test fun selectingInputStatisticsReturnsTheExecutableActionId() {
        val intent = Intent().putExtra(MacroEditorDialogFragment.EXTRA_MACRO_STEPS,
            MacroEditorDialogFragment.toStepsExtra(listOf(MacroStep.AppAction("theme"))))
        val controller = launchMacroEditorTestHost(intent)
        try {
            val activity = controller.get()
            val fragment = activity.macroEditorFragment()
            var result: Bundle? = null
            activity.supportFragmentManager.setFragmentResultListener(
                MacroEditorDialogFragment.RESULT_REQUEST_KEY, activity
            ) { _, value -> result = value }
            val window = fragment.dialog!!.window!!
            val list = (window.decorView as ViewGroup).descendants.filterIsInstance<RecyclerView>().single()
            val root = list.parent as ViewGroup
            root.measure(View.MeasureSpec.makeMeasureSpec(window.attributes.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(window.attributes.height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            root.descendants.filterIsInstance<FlowLayout>().single().getChildAt(0).performClick()

            val picker = ShadowDialog.getLatestDialog() as AlertDialog
            val label = activity.getString(R.string.stats_title)
            val rows = picker.listView.adapter
            val row = (0 until rows.count).single { rows.getItem(it).toString() == label }
            picker.listView.performItemClick(rows.getView(row, null, picker.listView), row, rows.getItemId(row))
            shadowOf(Looper.getMainLooper()).idle()

            val confirm = root.descendants.filterIsInstance<MaterialButton>()
                .single { it.text == activity.getString(R.string.macro_editor_confirm) }
            assertTrue(confirm.isEnabled)
            confirm.performClick()
            assertEquals(Activity.RESULT_OK, result!!.getInt(MacroEditorDialogFragment.RESULT_CODE))
            val resultIntent = result!!.parcelable<Intent>(MacroEditorDialogFragment.RESULT_DATA)!!
            val steps = Json.decodeFromString<List<MacroStep>>(
                resultIntent.getStringExtra(MacroEditorDialogFragment.EXTRA_MACRO_RESULT_JSON)!!)
            assertEquals(listOf(MacroStep.AppAction("settings_input_stats")), steps)
            assertSame(SettingsInputStatsAction, ButtonAction.fromId((steps.single() as MacroStep.AppAction).id))
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun newlyOfferedActionsResolveThroughTheKeyboardExecutor() {
        val pickerIds = MacroEditorDialogFragment.APP_ACTION_IDS.toSet()
        val addedIds = listOf("settings_input_stats", "voice_input", "ai_assistant", "split_keyboard",
            "theme_toggle", "random_theme", "text_keyboard_layout_profiles", "settings_js_plugins",
            "settings_scheme_updates", "settings_data_backup")
        addedIds.forEach { id ->
            assertTrue("The app action picker must offer $id", id in pickerIds)
            assertNotNull("The keyboard executor must recognize $id", ButtonAction.fromId(id))
        }
    }

    @Test fun pageActionsCanLaunchTheirDestinationsFromTheInputMethodService() {
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val engine = object : FcitxConnection {
            override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = error("Navigation must not read the engine")
            override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = error("Navigation must not wait for the engine")
            override fun runIfReady(block: suspend FcitxAPI.() -> Unit) = error("Navigation must not mutate the engine")
            override val lifecycleScope: CoroutineScope get() = error("Navigation must not start an engine task")
        }
        val destinations = mapOf(
            "settings_input_stats" to InputStatsActivity::class.java,
            "text_keyboard_layout_profiles" to TextKeyboardLayoutProfileManagerActivity::class.java,
            "settings_js_plugins" to JsVoicePluginsActivity::class.java,
            "settings_scheme_updates" to RimeSchemeUpdateActivity::class.java,
            "settings_data_backup" to MainActivity::class.java)
        destinations.forEach { (id, activityClass) ->
            val action = requireNotNull(ButtonAction.fromId(id))
            action.execute(service, service, engine, InputWindowManager())
            val started = requireNotNull(shadowOf(service).nextStartedActivity)
            assertEquals("Destination for $id", activityClass.name, started.component!!.className)
            assertEquals("Service navigation requires a new task for $id", Intent.FLAG_ACTIVITY_NEW_TASK,
                started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            if (id == "settings_data_backup") {
                assertEquals(Intent.ACTION_RUN, started.action)
                assertEquals(SettingsRoute.DataBackup, started.parcelable<SettingsRoute>(MainActivity.EXTRA_SETTINGS_ROUTE))
            }
        }
    }
}
