/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import androidx.appcompat.view.menu.MenuBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fcitx.fcitx5.android.input.config.ConfigProvider
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.displayedKawaiiBarButtons
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ButtonsCustomizerActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class ButtonsCustomizerPersistenceTest {
    @Test fun savingThenReopeningDoesNotRestoreTheRemovedDayNightControl() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "toolbar-test/config/ButtonsLayout.json")
        file.parentFile!!.mkdirs()
        file.writeText(Json.encodeToString(ButtonsLayoutConfig.default()))
        val original = ConfigProviders.provider
        ConfigProviders.provider = object : ConfigProvider {
            override fun textKeyboardLayoutFile(): File? = null
            override fun textKeyboardLayoutJson() = JsonObject(emptyMap())
            override fun popupPresetFile(): File? = null
            override fun fontsetFile(): File? = null
            override fun buttonsLayoutConfigFile() = file
            override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> =
                Result.failure(UnsupportedOperationException())
        }
        try {
            val first = Robolectric.buildActivity(ButtonsCustomizerActivity::class.java).setup()
            try {
                val items = ReflectionHelpers.getField<MutableList<ButtonsCustomizerActivity.ListItem>>(first.get(), "items")
                val index = items.indexOfFirst { it is ButtonsCustomizerActivity.ListItem.ButtonItem && it.button.id == "theme_toggle" }
                val button = items[index] as ButtonsCustomizerActivity.ListItem.ButtonItem
                items[index] = button.copy(section = ButtonsCustomizerActivity.Section.AddButtons)
                val menu = MenuBuilder(first.get())
                first.get().onCreateOptionsMenu(menu)
                first.get().onOptionsItemSelected(menu.getItem(0))
            } finally { first.pause().stop().destroy() }
            val saved = Json.decodeFromString<ButtonsLayoutConfig>(file.readText())
            assertFalse(saved.displayedKawaiiBarButtons().any { it.id == "theme_toggle" })
            val reopened = Robolectric.buildActivity(ButtonsCustomizerActivity::class.java).setup()
            try {
                val items = ReflectionHelpers.getField<List<ButtonsCustomizerActivity.ListItem>>(reopened.get(), "items")
                assertFalse(items.filterIsInstance<ButtonsCustomizerActivity.ListItem.ButtonItem>().any { it.button.id == "theme_toggle" })
                assertEquals(1, items.filterIsInstance<ButtonsCustomizerActivity.ListItem.AddButtonItem>().count { it.buttonDef.id == "theme_toggle" })
            } finally { reopened.pause().stop().destroy() }
        } finally { ConfigProviders.provider = original }
    }
}
