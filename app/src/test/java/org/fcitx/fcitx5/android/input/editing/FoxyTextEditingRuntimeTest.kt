/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.editing

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.config.ConfigProvider
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.io.File

@Implements(FcitxInputMethodService::class)
class FoxyCommitServiceShadow {
    val commits = mutableListOf<String>()
    @Implementation fun commitText(text: String, cursor: Int) { commits += text }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, FoxyCommitServiceShadow::class])
class FoxyTextEditingRuntimeTest {
    private fun buttons(root: View): List<TextEditingButton> {
        val bottom = (root as ViewGroup).getChildAt(1) as ViewGroup
        fun collect(view: View): List<TextEditingButton> = when (view) {
            is TextEditingButton -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
            else -> emptyList()
        }
        return collect(bottom)
    }

    @Test fun actualWindowAlphabetClickCommitsExactlyOnceAndKeepsFractionalWidths() {
        val application = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val context = ContextThemeWrapper(application, 0)
        // Emoji probes runtime Unicode delivery; the layout editor requires a single UTF-16 character.
        val rootJson = Json.parseToJsonElement("""{"text_editor":[[
            {"type":"AlphabetKey","main":"q","alt":"!","weight":0.1},
            {"type":"AlphabetKey","main":"中","alt":"!","weight":0.15},
            {"type":"AlphabetKey","main":"😀","alt":"!","weight":0.2}
        ]]}""").jsonObject
        val previousProvider = ConfigProviders.provider
        val style = AppPrefs.getInstance().textEditing.style
        val previousStyle = style.getValue()
        try {
            ConfigProviders.provider = object : ConfigProvider {
                override fun textKeyboardLayoutFile(): File? = null
                override fun textKeyboardLayoutJson() = rootJson
                override fun popupPresetFile(): File? = null
                override fun fontsetFile(): File? = null
                override fun buttonsLayoutConfigFile(): File? = null
                override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> =
                    Result.failure(UnsupportedOperationException("unused by Foxy runtime tests"))
            }
            style.setValue(TextEditingStyle.FoxySwipe)
            assertEquals(TextEditingStyle.FoxySwipe, style.getValue())
            val layout = requireNotNull(TextEditingLayoutLoader.loadSwipeBar())
            assertEquals(listOf(0.1f, 0.15f, 0.2f), layout.rows.flatten().map { it.appearance.percentWidth })
            val theme: Theme = ThemePreset.PixelLight
            val window = TextEditingWindow()
            val scope = DynamicScope().apply {
                this += context.wrapToUniqueComponent()
                this += theme.wrapToUniqueComponent()
                this += service.wrapToUniqueComponent()
                this += window
            }
            val root = window.onCreateView()
            val ui = ReflectionHelpers.callInstanceMethod<TextEditingUi>(window, "getUi")
            assertTrue("The actual window must use FoxySwipe", ui is FoxyTextEditingUi)
            assertSame(ui.root, root)
            val keys = buttons(root)
            assertEquals(listOf("q", "中", "😀"), keys.map { it.textView.text.toString() })
            assertEquals(3, keys.size)
            keys.forEach { assertTrue(it.isEnabled); assertTrue(it.performClick()) }
            val shadow = Shadow.extract<FoxyCommitServiceShadow>(service)
            assertEquals(listOf("q", "中", "😀"), shadow.commits)
            val weights = keys.map { (it.layoutParams as LinearLayout.LayoutParams).weight }
            assertEquals(listOf(0.1f, 0.15f, 0.2f), weights)
            root.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 900, 600)
            assertTrue(keys[0].width < keys[1].width)
            assertTrue(keys[1].width < keys[2].width)
            assertNotNull(scope)
        } finally {
            style.setValue(previousStyle)
            ConfigProviders.provider = previousProvider
        }
    }
}
