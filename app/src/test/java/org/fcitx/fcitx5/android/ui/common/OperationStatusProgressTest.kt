/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.common

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.ui.common.OperationStatusView.State
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangSelection
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangUpdateForm
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class OperationStatusProgressTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var status: OperationStatusView
    private lateinit var progress: LinearProgressIndicator

    @Before fun attachToTheSettingsTheme() {
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val selection = SettingsUiPreferences.read(activity).copy(mode = SettingsUiPreferences.MODE_LIGHT,
            palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false)
        status = OperationStatusView(SettingsUiPreferences.themedContext(activity, selection))
        activity.setContentView(status)
        status.layoutDirection = View.LAYOUT_DIRECTION_LTR
        shadowOf(Looper.getMainLooper()).idle()
        status.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
        status.layout(0, 0, status.measuredWidth, status.measuredHeight)
        progress = views(status).filterIsInstance<LinearProgressIndicator>().single()
        assertTrue(status.isAttachedToWindow)
    }

    @After fun closeActivity() {
        controller.get().finish()
        controller.pause().stop().destroy()
    }

    @Test fun knownProgressStartsAtZeroAdvancesAndResetsForTheNextFile() {
        for (percent in listOf(0, 25, 100, 0)) {
            status.show("Downloading", "Current file", State.WORKING, progressPercent = percent)
            assertDeterminate(percent)
            assertEquals("Downloading", status.heading.text.toString())
            assertEquals("Current file", status.detail.text.toString())
            assertEquals(View.VISIBLE, status.detail.visibility)
        }
    }

    @Test fun anAttachedIndicatorCanSwitchBetweenKnownAndUnknownProgress() {
        status.show("Preparing", state = State.WORKING)
        assertIndeterminate()
        status.show("Downloading", state = State.WORKING, progressPercent = 25)
        assertDeterminate(25)
        status.show("Checking resources", state = State.WORKING)
        assertIndeterminate()
        status.show("Next download", state = State.WORKING, progressPercent = 0)
        assertDeterminate(0)
    }

    @Test fun invalidPercentagesNeverBecomeDeterminateProgress() {
        for (invalid in listOf(-1, 101, Int.MIN_VALUE, Int.MAX_VALUE)) {
            status.show("Downloading", state = State.WORKING, progressPercent = 25)
            assertDeterminate(25)
            status.show("Unknown length", state = State.WORKING, progressPercent = invalid)
            assertIndeterminate()
        }
    }

    @Test fun nonWorkingStatesHideProgressAndOldCallersDoNotInheritIt() {
        for (state in listOf(State.IDLE, State.SUCCESS, State.ERROR)) {
            status.show("Downloading", state = State.WORKING, progressPercent = 25)
            assertDeterminate(25)
            status.show("Stopped", state = state, progressPercent = 25)
            assertEquals(View.GONE, progress.visibility)

            // Existing import/backup callers omit the new argument.
            status.show("Another operation", state = State.WORKING)
            assertIndeterminate()
            status.show("Ready")
            assertEquals(View.GONE, progress.visibility)
        }
    }

    @Test fun errorDetailsCanStillExpandAndTheNextOperationClearsThem() {
        status.show("Downloading", state = State.WORKING, progressPercent = 25)
        status.show("Download failed", "Connection interrupted", State.ERROR)
        assertEquals(View.GONE, progress.visibility)
        assertEquals(View.GONE, status.detail.visibility)
        val details = views(status).filterIsInstance<MaterialButton>().single()
        assertEquals(View.VISIBLE, details.visibility)
        assertTrue(details.performClick())
        assertEquals(View.VISIBLE, status.detail.visibility)

        status.show("Retrying", "Connecting", State.WORKING)
        assertIndeterminate()
        assertEquals("Retrying", status.heading.text.toString())
        assertEquals("Connecting", status.detail.text.toString())
        assertEquals(View.VISIBLE, status.detail.visibility)
        assertEquals(View.GONE, details.visibility)
    }

    @Test fun currentFileProgressIsVisibleInLightDarkAndLargeTextForms() {
        try {
            for (name in listOf("light", "dark", "large")) {
                val large = name == "large"
                RuntimeEnvironment.setFontScale(if (large) 1.6f else 1f)
                val activity = controller.get()
                val context = SettingsUiPreferences.themedContext(activity, SettingsUiPreferences.read(activity).copy(
                    mode = if (name == "dark") SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
                    palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false))
                val form = WanxiangUpdateForm(context, WanxiangSelection())
                activity.setContentView(form)
                form.layoutDirection = View.LAYOUT_DIRECTION_LTR
                form.target.text = "rime\n/storage/emulated/0/Android/data/test/files/data/rime"
                form.setBusy(true, true)
                form.status.show("正在下载方案（1/3）", "rime-wanxiang-base.zip\n当前文件 25% · 1 MB / 4 MB", State.WORKING, 25)
                val width = if (large) 320 else 360
                form.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                form.layout(0, 0, width, 800)
                val indicator = views(form.status).filterIsInstance<LinearProgressIndicator>().single()
                assertEquals(25, indicator.progress)
                assertFalse(indicator.isIndeterminate)
                assertTrue(indicator.getGlobalVisibleRect(android.graphics.Rect()))
                val output = System.getenv("WANXIANG_PROGRESS_PREVIEW_DIR") ?: continue
                val bitmap = Bitmap.createBitmap(width, 800, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
                    form.draw(canvas)
                    File(output, "download-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun assertDeterminate(expected: Int) {
        assertEquals(View.VISIBLE, progress.visibility)
        assertFalse(progress.isIndeterminate)
        assertEquals(100, progress.max)
        assertEquals(expected, progress.progress)
    }

    private fun assertIndeterminate() {
        assertEquals(View.VISIBLE, progress.visibility)
        assertTrue(progress.isIndeterminate)
    }

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
}
