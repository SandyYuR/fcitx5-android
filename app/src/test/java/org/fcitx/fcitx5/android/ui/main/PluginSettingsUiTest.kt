/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceField
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceFieldOption
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceProviders
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.DataBackupFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.ui.main.settings.voice.JsVoicePluginSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.voice.JsVoicePluginsFragment
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.After
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

internal class UiPolishHostActivity : SettingsUiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = R.id.settings_container })
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class PluginSettingsUiTest {
    private val controller = Robolectric.buildActivity(UiPolishHostActivity::class.java).setup()
    private val activity = controller.get()
    @After fun close() { controller.pause().stop().destroy() }

    private fun show(fragment: androidx.fragment.app.Fragment) {
        activity.supportFragmentManager.beginTransaction().replace(R.id.settings_container, fragment).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun capture(name: String) {
        val root = activity.findViewById<View>(R.id.settings_container)
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 700)
            shadowOf(Looper.getMainLooper()).idle()
        }
        System.getenv("UI_POLISH_PREVIEW_DIR")?.let { directory ->
            val bitmap = Bitmap.createBitmap(360, 700, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(activity.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
            root.draw(canvas)
            File(directory, "$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun pluginRefreshReusesUnchangedRowsAndShowsCapabilities() {
        val fragment = JsVoicePluginsFragment()
        show(fragment)
        val screen = fragment.preferenceScreen
        val first = screen.getPreference(1)
        ReflectionHelpers.callInstanceMethod<Unit>(fragment, "rebuild")
        assertSame(first, screen.getPreference(1))
        assertTrue((0 until screen.preferenceCount).map { screen.getPreference(it).summary.toString() }
            .any { it.contains(activity.getString(R.string.ui_plugin_voice)) })
        capture("plugins")
    }

    @Test fun groupedSettingsKeepConditionalCredentialsAndImmediateChanges() {
        val fragment = JsVoicePluginSettingsFragment.newInstance(JsVoiceProviders.QWEN_ASR_ID)
        show(fragment)
        // Wait for the real, local bundled script description before exercising a declared form.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (fragment.preferenceScreen.findPreference<Preference>("js_voice_placeholder") != null && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertNull(fragment.preferenceScreen.findPreference<Preference>("js_voice_placeholder"))
        val fields = listOf(
            JsVoiceField("select", "ui_test_mode", "连接方式", "选择当前服务的鉴权方式", "plain",
                options = listOf(JsVoiceFieldOption("免密", "plain"), JsVoiceFieldOption("API 密钥", "key"))),
            JsVoiceField("text", "ui_test_model", "模型", "", "通用识别模型"),
            JsVoiceField("secret", "ui_test_key", "API 密钥", "由宿主加密保存", "",
                visibleWhen = Json.parseToJsonElement("""{"key":"ui_test_mode","value":"key"}""").jsonObject)
        )
        ReflectionHelpers.callInstanceMethod<Unit>(fragment, "render", ReflectionHelpers.ClassParameter.from(List::class.java, fields))
        val screen = fragment.preferenceScreen
        val secret = screen.findPreference<EditTextPreference>("ui_test_key")!!
        val category = secret.parent as PreferenceCategory
        assertFalse(secret.isVisible)
        assertFalse(category.isVisible)
        val mode = screen.findPreference<ListPreference>("ui_test_mode")!!
        assertTrue(mode.callChangeListener("key"))
        assertTrue(mode.summary.toString().contains("API 密钥"))
        assertTrue(secret.isVisible)
        assertTrue(category.isVisible)
        assertEquals("", secret.text)
        assertFalse(secret.isPersistent)
        assertNotSame(mode.parent, category)
        capture("plugin-settings")
        assertTrue(mode.callChangeListener("plain"))
        assertFalse(category.isVisible)
    }

    @Test fun backupResultStaysVisibleAndDetailsRemainExplicit() {
        val fragment = DataBackupFragment()
        show(fragment)
        val groups = (0 until fragment.preferenceScreen.preferenceCount).map { fragment.preferenceScreen.getPreference(it) }
        val wanxiang = groups.filterIsInstance<PreferenceCategory>().single { it.title == activity.getString(R.string.wanxiang_group) }
        assertEquals(2, wanxiang.preferenceCount)
        assertEquals(activity.getString(R.string.wanxiang_update), wanxiang.getPreference(0).title)
        assertEquals(activity.getString(R.string.scheme_update_title), wanxiang.getPreference(1).title)
        assertSame(wanxiang, wanxiang.getPreference(0).parent)
        ReflectionHelpers.callInstanceMethod<Unit>(fragment, "showOperation",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, R.string.user_data_exported),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        val result = fragment.preferenceScreen.getPreference(1)
        assertEquals(activity.getString(R.string.user_data_exported), result.title)
        assertFalse(result.isSelectable)
        capture("backup")
        ReflectionHelpers.callInstanceMethod<Unit>(fragment, "showOperation",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, R.string.ui_operation_failed),
            ReflectionHelpers.ClassParameter.from(String::class.java, "The selected destination could not be written."),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        assertTrue(result.isSelectable)
        assertEquals(activity.getString(R.string.ui_operation_details), result.summary)
    }
}
