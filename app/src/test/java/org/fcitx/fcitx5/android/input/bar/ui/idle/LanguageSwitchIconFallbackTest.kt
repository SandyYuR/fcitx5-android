/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.view.View
import androidx.core.view.allViews
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 工具栏语言切换按钮的图标槽位回退顺序：ASCII 专用槽位 → 中文态槽位 → 内置图标。
 *
 * ASCII 专用槽位是按钮定义里的常量，永远非空；只配了中文态槽位的主题切到 ASCII 时必须
 * 沿用那个槽位，而不是掉回内置图标。这里用文字图标断言，因为文字与内置图标在按钮上
 * 是互斥的两种显示方式（文字可见 = 用了主题内容，图片可见 = 用了内置资源）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class LanguageSwitchIconFallbackTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun withIconTheme(icons: Map<String, String>, block: () -> Unit) {
        val previous = IconThemeManager.activeTheme
        try {
            IconThemeManager.activeTheme = IconTheme(name = "language switch fallback", icons = icons)
            block()
        } finally {
            IconThemeManager.activeTheme = previous
        }
    }

    private fun languageButton(): ToolButton {
        val ui = ButtonsBarUi(
            context, ThemePreset.PixelLight, listOf(ConfigurableButton("language_switch"))
        )
        return ui.root.allViews.filterIsInstance<ToolButton>().first { it.tag == "language_switch" }
    }

    private fun assertShowsText(button: ToolButton, text: String) {
        assertEquals(View.VISIBLE, button.textView.visibility)
        assertEquals(View.GONE, button.image.visibility)
        assertEquals(text, button.textView.text.toString())
    }

    private fun assertShowsBuiltInIcon(button: ToolButton) {
        assertEquals(View.VISIBLE, button.image.visibility)
        assertEquals(View.GONE, button.textView.visibility)
        assertNotNull(button.image.drawable)
    }

    @Test
    fun asciiModeFallsBackToThePrimarySlot() = withIconTheme(
        mapOf("toolbar.language_switch" to "中")
    ) {
        val ui = ButtonsBarUi(
            context, ThemePreset.PixelLight, listOf(ConfigurableButton("language_switch"))
        )
        val button = ui.root.allViews.filterIsInstance<ToolButton>()
            .first { it.tag == "language_switch" }
        assertShowsText(button, "中")
        ui.setAsciiMode(true)
        assertShowsText(button, "中")
    }

    @Test
    fun asciiModePrefersTheAsciiSlot() = withIconTheme(
        mapOf("toolbar.language_switch" to "中", "toolbar.language_switch_ascii" to "En")
    ) {
        val ui = ButtonsBarUi(
            context, ThemePreset.PixelLight, listOf(ConfigurableButton("language_switch"))
        )
        val button = ui.root.allViews.filterIsInstance<ToolButton>()
            .first { it.tag == "language_switch" }
        assertShowsText(button, "中")
        ui.setAsciiMode(true)
        assertShowsText(button, "En")
    }

    @Test
    fun asciiOnlySlotIsUsedInAsciiMode() = withIconTheme(
        mapOf("toolbar.language_switch_ascii" to "En")
    ) {
        val ui = ButtonsBarUi(
            context, ThemePreset.PixelLight, listOf(ConfigurableButton("language_switch"))
        )
        val button = ui.root.allViews.filterIsInstance<ToolButton>()
            .first { it.tag == "language_switch" }
        assertShowsBuiltInIcon(button)
        ui.setAsciiMode(true)
        assertShowsText(button, "En")
    }

    @Test
    fun themeWithoutLanguageSlotsKeepsTheBuiltInIcon() = withIconTheme(emptyMap()) {
        val ui = ButtonsBarUi(
            context, ThemePreset.PixelLight, listOf(ConfigurableButton("language_switch"))
        )
        val button = ui.root.allViews.filterIsInstance<ToolButton>()
            .first { it.tag == "language_switch" }
        assertShowsBuiltInIcon(button)
        ui.setAsciiMode(true)
        assertShowsBuiltInIcon(button)
    }

    @Test
    fun otherButtonsIgnoreTheAsciiState() = withIconTheme(
        mapOf("toolbar.language_switch" to "中")
    ) {
        val ui = ButtonsBarUi(
            context, ThemePreset.PixelLight,
            listOf(ConfigurableButton("language_switch"), ConfigurableButton("undo"))
        )
        val undo = ui.root.allViews.filterIsInstance<ToolButton>().first { it.tag == "undo" }
        val before = undo.image.drawable
        ui.setAsciiMode(true)
        assertEquals(View.VISIBLE, undo.image.visibility)
        assertEquals(before, undo.image.drawable)
    }
}
