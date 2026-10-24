/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.core.view.descendants
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.KeyEditorForm
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.KeyboardEditorUiBuilder
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.MacroEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeColorEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter.KeyboardLayoutAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class KeyEditorFormTest {
    private fun page(block: (Activity) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.Theme_FcitxSettings_Base_Light)
        controller.setup()
        try { block(controller.get()) } finally { controller.pause().stop().destroy() }
    }

    private fun options(key: Map<String, Any?>) = Intent().putExtra(
        KeyEditorForm.EXTRA_KEY_DATA, KeyEditorForm.toSerializableMap(key))

    private fun field(form: KeyEditorForm, label: String): EditText {
        return (form.view as ViewGroup).descendants.filterIsInstance<TextInputLayout>()
            .first { it.hint?.toString() == label }.editText!!
    }

    @Test fun fullFormShowsAllSectionsAndEveryEventHasItsOwnInlineEditButton() = page { activity ->
        val key = mapOf("type" to "MacroKey", "label" to "Macro", "weight" to 0.2f)
        var launched: Intent? = null
        val form = KeyEditorForm(activity, options(key), launchMacro = { launched = it })
        activity.setContentView(form.view)
        val views = (form.view as ViewGroup).descendants.toList()
        assertFalse(views.any { it is TabLayout })
        for (label in listOf(R.string.layout_editor_content, R.string.layout_editor_actions, R.string.layout_editor_appearance)) {
            assertTrue(views.filterIsInstance<TextView>().any { it.text == activity.getString(label) && it.isShown })
        }
        assertFalse(views.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.layout_editor_advanced) })
        for ((slot, title) in listOf("tap" to R.string.text_keyboard_layout_macro_tap_event,
            "swipeUp" to R.string.text_keyboard_layout_macro_swipe_up_event,
            "swipeDown" to R.string.text_keyboard_layout_macro_swipe_down_event,
            "longPress" to R.string.text_keyboard_layout_macro_longpress_event)) {
            val row = views.filterIsInstance<LinearLayout>().single {
                it.childCount == 3 && (it.getChildAt(0) as? TextView)?.text == activity.getString(title)
            }
            assertEquals(LinearLayout.HORIZONTAL, row.orientation)
            assertEquals(activity.getString(R.string.edit), (row.getChildAt(2) as TextView).text)
            row.getChildAt(2).performClick()
            assertEquals(slot, launched?.getStringExtra(MacroEditorActivity.EXTRA_TARGET_TOKEN))
        }
    }

    @Test fun everyKeyTypeKeepsGeometryAndColorThroughTheSharedForm() = page { activity ->
        for (type in KeyboardEditorUiBuilder.KEY_TYPES) {
            val key = mutableMapOf<String, Any?>("type" to type, "weight" to 0.25f,
                "rowHeightPercent" to 22f, "splitAfter" to true, "textColor" to -16711936,
                "main" to "a", "alt" to "A", "label" to "Key")
            val form = KeyEditorForm(activity, options(key))
            val result = form.validatedDraft(false)
            assertNotNull(type, result)
            assertEquals(type, result!!["type"])
            assertEquals(type, 0.25f, result["weight"])
            assertEquals(type, 22f, result["rowHeightPercent"])
            assertEquals(type, true, result["splitAfter"])
            assertEquals(type, -16711936, result["textColor"])
        }
    }

    @Test fun liveInspectorEditsUseTheFullFormAndRejectAnInvalidWidth() = page { activity ->
        val inspector = KeyboardKeyInspector(activity)
        var emitted: Map<String, Any?>? = null
        inspector.onDraft = { emitted = it }
        inspector.bind(EditorKeyPosition(0, 0), mapOf("type" to "AlphabetKey", "main" to "a", "alt" to "A"))
        activity.setContentView(inspector)
        val fields = inspector.descendants.filterIsInstance<EditText>().toList()
        fields.first { it.text.toString() == "a" }.setText("b")
        assertEquals("b", emitted?.get("main"))
        val width = inspector.descendants.filterIsInstance<TextInputLayout>()
            .single { it.hint == activity.getString(R.string.layout_editor_width) }.editText!!
        width.setText("125")
        assertFalse(inspector.validate())
        assertFalse(emitted!!.containsKey("weight"))
        width.setText("32")
        assertTrue(inspector.validate())
        assertEquals(0.32f, emitted?.get("weight"))
    }

    @Test fun openingAKeyDoesNotPublishImplicitDefaultsAsAnEdit() = page { activity ->
        val inspector = KeyboardKeyInspector(activity)
        var changes = 0
        val drafts = mutableListOf<Map<String, Any?>>()
        inspector.onDraft = { changes++; drafts.add(it) }
        activity.setContentView(inspector)
        for (key in listOf(mapOf("type" to "NumPadKey"), mapOf("type" to "MacroKey", "label" to "M"))) {
            inspector.bind(EditorKeyPosition(0, 0), key)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(inspector.validate())
            assertEquals("Opening $key published $drafts", 0, changes)
        }
        assertEquals(0, changes)
    }

    @Test fun structureCellsKeepEqualWidthsForLettersAndLongLabelsAfterRebinding() = page { activity ->
        val row = mutableListOf(
            mutableMapOf<String, Any?>("type" to "AlphabetKey", "main" to "a"),
            mutableMapOf<String, Any?>("type" to "MacroKey", "label" to "A long action name"),
            mutableMapOf<String, Any?>("type" to "SpaceKey", "weight" to 0.6f))
        val adapter = KeyboardLayoutAdapter(activity, listOf(row), NoOpListener)
        val parent = android.widget.FrameLayout(activity)
        val holder = adapter.onCreateViewHolder(parent, 0) as KeyboardLayoutAdapter.RowViewHolder
        parent.addView(holder.itemView)
        activity.setContentView(parent)
        fun checkCells() {
            adapter.onBindViewHolder(holder, 0)
            parent.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
            parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
            val cells = (0 until holder.keysFlow.childCount).map(holder.keysFlow::getChildAt)
            assertEquals(1, cells.map { it.width }.distinct().size)
            assertEquals(1, cells.map { it.height }.distinct().size)
            assertTrue(cells.first().width > 0)
        }
        checkCells()
        row.removeAt(0)
        checkCells()
        row.add(mutableMapOf("type" to "ReturnKey"))
        checkCells()
    }

    @Test fun aRecreatedFormAcceptsThePendingEventAndKeepsTypedContentAndColors() = page { activity ->
        val args = options(mapOf("type" to "MacroKey", "label" to "Before", "weight" to 0.2f))
        val form = KeyEditorForm(activity, args)
        field(form, activity.getString(R.string.text_keyboard_layout_key_label)).setText("After")
        val state = Bundle().also(form::saveState)
        val recreated = KeyEditorForm(activity, args, state)
        val steps = arrayListOf<Map<*, *>>(mapOf("type" to "text", "text" to "hello"))
        recreated.acceptMacroResult(ActivityResult(Activity.RESULT_OK, Intent()
            .putExtra(MacroEditorActivity.EXTRA_TARGET_TOKEN, "swipeDown")
            .putExtra(MacroEditorActivity.EXTRA_MACRO_RESULT, steps)))
        recreated.acceptColorResult(ThemeColorEditorActivity.EditorResult("textColor", -16711936))
        val saved = recreated.validatedDraft(false)!!
        assertEquals("After", saved["label"])
        assertEquals(steps, (saved["swipeDown"] as Map<*, *>)["macro"])
        assertEquals(-16711936, saved["textColor"])
        assertEquals(0.2f, saved["weight"])
    }

    private fun splitCheckbox(root: ViewGroup): CheckBox = root.descendants.filterIsInstance<CheckBox>().single {
        it.text == root.context.getString(R.string.text_keyboard_layout_key_split_after)
    }

    @Test fun splitBoundaryCanBeSetAndClearedForEveryKeyTypeAndRestoredAfterRecreation() = page { activity ->
        for (type in KeyboardEditorUiBuilder.KEY_TYPES) {
            val args = options(mapOf("type" to type, "main" to "a", "alt" to "A", "label" to "Key", "rowHeightPercent" to 22f))
            val form = KeyEditorForm(activity, args)
            activity.setContentView(form.view)
            val checkbox = splitCheckbox(form.view as ViewGroup)
            assertTrue(checkbox.isShown)
            assertFalse(checkbox.isChecked)
            assertFalse(form.hasChanges())
            checkbox.performClick()
            assertTrue(form.hasChanges())
            assertEquals(type, true, form.validatedDraft(false)!!["splitAfter"])
            val recreated = KeyEditorForm(activity, args, Bundle().also(form::saveState))
            val restoredCheckbox = splitCheckbox(recreated.view as ViewGroup)
            assertTrue(restoredCheckbox.isChecked)
            assertEquals(type, true, recreated.validatedDraft(false)!!["splitAfter"])
            restoredCheckbox.performClick()
            val result = recreated.validatedDraft(false)!!
            assertFalse(type, result.containsKey("splitAfter"))
            assertEquals(type, 22f, result["rowHeightPercent"])
        }
    }

    @Test fun inlineSplitBoundaryImmediatelyUpdatesPreviewDraftAndSurvivesATypeChange() = page { activity ->
        val inspector = KeyboardKeyInspector(activity)
        val drafts = mutableListOf<Map<String, Any?>>()
        inspector.onDraft = { drafts.add(it) }
        inspector.bind(EditorKeyPosition(0, 0), mapOf("type" to "AlphabetKey", "main" to "a", "alt" to "A", "splitAfter" to true))
        activity.setContentView(inspector)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(drafts.isEmpty())
        splitCheckbox(inspector).performClick()
        assertFalse(drafts.last().containsKey("splitAfter"))
        splitCheckbox(inspector).performClick()
        assertEquals(true, drafts.last()["splitAfter"])
        val selector = inspector.descendants.filterIsInstance<Spinner>().single {
            it.adapter.count == KeyboardEditorUiBuilder.KEY_TYPES.size
        }
        selector.setSelection(KeyboardEditorUiBuilder.KEY_TYPES.indexOf("SpaceKey"))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(splitCheckbox(inspector).isChecked)
        assertTrue(inspector.validate())
        assertEquals("SpaceKey", drafts.last()["type"])
        assertEquals(true, drafts.last()["splitAfter"])
        splitCheckbox(inspector).performClick()
        assertFalse(drafts.last().containsKey("splitAfter"))
    }

    @Test fun composeOverrideCannotChangeTheBaseKeysSplitBoundary() = page { activity ->
        val args = options(mapOf("type" to "AlphabetKey", "main" to "a", "alt" to "A", "splitAfter" to true))
            .putExtra(KeyEditorForm.EXTRA_COMPOSE_OVERRIDE_EDITOR_MODE, true)
        val form = KeyEditorForm(activity, args)
        assertFalse((form.view as ViewGroup).descendants.filterIsInstance<CheckBox>().any {
            it.text == activity.getString(R.string.text_keyboard_layout_key_split_after)
        })
        assertFalse(form.validatedDraft(false)!!.containsKey("splitAfter"))
    }

    private object NoOpListener : KeyboardLayoutAdapter.Listener {
        override fun onKeyClick(rowIndex: Int, keyIndex: Int) {}
        override fun onAddKeyClick(rowIndex: Int) {}
        override fun onDeleteRowClick(rowIndex: Int) {}
        override fun onEditRowClick(rowIndex: Int) {}
        override fun onMoveRowUpClick(rowIndex: Int) {}
        override fun onAddRowClick() {}
        override fun onRowPositionChanged(from: Int, to: Int) {}
        override fun onRowDragEnded() {}
        override fun onKeyPositionChanged(rowIndex: Int, from: Int, to: Int) {}
        override fun onKeyDragEnded(rowIndex: Int) {}
        override fun onKeyMovedAcrossRows(fromRow: Int, fromIndex: Int, toRow: Int, toIndex: Int) {}
    }
}
