/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.app.Activity
import org.fcitx.fcitx5.android.FcitxApplication
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.descendants
import com.google.android.material.tabs.TabLayout
import java.time.Duration
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.MaterialSpinner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Measures actual Android views: compilation cannot detect a zero-height inspector. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class KeyboardEditorWorkspaceTest {
    private fun page(block: (Activity) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_FcitxSettings_Base_Light)
        controller.setup()
        try { block(activity) } finally { controller.pause().stop().destroy() }
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun preview(activity: Activity, overlay: View, height: Int) = FrameLayout(activity).apply {
        addView(View(activity), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @Test fun overlayDoesNotConsumeTheSpaceReservedForTheInspector() = page { activity ->
        val overlay = KeyboardCanvasOverlay(activity)
        val keyboard = preview(activity, overlay, 320)
        val inspector = KeyboardKeyInspector(activity).apply {
            bind(EditorKeyPosition(0, 0), mapOf("type" to "AlphabetKey", "main" to "a", "alt" to "A"))
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(keyboard, LinearLayout.LayoutParams(-1, -2))
            addView(inspector, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        activity.setContentView(root)
        layout(root, 1080, 1600)
        assertEquals("The overlay must use the preview height, not the viewport", 320, keyboard.height)
        assertEquals(keyboard.height, overlay.height)
        assertTrue("The selected key properties must have visible height", inspector.height > 0)
        val fields = inspector.descendants.filterIsInstance<EditText>().filter { it.isShown }.toList()
        assertTrue(fields.isNotEmpty())
        assertTrue(fields.all { it.height > 0 })
    }

    @Test fun portraitAndLandscapeKeepThePreviewSizeAndScrollableProperties() = page { activity ->
        val overlay = KeyboardCanvasOverlay(activity)
        val keyboard = preview(activity, overlay, 480)
        val inspector = KeyboardKeyInspector(activity).apply {
            bind(EditorKeyPosition(0, 0), mapOf("type" to "AlphabetKey", "main" to "a", "alt" to "A"))
        }
        val selectors = MaterialSpinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("Default", "Schema A"))
        }
        val workspace = KeyboardEditorWorkspace(activity, selectors, TextView(activity), keyboard, inspector)
        activity.setContentView(workspace)
        for ((width, height) in listOf(1080 to 1600, 1600 to 700, 1080 to 260)) {
            layout(workspace, width, height)
            assertEquals("Preview width must not be halved in landscape", width, keyboard.width)
            assertEquals("Small windows and the IME must not squash the preview", 480, keyboard.height)
            assertEquals(480, overlay.height)
            assertTrue(inspector.height > 0)
            assertEquals(View.VISIBLE, selectors.visibility)
            assertTrue(selectors.height > 0)
            assertTrue(selectors.bottom <= keyboard.top)
            val fields = inspector.descendants.filterIsInstance<EditText>().filter { it.isShown }.toList()
            assertTrue(fields.isNotEmpty())
            assertTrue(fields.all { it.height > 0 })
            workspace.revealProperties(inspector)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            workspace.computeScroll()
            val target = Rect()
            assertTrue("Properties must be reachable by scrolling", inspector.getGlobalVisibleRect(target))
        }
    }

    @Test fun selectedSpaceKeyHasAccessibleAppearanceControls() = page { activity ->
        val inspector = KeyboardKeyInspector(activity).apply {
            bind(EditorKeyPosition(4, 3), mapOf("type" to "SpaceKey", "weight" to 0.4f))
        }
        val workspace = KeyboardEditorWorkspace(activity, TextView(activity), TextView(activity),
            preview(activity, KeyboardCanvasOverlay(activity), 640), inspector)
        activity.setContentView(workspace)
        assertFalse(inspector.descendants.any { it is TabLayout })
        layout(workspace, 1080, 800)
        val width = inspector.descendants.filterIsInstance<EditText>().single { it.text.toString() == "40.0" }
        assertTrue(width.height > 0)
        assertEquals("40.0", width.text.toString())
        width.requestFocus()
        width.requestRectangleOnScreen(Rect(0, 0, width.width, width.height))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        workspace.computeScroll()
        assertTrue("The space key width editor must scroll into view", width.getGlobalVisibleRect(Rect()))
    }

    @Test fun tappingThePreviewBindsTheInspectorAndKeepsTheSchemaSelectorUsable() = page { activity ->
        val keys = listOf(
            mapOf<String, Any?>("type" to "AlphabetKey", "main" to "a", "alt" to "A"),
            mapOf<String, Any?>("type" to "AlphabetKey", "main" to "b", "alt" to "B")
        )
        val inspector = KeyboardKeyInspector(activity)
        val selectors = MaterialSpinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("Default", "Schema A"))
        }
        var selected = false
        val overlay = KeyboardCanvasOverlay(activity).apply {
            boundsProvider = { listOf(EditorKeyBounds(EditorKeyPosition(0, 0), RectF(0f, 0f, 200f, 200f), "a", false)) }
            onSelect = {
                inspector.bind(it, keys[selectors.selectedItemPosition])
                selected = true
                true
            }
        }
        val workspace = KeyboardEditorWorkspace(activity, selectors, TextView(activity), preview(activity, overlay, 480), inspector)
        overlay.onShowInspector = { workspace.revealProperties(inspector) }
        activity.setContentView(workspace)
        layout(workspace, 1080, 1600)
        fun tap() {
            val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 50f, 50f, 0)
            val up = MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, 50f, 50f, 0)
            overlay.onTouchEvent(down)
            overlay.onTouchEvent(up)
            down.recycle(); up.recycle()
            layout(workspace, 1080, 1600)
            shadowOf(Looper.getMainLooper()).idle()
        }
        tap()
        assertTrue(selected)
        assertEquals("a", inspector.descendants.filterIsInstance<EditText>().first().text.toString())
        assertTrue(inspector.height > 0)
        assertTrue(selectors.isShown)
        selectors.setSelection(1)
        tap()
        assertEquals("b", inspector.descendants.filterIsInstance<EditText>().first().text.toString())
        assertTrue(selectors.isShown)
    }
}
