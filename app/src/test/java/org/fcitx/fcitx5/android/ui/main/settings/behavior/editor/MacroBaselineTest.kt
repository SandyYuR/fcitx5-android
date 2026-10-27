/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.content.Intent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyRef
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.MacroEditorActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class MacroBaselineTest {
    private fun page(intent: Intent, block: (MacroEditorActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MacroEditorActivity::class.java, intent).setup()
        try { block(controller.get()) }
        finally { controller.pause().stop().destroy() }
    }
    private fun json(steps: List<MacroStep>) = Intent().putExtra(
        MacroEditorActivity.EXTRA_MACRO_STEPS_JSON, Json.encodeToString(steps))
    private fun maps(steps: List<MacroStep>) = Intent().putExtra(
        MacroEditorActivity.EXTRA_MACRO_STEPS, MacroEditorActivity.toStepsExtra(steps))
    private fun dirty(activity: MacroEditorActivity): Boolean {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "updateSaveButtonState")
        return ReflectionHelpers.getField(activity, "hasChanges")
    }
    private fun steps(activity: MacroEditorActivity): MutableList<MacroEditorActivity.MacroStepData> =
        ReflectionHelpers.getField(activity, "steps")
    private fun mixed() = listOf(
        MacroStep.Down(listOf(KeyRef.Fcitx("Ctrl_L"))),
        MacroStep.Tap(listOf(KeyRef.Fcitx("C"), KeyRef.Android(66))),
        MacroStep.Up(listOf(KeyRef.Fcitx("Ctrl_L"))),
        MacroStep.Shortcut(listOf(KeyRef.Fcitx("Alt_L")), KeyRef.Fcitx("Tab")),
        MacroStep.Text("example"), MacroStep.Edit("paste"), MacroStep.AppAction("theme"),
        MacroStep.LayerSwitch(KeyAction.LayerSwitchMode.TO, "English"))

    @Test fun aJsonTapDoesNotStartDirty() = page(json(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("A")))))) {
        assertFalse(dirty(it))
    }
    @Test fun everyJsonStepTypeSharesTheNormalizedBaseline() = page(json(mixed())) {
        assertEquals(8, steps(it).size)
        assertFalse(dirty(it))
    }
    @Test fun legacyMapLayerModesAreNormalizedBeforeComparison() =
        page(maps(listOf(MacroStep.LayerSwitch(KeyAction.LayerSwitchMode.TO, "English")))) {
            assertFalse(dirty(it))
        }
    @Test fun aJsonTextEditBecomesDirtyAndRestoringItBecomesClean() =
        page(json(listOf(MacroStep.Text("original")))) {
            steps(it).single().text = "edited"
            assertTrue(dirty(it))
            steps(it).single().text = "original"
            assertFalse(dirty(it))
        }
    @Test fun theBaselineDoesNotShareMutableKeysWithTheEditor() =
        page(json(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("A")))))) {
            steps(it).single().keys.single().code = "B"
            assertTrue(dirty(it))
            steps(it).single().keys.single().code = "A"
            assertFalse(dirty(it))
        }
    @Test fun theBaselineDoesNotShareTheMutableKeyList() =
        page(json(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("A")))))) {
            steps(it).single().keys += MacroEditorActivity.KeyData("fcitx", "B")
            assertTrue(dirty(it))
            steps(it).single().keys.removeAt(1)
            assertFalse(dirty(it))
        }
    @Test fun aNewEmptyMacroStillStartsClean() = page(Intent()) {
        assertFalse(dirty(it))
        steps(it).single().keys += MacroEditorActivity.KeyData("fcitx", "A")
        assertTrue(dirty(it))
    }
    @Test fun openingAMacroDoesNotLogItsContents() {
        val secret = "private-macro-text-fixture"
        page(maps(listOf(MacroStep.Text(secret)))) {
            assertFalse(dirty(it))
            assertTrue(ShadowLog.getLogsForTag("MacroEditor").none { log -> log.msg.contains(secret) })
        }
    }
}
