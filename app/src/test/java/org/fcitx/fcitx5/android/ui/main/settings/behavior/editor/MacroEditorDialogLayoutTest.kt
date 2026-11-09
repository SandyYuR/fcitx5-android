/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.view.descendants
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.input.keyboard.KeyRef
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.MacroEditorDialogFragment
import org.fcitx.fcitx5.android.utils.parcelable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], qualifiers = "w360dp-h640dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class MacroEditorDialogLayoutTest {
    private fun page(manySteps: Boolean = false, initialSteps: List<MacroStep>? = null,
        block: (MacroEditorDialogFragment, ViewGroup, () -> Bundle?) -> Unit) {
        val steps = initialSteps ?: List(if (manySteps) 16 else 1) { MacroStep.Text("original $it") }
        val intent = Intent().putExtra(MacroEditorDialogFragment.EXTRA_MACRO_STEPS_JSON, Json.encodeToString<List<MacroStep>>(steps))
            .putExtra(MacroEditorDialogFragment.EXTRA_TARGET_TOKEN, "longPress")
        val controller = launchMacroEditorTestHost(intent)
        try {
            val activity = controller.get()
            val fragment = activity.macroEditorFragment()
            assertFalse("The host page remains the active window", activity.window.isFloating)
            assertTrue(fragment.dialog!!.isShowing)
            var result: Bundle? = null
            activity.supportFragmentManager.setFragmentResultListener(
                MacroEditorDialogFragment.RESULT_REQUEST_KEY, activity
            ) { _, value -> result = value }
            val decor = fragment.dialog!!.window!!.decorView as ViewGroup
            val list = decor.descendants.filterIsInstance<RecyclerView>().single()
            val root = list.parent as ViewGroup
            val width = fragment.dialog!!.window!!.attributes.width
            val height = fragment.dialog!!.window!!.attributes.height
            assertTrue(width in 1 until activity.resources.configuration.screenWidthDp)
            assertTrue(height in 1 until activity.resources.configuration.screenHeightDp)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            block(fragment, root) { result }
        } finally { controller.pause().stop().destroy() }
    }

    private fun button(root: ViewGroup, label: Int): MaterialButton =
        root.descendants.filterIsInstance<MaterialButton>().single { it.text == root.context.getString(label) }

    private fun bounds(root: ViewGroup, view: View) = Rect(0, 0, view.width, view.height).also {
        root.offsetDescendantRectToMyCoords(view, it)
    }

    private fun verifyControls(root: ViewGroup) {
        val list = root.descendants.filterIsInstance<RecyclerView>().single()
        val add = button(root, R.string.macro_editor_add_step)
        val confirm = button(root, R.string.macro_editor_confirm)
        val cancel = button(root, R.string.macro_editor_cancel)
        assertTrue("The steps must retain scrollable space", list.height > 0)
        val addBounds = bounds(root, add)
        val confirmBounds = bounds(root, confirm)
        assertTrue(addBounds.top >= bounds(root, list).bottom)
        assertTrue(confirmBounds.top >= addBounds.bottom)
        for (view in listOf(add, confirm, cancel)) {
            val rect = bounds(root, view)
            assertTrue(rect.height() >= 48)
            assertTrue(rect.left >= 0 && rect.right <= root.width && rect.bottom <= root.height)
        }
        assertFalse(Rect.intersects(confirmBounds, bounds(root, cancel)))
        assertTrue(list.canScrollVertically(1))
        assertFalse(confirm.isEnabled)
    }

    @Test fun longMacrosScrollWithoutHidingAddCancelOrConfirm() = page(true) { _, root, _ -> verifyControls(root) }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun landscapeWithLargeTextKeepsControlsInsideTheDialog() {
        RuntimeEnvironment.setFontScale(1.5f)
        try { page(true) { _, root, _ -> verifyControls(root) } }
        finally { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun confirmationReturnsEditedStepsAndTheOriginalTargetToken() = page { _, root, result ->
        root.descendants.filterIsInstance<EditText>().first().setText("edited")
        val confirm = button(root, R.string.macro_editor_confirm)
        assertTrue(confirm.isEnabled)
        confirm.performClick()
        assertEquals(Activity.RESULT_OK, result()!!.getInt(MacroEditorDialogFragment.RESULT_CODE))
        val resultIntent = result()!!.parcelable<Intent>(MacroEditorDialogFragment.RESULT_DATA)!!
        assertEquals("longPress", resultIntent.getStringExtra(MacroEditorDialogFragment.EXTRA_TARGET_TOKEN))
        val steps = Json.decodeFromString<List<MacroStep>>(resultIntent.getStringExtra(MacroEditorDialogFragment.EXTRA_MACRO_RESULT_JSON)!!)
        assertEquals(listOf(MacroStep.Text("edited")), steps)
    }

    @Test fun lazilyBoundTextStepCanAcceptInputFromTheSystemIme() = page { fragment, root, _ ->
        assertEquals("AlertController must not exclude editors created by RecyclerView from the IME",
            0, fragment.dialog!!.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        val editor = root.descendants.filterIsInstance<EditText>().first()
        assertTrue(editor.requestFocus())
        assertTrue(editor.showSoftInputOnFocus)
        editor.selectAll()
        val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        assertTrue(connection.commitText("中文😀", 1))
        assertEquals("中文😀", editor.text.toString())
        assertTrue(button(root, R.string.macro_editor_confirm).isEnabled)
    }

    @Test fun changingAnInitiallyNonEditableStepToTextKeepsTheImeAvailable() =
        page(initialSteps = listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("Return"))))) { fragment, root, _ ->
            val selector = root.descendants.filterIsInstance<org.fcitx.fcitx5.android.ui.common.MaterialSpinner>().single()
            selector.setSelection(7)
            shadowOf(Looper.getMainLooper()).idle()
            val editor = root.descendants.filterIsInstance<EditText>().single()
            assertTrue(editor.isShown)
            assertTrue(editor.requestFocus())
            assertEquals(0, fragment.dialog!!.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            assertTrue(requireNotNull(editor.onCreateInputConnection(EditorInfo())).commitText("新增文本", 1))
            assertEquals("新增文本", editor.text.toString())
        }

    @Test fun cancelDoesNotReturnAnEditedMacro() = page { fragment, root, result ->
        root.descendants.filterIsInstance<EditText>().first().setText("discarded")
        button(root, R.string.macro_editor_cancel).performClick()
        assertFalse(fragment.dialog?.isShowing ?: false)
        assertEquals(Activity.RESULT_CANCELED, result()!!.getInt(MacroEditorDialogFragment.RESULT_CODE))
    }

    @Test fun stepSelectorAndDeleteControlLeaveASeparateFullWidthActionRow() = page { _, root, _ ->
        val list = root.descendants.filterIsInstance<RecyclerView>().single()
        val step = list.getChildAt(0) as ViewGroup
        val selector = step.descendants.filterIsInstance<org.fcitx.fcitx5.android.ui.common.MaterialSpinner>().single()
        val delete = step.descendants.filterIsInstance<MaterialButton>().single { it.contentDescription == root.context.getString(R.string.macro_editor_delete) }
        val editor = step.descendants.filterIsInstance<EditText>().single()
        val selectorBounds = bounds(step, selector)
        val deleteBounds = bounds(step, delete)
        assertFalse(Rect.intersects(selectorBounds, deleteBounds))
        assertTrue(bounds(step, editor).top >= selectorBounds.bottom)
        assertTrue(editor.width > selector.width)
    }
}
