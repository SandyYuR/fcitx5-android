/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.content.Intent
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.descendants
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.keyboard.KeyRef
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.ui.main.settings.behavior.FlowLayout
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.MacroEditorActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class MacroKeyDialogTest {
    private class Page(val activity: MacroEditorActivity) {
        val step: MacroEditorActivity.MacroStepData =
            ReflectionHelpers.getField<MutableList<MacroEditorActivity.MacroStepData>>(activity, "steps").single()
        private val adapter = ReflectionHelpers.getField<MacroEditorActivity.StepsAdapter>(activity, "stepsAdapter")
        private val parent = FrameLayout(activity)
        private val holder = adapter.onCreateViewHolder(parent, 0).also {
            parent.addView(it.itemView)
            activity.setContentView(parent)
            adapter.onBindViewHolder(it, 0)
        }
        fun open(index: Int): AlertDialog {
            ReflectionHelpers.getField<FlowLayout>(holder, "keysFlow").getChildAt(index).performClick()
            return ShadowDialog.getLatestDialog() as AlertDialog
        }
        fun select(dialog: AlertDialog, code: String, asAndroid: Boolean = false) {
            val label = if (asAndroid) MacroEditorActivity.ANDROID_KEY_NAMES.getValue(code)
                else MacroEditorActivity.getFcitxKeyDisplayName(code)
            dialog.findViewById<ViewGroup>(android.R.id.content)!!.descendants.filterIsInstance<TextView>()
                .single { it.text.toString() == label }.performClick()
        }
        fun click(dialog: AlertDialog, button: Int) {
            dialog.getButton(button).performClick()
            // AlertDialog posts its positive/negative callbacks to the main handler.
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun page(keys: List<KeyRef> = listOf(KeyRef.Fcitx("A")), block: (Page) -> Unit) {
        val intent = Intent().putExtra(MacroEditorActivity.EXTRA_MACRO_STEPS,
            MacroEditorActivity.toStepsExtra(listOf(MacroStep.Tap(keys))))
        val controller = Robolectric.buildActivity(MacroEditorActivity::class.java, intent).setup()
        try { block(Page(controller.get())) }
        finally {
            ShadowDialog.getLatestDialog()?.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun negativeButtonDiscardsSelectionWithoutDirtyingTheMacro() = page { page ->
        val original = page.step.keys.single()
        val dialog = page.open(0)
        page.select(dialog, "B")
        assertEquals("A", original.code)
        page.click(dialog, AlertDialog.BUTTON_NEGATIVE)
        assertSame(original, page.step.keys.single())
        assertEquals("A", original.code)
        assertFalse(ReflectionHelpers.getField<Boolean>(page.activity, "hasChanges"))
    }

    @Test fun backOrOutsideCancellationDiscardsSelection() = page { page ->
        val dialog = page.open(0)
        page.select(dialog, "B")
        dialog.cancel()
        assertEquals("A", page.step.keys.single().code)
        assertFalse(ReflectionHelpers.getField<Boolean>(page.activity, "hasChanges"))
    }

    @Test fun confirmWritesOnlyTheFinalSelectionAndMarksTheMacroDirty() = page { page ->
        val original = page.step.keys.single()
        val dialog = page.open(0)
        page.select(dialog, "B")
        page.select(dialog, "C")
        assertEquals("A", original.code)
        page.click(dialog, AlertDialog.BUTTON_POSITIVE)
        assertSame(original, page.step.keys.single())
        assertEquals("C", original.code)
        assertEquals("fcitx", original.keyType)
        assertTrue(ReflectionHelpers.getField<Boolean>(page.activity, "hasChanges"))
    }

    @Test fun legacyAndroidKeysKeepTheirTypeWhenConfirmed() = page(listOf(KeyRef.Android(66))) { page ->
        val dialog = page.open(0)
        page.select(dialog, "67", asAndroid = true)
        assertEquals("66", page.step.keys.single().code)
        page.click(dialog, AlertDialog.BUTTON_POSITIVE)
        assertEquals("67", page.step.keys.single().code)
        assertEquals("android", page.step.keys.single().keyType)
    }

    @Test fun deletingAnEqualImportedKeyRemovesTheClickedOccurrence() =
        page(listOf(KeyRef.Fcitx("A"), KeyRef.Fcitx("A"))) { page ->
            val first = page.step.keys.first()
            val dialog = page.open(1)
            page.select(dialog, "B")
            page.click(dialog, AlertDialog.BUTTON_NEUTRAL)
            assertSame(first, page.step.keys.single())
            assertEquals("A", first.code)
        }

    @Test fun deletingANewDraftCannotRemoveAnExistingEqualKey() = page { page ->
        val original = page.step.keys.single()
        val dialog = page.open(1) // Add-key chip.
        page.select(dialog, "A")
        page.click(dialog, AlertDialog.BUTTON_NEUTRAL)
        assertSame(original, page.step.keys.single())
        assertFalse(ReflectionHelpers.getField<Boolean>(page.activity, "hasChanges"))
    }

    @Test fun cancellingANewKeyDoesNotAppendIt() = page { page ->
        val dialog = page.open(1)
        page.select(dialog, "B")
        page.click(dialog, AlertDialog.BUTTON_NEGATIVE)
        assertEquals(listOf("A"), page.step.keys.map { it.code })
    }

    @Test fun confirmingANewKeyAppendsTheSelectedDraft() = page { page ->
        val dialog = page.open(1)
        page.select(dialog, "B")
        assertEquals(1, page.step.keys.size)
        page.click(dialog, AlertDialog.BUTTON_POSITIVE)
        assertEquals(listOf("A", "B"), page.step.keys.map { it.code })
    }
}
