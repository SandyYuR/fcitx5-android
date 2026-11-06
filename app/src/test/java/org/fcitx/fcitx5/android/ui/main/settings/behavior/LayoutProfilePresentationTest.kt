/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class LayoutProfilePresentationTest {
    @Test fun onlyTheActiveProfileStartsExpandedAndCollapsedHighlightFillsTheCard() {
        val prefs = AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile
        val previous = prefs.getValue()
        prefs.setValue(UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)
        val controller = Robolectric.buildActivity(TextKeyboardLayoutProfileManagerActivity::class.java).setup()
        try {
            val activity = controller.get()
            val deadline = System.nanoTime() + 10_000_000_000L
            val list = ReflectionHelpers.getField<LinearLayout>(activity, "list")
            while (list.childCount == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle()
            }
            assertTrue(list.childCount > 0)
            val expanded = ReflectionHelpers.getField<Set<String>>(activity, "expanded")
            assertEquals(setOf(UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE), expanded)
            val first = list.getChildAt(0) as ViewGroup
            val header = first.getChildAt(0) as ViewGroup
            val toggle = (0 until header.childCount).map { header.getChildAt(it) }.filterIsInstance<ImageButton>()
                .single { it.contentDescription == activity.getString(R.string.text_keyboard_layout_manage_collapse) }
            toggle.performClick()
            val collapsed = list.getChildAt(0) as ViewGroup
            assertEquals(1, collapsed.childCount)
            assertEquals(0, collapsed.paddingBottom)
            assertEquals(activity.settingsColor(com.google.android.material.R.attr.colorSecondaryContainer),
                (collapsed.background as GradientDrawable).color?.defaultColor)
            val state = Bundle()
            controller.saveInstanceState(state)
            assertTrue(state.getStringArrayList("expanded")!!.isEmpty())
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 393, 740)
            System.getenv("UI_POLISH_PREVIEW_DIR")?.let { dir ->
                val bitmap = Bitmap.createBitmap(393, 740, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap).apply { drawColor(activity.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer)) }
                root.draw(canvas)
                File(dir, "layout-collapsed.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally { controller.pause().stop().destroy(); prefs.setValue(previous) }
    }
}
