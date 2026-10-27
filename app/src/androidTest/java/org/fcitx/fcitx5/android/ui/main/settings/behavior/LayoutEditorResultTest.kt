/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.os.SystemClock
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.view.descendants
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.input.keyboard.AuxBarConfig
import org.fcitx.fcitx5.android.input.keyboard.AuxBarPosition
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.KeyEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.KeyEditorForm
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.KeyEditorPendingResults
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeColorEditorActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real child return + framework recreation; force the loading window instead of racing disk IO. */
class LayoutEditorResultTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
    private fun set(instance: Any, name: String, value: Any?) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }
    private fun call(instance: Any, name: String, vararg args: Any?) {
        instance.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(instance, *args)
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 180_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            onMain { done = predicate() }
            if (done) return
            SystemClock.sleep(200)
        }
        fail(message)
    }

    @Test fun keyReturnBeforeLoadSurvivesRecreationAndEditsItsOriginalFileAndEntry() = exercise(aux = false)
    @Test fun auxiliaryReturnBeforeLoadSurvivesRecreationAndEditsItsOriginalFileAndEntry() = exercise(aux = true)

    private fun exercise(aux: Boolean) {
        val profile = "result-test-${UUID.randomUUID()}"
        val otherProfile = "$profile-other"
        val prefs = AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile
        val previous = prefs.getValue()
        val original = requireNotNull(UserConfigFiles.textKeyboardLayoutJson(profile))
        val other = requireNotNull(UserConfigFiles.textKeyboardLayoutJson(otherProfile))
        val fixture = LayoutDataManager(context).apply {
            entries["rime"] = mutableListOf(mutableListOf(mutableMapOf("type" to "AlphabetKey", "main" to "a", "alt" to "A")))
            entries["other"] = mutableListOf(mutableListOf(mutableMapOf("type" to "AlphabetKey", "main" to "z", "alt" to "Z")))
            setLayoutAuxBarConfig("rime", AuxBarConfig(AuxBarPosition.Top, 10f))
            setLayoutAuxBarKeys("rime", listOf(mapOf("type" to "AlphabetKey", "main" to "x", "alt" to "X")))
        }.exportCurrentJsonCompact()
        original.parentFile!!.mkdirs()
        original.writeText(fixture)
        other.writeText(fixture)
        var parent: TextKeyboardLayoutEditorActivity? = null
        var child: KeyEditorActivity? = null
        try {
            onMain { prefs.setValue(profile) }
            // No EXTRA_INITIAL_PROFILE: a restored file must also beat a changed active preference.
            parent = instrumentation.startActivitySync(Intent(context, TextKeyboardLayoutEditorActivity::class.java)
                .putExtra(TextKeyboardLayoutEditorActivity.EXTRA_INITIAL_LAYOUT, "rime")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextKeyboardLayoutEditorActivity
            val first = requireNotNull(parent)
            await("Initial layout did not load") { field(first, "stateLoaded") == true }
            onMain {
                val manager = field(first, "dataManager") as LayoutDataManager
                assertEquals("a", manager.entries.getValue("rime")[0][0]["main"])
                assertEquals("Auxiliary fixture must be enabled and loaded", "x",
                    manager.getLayoutAuxBarKeys("rime").firstOrNull()?.get("main"))
                manager.entries.getValue("other")[0][0]["main"] = "unsaved"
                call(first, "updateSaveButtonState")
                if (aux) call(first, "openAuxBarKeyEditor", 0) else call(first, "openKeyEditor", 0, 0)
                // The return target must come from launch time, not the saved spinner selection.
                set(first, "currentLayout", "other")
                set(first, "previewSubModeLabel", null)
            }
            await("Key editor did not open") {
                child = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<KeyEditorActivity>().firstOrNull()
                child != null && first in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED)
            }
            onMain {
                // Deterministically emulate the parent's asynchronous load still being in flight.
                set(first, "stateLoaded", false)
                val originalText = if (aux) "x" else "a"
                val fields = (child!!.window.decorView as ViewGroup).descendants.filterIsInstance<EditText>().toList()
                val main = fields.firstOrNull { it.text.toString() == originalText }
                assertNotNull("Expected $originalText; fields=${fields.map { it.text.toString() }}", main)
                main!!.setText(if (aux) "y" else "b")
                call(child!!, "save") // Production child save echoes its original request token.
            }
            await("Parent did not receive the child result") {
                first in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED) &&
                    (field(field(first, "pendingEditorResults")!!, "pending") as Map<*, *>).size == 1
            }
            onMain {
                val manager = field(first, "dataManager") as LayoutDataManager
                assertEquals("a", manager.entries.getValue("rime")[0][0]["main"])
                assertEquals("x", manager.getLayoutAuxBarKeys("rime")[0]["main"])
                prefs.setValue(otherProfile)
                first.recreate() // Save while stateLoaded=false, then load file + unsaved draft.
            }
            await("Restored parent did not finish loading") {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<TextKeyboardLayoutEditorActivity>().firstOrNull { it !== first }
                    ?.let { parent = it }
                parent !== first && field(parent!!, "stateLoaded") == true
            }
            onMain {
                val restored = parent!!
                val manager = field(restored, "dataManager") as LayoutDataManager
                assertEquals(profile, field(restored, "currentLayoutProfile"))
                assertEquals("other", field(restored, "currentLayout"))
                assertEquals("unsaved", manager.entries.getValue("other")[0][0]["main"])
                assertEquals(if (aux) "a" else "b", manager.entries.getValue("rime")[0][0]["main"])
                assertEquals(if (aux) "y" else "x", manager.getLayoutAuxBarKeys("rime")[0]["main"])
                assertTrue((field(field(restored, "pendingEditorResults")!!, "pending") as Map<*, *>).isEmpty())
                call(restored, "restoreHistory", false)
                assertEquals("a", manager.entries.getValue("rime")[0][0]["main"])
                assertEquals("x", manager.getLayoutAuxBarKeys("rime")[0]["main"])
                assertEquals("unsaved", manager.entries.getValue("other")[0][0]["main"])
            }
            if (!aux) {
                val beforeSecondRestore = requireNotNull(parent)
                onMain {
                    val manager = field(beforeSecondRestore, "dataManager") as LayoutDataManager
                    val options = Intent().putExtra(KeyEditorForm.EXTRA_KEY_DATA,
                        KeyEditorForm.toSerializableMap(manager.entries.getValue("rime")[0][0]))
                    val formState = Bundle().also(KeyEditorForm(beforeSecondRestore, options)::saveState)
                    set(beforeSecondRestore, "pendingInlineKeyState", Bundle().apply {
                        putInt("inline_key_row", 0); putInt("inline_key_index", 0)
                        putString("inline_key_layout", "rime"); putBundle("inline_key_form", formState)
                    })
                    set(beforeSecondRestore, "pendingEditorSelection", Bundle().apply {
                        putString("current_layout", "rime")
                        putBoolean("preview_submode_is_default_item", true)
                        putBoolean("editing_split", false)
                    })
                    val host = field(beforeSecondRestore, "inlineKeyResults")!!
                    (field(host, "pending") as KeyEditorPendingResults).offer(KeyEditorPendingResults.Kind.COLOR,
                        ThemeColorEditorActivity.EditorResult("textColor", -16711936))
                    set(beforeSecondRestore, "currentLayout", null)
                    set(beforeSecondRestore, "stateLoaded", false)
                    beforeSecondRestore.recreate()
                }
                await("Second restore lost the inline editor") {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<TextKeyboardLayoutEditorActivity>().firstOrNull { it !== beforeSecondRestore }
                        ?.let { parent = it }
                    parent !== beforeSecondRestore && field(parent!!, "stateLoaded") == true
                }
                onMain {
                    val restored = parent!!
                    val manager = field(restored, "dataManager") as LayoutDataManager
                    assertEquals(profile, field(restored, "currentLayoutProfile"))
                    assertEquals("rime", field(restored, "currentLayout"))
                    assertNotNull(field(restored, "selectedKey"))
                    assertEquals(-16711936, manager.entries.getValue("rime")[0][0]["textColor"])
                    assertEquals("unsaved", manager.entries.getValue("other")[0][0]["main"])
                    val host = field(restored, "inlineKeyResults")!!
                    assertTrue((field(host, "pending") as KeyEditorPendingResults).takeAll().isEmpty())
                }
            }
            assertEquals("No implicit disk save", fixture, original.readText())
            assertEquals("Other profile untouched", fixture, other.readText())
        } finally {
            onMain {
                child?.takeIf { !it.isDestroyed }?.finish()
                parent?.takeIf { !it.isDestroyed }?.finish()
                prefs.setValue(previous)
            }
            original.delete()
            other.delete()
        }
    }
}
