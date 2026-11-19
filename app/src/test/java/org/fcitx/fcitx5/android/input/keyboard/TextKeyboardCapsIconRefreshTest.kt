/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.drawable.BitmapDrawable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * 图标主题刷新必须重放当前的 Caps 状态，而不是构造期固定的 none 槽位。
 *
 * 场景：按住 Shift（Once）后切换图标主题。通用刷新只会按构造期记录的槽位重画，
 * 若 TextKeyboard 不复用 [TextKeyboard.onIconThemeRefreshed] 再校正一次，按下态会被
 * 画回未按下态——用户看到 Shift 灯灭了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextKeyboardCapsIconRefreshTest {
    private val layout = Json.parseToJsonElement(
        """
        {"default":[[
          {"type":"CapsKey"},
          {"type":"AlphabetKey","main":"a","alt":"!"}
        ]]}
        """.trimIndent()
    ).jsonObject

    private fun svg(color: String) =
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>" +
                "<rect x='0' y='0' width='24' height='24' fill='$color'/></svg>"

    private fun theme(name: String, none: String, once: String, lock: String) = IconTheme(
        name = name,
        icons = mapOf(
            "keys.capslock.none" to svg(none),
            "keys.capslock.once" to svg(once),
            "keys.capslock.lock" to svg(lock)
        )
    )

    private fun TextKeyboard.capsOnce() {
        ReflectionHelpers.callInstanceMethod<Unit>(
            this, "onAction",
            ClassParameter.from(KeyAction::class.java, KeyAction.CapsAction(false)),
            ClassParameter.from(KeyActionListener.Source::class.java, KeyActionListener.Source.Keyboard)
        )
    }

    private fun TextKeyboard.capsPixel(): Int {
        val views = ReflectionHelpers.getField<TextKeyboard.SpecialKeyViews>(this, "specialKeyViews").caps
        assertEquals("layout should provide exactly one Caps key", 1, views.size)
        val drawable = views.single().drawable
        assertTrue("icon theme SVG should reach the Caps key, got $drawable", drawable is BitmapDrawable)
        val bitmap = (drawable as BitmapDrawable).bitmap
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }

    @After
    fun restoreTheme() {
        IconThemeManager.selectTheme(IconThemeManager.iconThemes.first())
    }

    @Test
    fun iconThemeRefreshReplaysTheCurrentCapsSlot() {
        val first = theme("caps-refresh-first", none = "#FF0000", once = "#00FF00", lock = "#0000FF")
        val second = theme("caps-refresh-second", none = "#FF0000", once = "#00FFFF", lock = "#0000FF")
        IconThemeManager.selectTheme(first)
        TextKeyboard.withPreviewLayout(layout) {
            val context = RuntimeEnvironment.getApplication()
            SplitKeyboardStateManager.init(context)
            val ime = InputMethodEntry("preview")
            val keyboard = TextKeyboard(context, ThemePreset.PixelLight, initialIme = ime, isPreview = true)
            keyboard.onInputMethodUpdate(ime)
            try {
                keyboard.capsOnce()
                assertEquals(0xFF00FF00.toInt(), keyboard.capsPixel())

                // 模拟图标主题监听：换主题后走的正是 refreshIconTheme()。
                IconThemeManager.selectTheme(second)
                keyboard.refreshIconTheme()

                assertEquals(
                    "Shift 处于 Once 时必须重放 once 槽位，而不是 none",
                    0xFF00FFFF.toInt(),
                    keyboard.capsPixel()
                )
            } finally {
                keyboard.onDetach()
            }
        }
    }
}
