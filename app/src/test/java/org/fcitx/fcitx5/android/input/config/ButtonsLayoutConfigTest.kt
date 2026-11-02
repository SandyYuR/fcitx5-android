/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ButtonsLayoutConfigTest {
    @Test fun removingTheDayNightControlSurvivesSavingAndReloading() {
        val config = ButtonsLayoutConfig.default().let { original ->
            original.copy(kawaiiBarButtons = original.kawaiiBarButtons.filterNot { it.id == "theme_toggle" })
        }
        val restored = Json.decodeFromString<ButtonsLayoutConfig>(Json.encodeToString(config))
        assertEquals(listOf("undo", "redo", "cursor_move", "floating_toggle", "clipboard"),
            restored.displayedKawaiiBarButtons().map { it.id })
    }

    @Test fun movingTheDayNightControlToStatusDoesNotCreateAnotherToolbarIcon() {
        val config = ButtonsLayoutConfig.default().let { original ->
            original.copy(kawaiiBarButtons = original.kawaiiBarButtons.filterNot { it.id == "theme_toggle" },
                statusAreaButtons = original.statusAreaButtons + ConfigurableButton("theme_toggle", text = "☀"))
        }
        val restored = Json.decodeFromString<ButtonsLayoutConfig>(Json.encodeToString(config))
        assertFalse(restored.displayedKawaiiBarButtons().any { it.id == "theme_toggle" })
        assertEquals(1, restored.statusAreaButtons.count { it.id == "theme_toggle" })
        assertEquals("☀", restored.statusAreaButtons.last().text)
    }

    @Test fun newDefaultsIncludeTheControlAndLegacyMoreDoesNotReplaceSavedButtons() {
        assertEquals(1, ButtonsLayoutConfig.default().displayedKawaiiBarButtons().count { it.id == "theme_toggle" })
        val configured = listOf(ConfigurableButton("clipboard", text = "剪"), ConfigurableButton("theme_toggle", text = "☀"))
        val config = ButtonsLayoutConfig(configured + ConfigurableButton("more"), emptyList())
        assertEquals(configured, config.displayedKawaiiBarButtons())
        assertTrue(config.copy(kawaiiBarButtons = emptyList()).displayedKawaiiBarButtons().isEmpty())
    }
}
