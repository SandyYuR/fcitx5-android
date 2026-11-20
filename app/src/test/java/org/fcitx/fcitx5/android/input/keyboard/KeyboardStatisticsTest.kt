/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w400dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class KeyboardStatisticsTest {
    @Test fun aSoftPressIsCountedOnceAcrossRepeatsMovesReleaseAndCancellation() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val delay = AppPrefs.getInstance().keyboard.longPressDelay
        val originalDelay = delay.getValue()
        try {
            delay.setValue(100)
            var presses = 0
            var repeats = 0
            val key = TextKeyView(controller.get(), ThemePreset.PixelLight, KeyDef.Appearance.Text("a", 20f)).apply {
                onStatisticsPress = { presses++ }
                repeatEnabled = true
                onRepeatListener = { repeats++ }
            }
            controller.get().setContentView(key)
            key.setViewTreeLifecycleOwner(controller.get())
            measure(key, 100, 60)
            fun touch(action: Int) {
                MotionEvent.obtain(1, SystemClock.uptimeMillis(), action, 20f, 20f, 0).also {
                    try { key.onTouchEvent(it) } finally { it.recycle() }
                }
            }
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_MOVE)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
            assertTrue(repeats > 0)
            assertEquals(1, presses)
            touch(MotionEvent.ACTION_UP)
            assertEquals(1, presses)
            key.isEnabled = false
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_CANCEL)
            assertEquals(1, presses)
            key.isEnabled = true
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_CANCEL)
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_UP)
            assertEquals(3, presses)
        } finally { delay.setValue(originalDelay); controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun geometryPreservesRealSplitGapsAndRemovesDisabledOrDetachedKeys() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity)
            val row = FrameLayout(activity)
            root.addView(row, FrameLayout.LayoutParams(400, 70))
            fun key(label: String, left: Int, width: Int): TextKeyView = TextKeyView(activity, ThemePreset.PixelLight,
                KeyDef.Appearance.Text(label, 20f, margin = false)).apply {
                statisticsKeyLabel = label
                row.addView(this, FrameLayout.LayoutParams(width, 70).apply { leftMargin = left })
            }
            val left = key("a", 0, 80)
            val right = key("Space", 220, 180)
            val disabled = key("disabled", 100, 40).apply { isEnabled = false }
            val detached = TextKeyView(activity, ThemePreset.PixelLight, KeyDef.Appearance.Text("detached", 20f)).apply {
                statisticsKeyLabel = "detached"
            }
            activity.setContentView(root)
            measure(root, 400, 100)
            val captured = KeyboardStatisticsLayout.capture(root, listOf(detached, disabled, right, left))
            assertEquals(listOf(listOf("a", "Space")), captured.rows)
            assertEquals(2, captured.geometry.size)
            assertEquals(0f, captured.geometry[0].x, 0.001f)
            assertEquals(0.2f, captured.geometry[0].width, 0.001f)
            assertEquals(0.55f, captured.geometry[1].x, 0.001f)
            assertEquals(0.45f, captured.geometry[1].width, 0.001f)
            assertEquals(70f, captured.geometry[1].height, 0.001f)
            assertEquals("Without row containers the old parent grouping has no row number", -1, captured.geometry[0].row)
            row.removeView(right)
            val next = KeyboardStatisticsLayout.capture(root, listOf(left, right))
            assertEquals(listOf(listOf("a")), next.rows)
            assertEquals("Earlier snapshots remain immutable", 2, captured.geometry.size)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun sideBarKeysJoinTheirNearestRowInsteadOfBecomingExtraRows() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity)
            activity.setContentView(root)
            val rowHeight = 70
            val rowWidth = 400
            val rows = (0 until 3).map { index ->
                FrameLayout(activity).also { row ->
                    root.addView(row, FrameLayout.LayoutParams(rowWidth, rowHeight).apply { topMargin = index * rowHeight })
                }
            }
            fun key(label: String, parent: FrameLayout, left: Int, top: Int, width: Int = 80) = TextKeyView(activity,
                ThemePreset.PixelLight, KeyDef.Appearance.Text(label, 20f, margin = false)).apply {
                statisticsKeyLabel = label
                parent.addView(this, FrameLayout.LayoutParams(width, rowHeight).apply {
                    leftMargin = left
                    topMargin = top
                })
            }
            // Main keys: q / a / z, one per row.
            val main = listOf(key("q", rows[0], 0, 0), key("a", rows[1], 0, 0), key("z", rows[2], 0, 0))
            // Side bar: each item is its own wrapper, and the last one is a pixel lower than the
            // main row it belongs to. Parent-based grouping turned these into extra rows.
            val sideBar = FrameLayout(activity)
            root.addView(sideBar, FrameLayout.LayoutParams(100, 3 * rowHeight).apply { leftMargin = 300 })
            val side = (0 until 3).map { index ->
                FrameLayout(activity).also { item ->
                    sideBar.addView(item, FrameLayout.LayoutParams(100, rowHeight).apply {
                        topMargin = index * rowHeight + if (index == 2) 1 else 0
                    })
                }
            }
            val labels = listOf("全选", "复制", "粘贴")
            val auxiliary = labels.mapIndexed { index, label -> key(label, side[index], 0, 0, 100) }
            measure(root, rowWidth, 3 * rowHeight)
            val captured = KeyboardStatisticsLayout.capture(root, main + auxiliary, rows)
            assertEquals(listOf(listOf("q", "全选"), listOf("a", "复制"), listOf("z", "粘贴")), captured.rows)
            assertEquals(3, captured.geometry.map { it.row }.distinct().size)
            captured.geometry.filter { it.label in labels }.forEachIndexed { index, geometry ->
                assertEquals(index, geometry.row)
            }
            val last = captured.geometry.single { it.label == "粘贴" }
            assertEquals("Side bar keys snap to the row they join", 140f, last.y, 0.001f)
            assertEquals(6, captured.geometry.size)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun labelsUseKeyNamesAndNeverExpandMacroContentsOrDecorations() {
        assertEquals("a", KeyboardStatisticsLayout.label(AlphabetKey("A", "!", displayText = "Custom cap")))
        assertEquals("Space", KeyboardStatisticsLayout.label(SpaceKey()))
        assertEquals("Backspace", KeyboardStatisticsLayout.label(BackspaceKey()))
        assertEquals("Enter", KeyboardStatisticsLayout.label(ReturnKey()))
        assertNull(KeyboardStatisticsLayout.label(PlaceholderKey(displayText = "Decoration")))
        val secretPayload = "payload that must not appear in the heatmap"
        val macro = MacroKey(label = "Snippet", tap = MacroAction(listOf(MacroStep.Text(secretPayload))))
        assertEquals("Snippet", KeyboardStatisticsLayout.label(macro))
    }

    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
}
