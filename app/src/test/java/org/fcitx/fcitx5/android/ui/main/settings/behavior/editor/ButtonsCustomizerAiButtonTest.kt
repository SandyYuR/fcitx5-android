/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.recyclerview.widget.RecyclerView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fcitx.fcitx5.android.input.config.ConfigProvider
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ButtonsCustomizerActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ButtonsCustomizerActivity.ListItem
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ButtonsCustomizerActivity.Section
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowPopupMenu
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Go through the actual settings add controls, not only the runtime action registry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], qualifiers = "zh-rCN", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class ButtonsCustomizerAiButtonTest {
    private lateinit var original: ConfigProvider
    private lateinit var file: File
    private val controllers = mutableListOf<ActivityController<ButtonsCustomizerActivity>>()

    @Before fun setUp() {
        file = File(RuntimeEnvironment.getApplication().filesDir, "ai-toolbar-test/ButtonsLayout.json")
        file.parentFile!!.mkdirs()
        file.writeText(Json.encodeToString(ButtonsLayoutConfig.default()))
        original = ConfigProviders.provider
        ConfigProviders.provider = object : ConfigProvider {
            override fun textKeyboardLayoutFile(): File? = null
            override fun textKeyboardLayoutJson() = JsonObject(emptyMap())
            override fun popupPresetFile(): File? = null
            override fun fontsetFile(): File? = null
            override fun buttonsLayoutConfigFile() = file
            override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> =
                Result.failure(UnsupportedOperationException())
        }
    }

    @After fun tearDown() {
        ShadowPopupMenu.getLatestPopupMenu()?.dismiss()
        ShadowDialog.getLatestDialog()?.dismiss()
        controllers.reversed().forEach { it.pause().stop().destroy() }
        ConfigProviders.provider = original
    }

    private fun open() = Robolectric.buildActivity(ButtonsCustomizerActivity::class.java)
        .setup().also { controllers += it }.get()

    private fun items(activity: ButtonsCustomizerActivity) =
        ReflectionHelpers.getField<List<ListItem>>(activity, "items")

    private fun layout(activity: ButtonsCustomizerActivity) {
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun card(activity: ButtonsCustomizerActivity, predicate: (ListItem) -> Boolean): View {
        val position = items(activity).indexOfFirst(predicate)
        assertTrue("The settings page must expose the requested add control", position >= 0)
        val recycler = ReflectionHelpers.callInstanceMethod<RecyclerView>(activity, "getRecyclerView")
        recycler.scrollToPosition(position)
        layout(activity)
        val holder = recycler.findViewHolderForAdapterPosition(position)
        assertNotNull("The add control must be bound to an actual settings view", holder)
        return holder!!.itemView
    }

    private fun addFromPlus(section: Section) {
        val activity = open()
        assertEquals("AI 助手", activity.getString(R.string.ai_assistant))
        card(activity) {
            if (section == Section.KawaiiBar) it is ListItem.AddButtonPlaceholder
            else it is ListItem.StatusAreaAddButtonPlaceholder
        }.performClick()
        val popup = ShadowPopupMenu.getLatestPopupMenu()
        assertNotNull(popup)
        val ai = (0 until popup.menu.size()).map { popup.menu.getItem(it) }
            .singleOrNull { it.title == activity.getString(R.string.ai_assistant) }
        assertNotNull("AI assistant must appear in the actual + menu", ai)
        assertTrue(shadowOf(popup).onMenuItemClickListener.onMenuItemClick(ai!!))
        popup.dismiss()
        saveAndReopen(activity, section)
    }

    private fun saveAndReopen(activity: ButtonsCustomizerActivity, section: Section) {
        assertEquals(ConfigurableButton("ai_assistant"), items(activity).filterIsInstance<ListItem.ButtonItem>()
            .single { it.button.id == "ai_assistant" && it.section == section }.button)
        assertFalse(items(activity).filterIsInstance<ListItem.AddButtonItem>().any { it.buttonDef.id == "ai_assistant" })
        val menu = MenuBuilder(activity)
        activity.onCreateOptionsMenu(menu)
        assertTrue(activity.onOptionsItemSelected(menu.getItem(0)))
        val saved = Json.decodeFromString<ButtonsLayoutConfig>(file.readText())
        val target = if (section == Section.KawaiiBar) saved.kawaiiBarButtons else saved.statusAreaButtons
        assertEquals(1, target.count { it.id == "ai_assistant" })
        val reopened = open()
        assertEquals(1, items(reopened).filterIsInstance<ListItem.ButtonItem>()
            .count { it.button.id == "ai_assistant" && it.section == section })
    }

    @Test fun toolbarPlusOffersAiAndPersistsItAfterReopening() = addFromPlus(Section.KawaiiBar)

    @Test fun statusAreaPlusOffersAiAndPersistsItAfterReopening() = addFromPlus(Section.StatusArea)

    @Test fun availableAiCardLetsTheUserChooseTheToolbar() {
        val activity = open()
        card(activity) { it is ListItem.AddButtonItem && it.buttonDef.id == "ai_assistant" }.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(activity.getString(R.string.kawaii_bar_section), dialog.listView.adapter.getItem(0))
        dialog.listView.performItemClick(null, 0, 0)
        saveAndReopen(activity, Section.KawaiiBar)
    }
}
