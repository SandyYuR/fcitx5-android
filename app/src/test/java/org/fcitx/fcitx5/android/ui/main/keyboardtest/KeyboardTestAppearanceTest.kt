/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.keyboardtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Native Robolectric rendering of attached dialog windows; these are not device screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class KeyboardTestAppearanceTest {
    private class Host : SettingsUiActivity()
    private data class Scenario(val name: String, val dark: Boolean = false, val pin: Boolean = false,
                                val width: Int = 360, val scale: Float = 1f)

    @Test fun tabsEditorAndCloseRemainVisibleInLightDarkPinAndLargeTextWindows() {
        val context = RuntimeEnvironment.getApplication()
        val original = SettingsUiPreferences.read(context)
        val scenarios = listOf(Scenario("light"), Scenario("dark", dark = true),
            Scenario("pin", pin = true), Scenario("large-320dp", width = 320, scale = 1.6f))
        try {
            for (scene in scenarios) {
                RuntimeEnvironment.setFontScale(scene.scale)
                SettingsUiPreferences.save(context, original.copy(
                    mode = if (scene.dark) SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
                    palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false))
                val controller = Robolectric.buildActivity(Host::class.java).setup()
                val dialog = KeyboardTestDialog(controller.get())
                try {
                    dialog.show()
                    val panel = dialog.panel
                    if (scene.pin) {
                        panel.select(KeyboardTestPanel.Kind.PIN)
                        panel.input.setText("1234")
                        assertEquals(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, panel.input.inputType)
                        assertTrue(panel.input.transformationMethod is PasswordTransformationMethod)
                    }
                    val decor = dialog.window!!.decorView as ViewGroup
                    decor.layoutDirection = View.LAYOUT_DIRECTION_LTR
                    fun layout() {
                        decor.measure(View.MeasureSpec.makeMeasureSpec(scene.width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                        decor.layout(0, 0, scene.width, 800)
                    }
                    layout()
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
                    layout()
                    // Keep the rendered evidence even when a reachability assertion fails.
                    System.getenv("KEYBOARD_TEST_PREVIEW_DIR")?.let { directory ->
                        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                        try {
                            decor.draw(Canvas(bitmap))
                            File(directory, "${scene.name}-robolectric.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        } finally { bitmap.recycle() }
                    }
                    assertTrue(decor.isAttachedToWindow)
                    assertEquals(4, panel.tabs.childCount)
                    assertEquals(1, (0 until panel.tabs.childCount).map { panel.tabs.getChildAt(it).width }.distinct().size)
                    val close = descendants(panel).single { it.contentDescription == panel.context.getString(R.string.keyboard_test_close) }
                    val controls = (0 until panel.tabs.childCount).map(panel.tabs::getChildAt) + listOf(panel.input, close)
                    for (control in controls) {
                        val bounds = Rect(); control.getDrawingRect(bounds); decor.offsetDescendantRectToMyCoords(control, bounds)
                        assertTrue("${scene.name}: control escaped the viewport: $bounds", control.isShown &&
                            bounds.left >= 0 && bounds.right <= decor.width && bounds.top >= 0 && bounds.bottom <= decor.height)
                        val visible = Rect()
                        assertTrue("${scene.name}: control was clipped", control.getLocalVisibleRect(visible) &&
                            visible.width() == control.width && visible.height() == control.height)
                        if (control is MaterialButton) assertTrue(control.height >= 48)
                    }
                } finally { dialog.dismiss(); controller.pause().stop().destroy() }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            SettingsUiPreferences.save(context, original)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
