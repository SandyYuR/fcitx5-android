/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.appcompat.view.menu.MenuBuilder
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.ui.main.settings.behavior.FontsetEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.PopupEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.MacroEditorActivity
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class StandaloneEditorDraftTest {
    private fun parcel(state: Bundle): Bundle {
        val p = Parcel.obtain()
        return try { p.writeBundle(state); p.setDataPosition(0); p.readBundle(javaClass.classLoader)!! }
        finally { p.recycle() }
    }
    private fun <T : Activity> state(controller: ActivityController<T>): Bundle {
        val bundle = Bundle()
        controller.pause().saveInstanceState(bundle).stop().destroy()
        return parcel(bundle)
    }
    private fun <T : Activity> finish(controller: ActivityController<T>) {
        controller.get().finish(); controller.pause().stop().destroy()
    }
    private fun macroIntent() = Intent().putExtra(MacroEditorActivity.EXTRA_MACRO_STEPS_JSON,
        Json.encodeToString<List<MacroStep>>(listOf(MacroStep.Text("original"))))
        .putExtra(MacroEditorActivity.EXTRA_TARGET_TOKEN, "original-slot")
    private fun steps(a: MacroEditorActivity): MutableList<MacroEditorActivity.MacroStepData> =
        ReflectionHelpers.getField(a, "steps")
    private fun macroDirty(a: MacroEditorActivity): Boolean {
        ReflectionHelpers.callInstanceMethod<Void>(a, "updateSaveButtonState")
        return ReflectionHelpers.getField(a, "hasChanges")
    }
    private fun popupEntries(a: PopupEditorActivity): MutableMap<String, MutableList<String>> =
        ReflectionHelpers.getField(a, "entries")

    @Test fun macroRestoresItsDraftAndOriginalBaselineAcrossTwoFreshActivities() {
        val first = Robolectric.buildActivity(MacroEditorActivity::class.java, macroIntent()).setup()
        steps(first.get()).single().text = "edited"
        val second = Robolectric.buildActivity(MacroEditorActivity::class.java, macroIntent()).setup(state(first))
        assertEquals("edited", steps(second.get()).single().text)
        assertTrue(macroDirty(second.get()))
        val third = Robolectric.buildActivity(MacroEditorActivity::class.java, macroIntent()).setup(state(second))
        try {
            assertEquals("edited", steps(third.get()).single().text)
            assertTrue(macroDirty(third.get()))
            steps(third.get()).single().text = "original"
            assertFalse(macroDirty(third.get()))
            assertEquals("original-slot", third.get().intent.getStringExtra(MacroEditorActivity.EXTRA_TARGET_TOKEN))
        } finally { finish(third) }
    }

    @Test fun aLargeMacroDraftOnlyParcelsItsFileName() {
        val first = Robolectric.buildActivity(MacroEditorActivity::class.java, macroIntent()).setup()
        val text = "私有草稿".repeat(70_000)
        steps(first.get()).single().text = text
        val saved = state(first)
        val p = Parcel.obtain()
        try { p.writeBundle(saved); assertTrue("The draft must not enter the Binder payload", p.dataSize() < 64 * 1024) }
        finally { p.recycle() }
        val second = Robolectric.buildActivity(MacroEditorActivity::class.java, macroIntent()).setup(saved)
        try { assertEquals(text, steps(second.get()).single().text); assertTrue(macroDirty(second.get())) }
        finally { finish(second) }
    }

    @Test fun independentMacroEditorsDoNotOverwriteEachOthersDrafts() {
        val a = Robolectric.buildActivity(MacroEditorActivity::class.java, macroIntent()).setup()
        val b = Robolectric.buildActivity(MacroEditorActivity::class.java, macroIntent()).setup()
        steps(a.get()).single().text = "a"; steps(b.get()).single().text = "b"
        val stateA = state(a); val stateB = state(b)
        assertNotEquals(stateA.getString("editor_draft_file"), stateB.getString("editor_draft_file"))
        val restoredA = Robolectric.buildActivity(MacroEditorActivity::class.java, macroIntent()).setup(stateA)
        val restoredB = Robolectric.buildActivity(MacroEditorActivity::class.java, macroIntent()).setup(stateB)
        try { assertEquals("a", steps(restoredA.get()).single().text); assertEquals("b", steps(restoredB.get()).single().text) }
        finally { finish(restoredA); finish(restoredB) }
    }

    @Test fun popupRestoresInsertedRowsAndItsOriginalComparisonSnapshot() {
        val first = Robolectric.buildActivity(PopupEditorActivity::class.java).setup()
        val before = popupEntries(first.get()).mapValues { it.value.toList() }
        popupEntries(first.get())["draft-key"] = mutableListOf("one", "two")
        val second = Robolectric.buildActivity(PopupEditorActivity::class.java).setup(state(first))
        try {
            assertEquals(listOf("one", "two"), popupEntries(second.get())["draft-key"])
            assertTrue(ReflectionHelpers.callInstanceMethod(second.get(), "hasChanges"))
            popupEntries(second.get()).clear(); popupEntries(second.get()).putAll(before.mapValues { it.value.toMutableList() })
            assertFalse(ReflectionHelpers.callInstanceMethod(second.get(), "hasChanges"))
        } finally { finish(second) }
    }

    @Test fun fontSelectionsSizesAndDirtySaveButtonSurviveAParcelRoundTrip() {
        val first = Robolectric.buildActivity(FontsetEditorActivity::class.java).setup()
        val fonts: MutableMap<String, MutableList<String>> = ReflectionHelpers.getField(first.get(), "selectedFonts")
        val sizes: MutableMap<String, Float> = ReflectionHelpers.getField(first.get(), "selectedFontSizes")
        fonts["key_main_font"] = mutableListOf("first.ttf", "second.ttf"); sizes["key_main_font"] = 33f
        val second = Robolectric.buildActivity(FontsetEditorActivity::class.java).setup(state(first))
        try {
            val restored: Map<String, List<String>> = ReflectionHelpers.getField(second.get(), "selectedFonts")
            val restoredSizes: Map<String, Float> = ReflectionHelpers.getField(second.get(), "selectedFontSizes")
            assertEquals(listOf("first.ttf", "second.ttf"), restored["key_main_font"])
            assertEquals(33f, restoredSizes["key_main_font"])
            val menu = MenuBuilder(second.get()); second.get().onCreateOptionsMenu(menu)
            assertTrue(menu.getItem(0).isEnabled)
        } finally { finish(second) }
    }

    @Test fun malformedDraftsFallBackToOriginalDataWithoutBecomingDirty() {
        val saved = Bundle().apply { putString("editor_draft_inline", "{broken") }
        val macro = Robolectric.buildActivity(MacroEditorActivity::class.java, macroIntent()).setup(parcel(saved))
        try { assertEquals("original", steps(macro.get()).single().text); assertFalse(macroDirty(macro.get())) }
        finally { finish(macro) }
    }
}
