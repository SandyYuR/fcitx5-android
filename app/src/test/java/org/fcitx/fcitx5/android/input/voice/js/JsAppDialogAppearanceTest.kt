/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Measure the real plugin layout with Android graphics, including the height below its label. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xhdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class JsAppDialogAppearanceTest {
    private val dark = ThemePreset.PixelDark.copy(
        backgroundColor = 0xff2c211e.toInt(), keyBackgroundColor = 0xff473935.toInt(),
        keyTextColor = 0xffece0dd.toInt(), popupBackgroundColor = 0xff362b28.toInt(),
        popupTextColor = 0xffece0dd.toInt(), accentKeyBackgroundColor = 0xffffb4a9.toInt(),
        accentKeyTextColor = 0xff561e17.toInt())

    private fun captureAndCheck(theme: Theme, name: String, result: Boolean = false) = runBlocking {
        ReflectionHelpers.setStaticField(ThemeManager::class.java, "_activeTheme", theme)
        val service = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        val js = JsVoiceRuntime.create()
        var dialog: JsAppDialog? = null
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE + "\n" +
                File("src/main/assets/bundled/js-apps/llm-assistant.js").readText())
            val spec = js.evaluate<String>("""
                currentProvider = "custom";
                activeSettings = {customUrl: "https://relay.example/v1", customModel: "model-x", customProtocol: "responses"};
                activeSecrets = ["customKey"];
                lastResult = ${if (result) "\"这是 AI 处理后的回复，可以在面板内阅读，确认后再上屏。\"" else "\"\""};
                JSON.stringify(dialogSpec(stringsFor("zh-CN"), "LLM 助手", "polish", "你好，这是需要润色的内容。"));
            """.trimIndent())
            dialog = JsAppDialog(service, { _, _ -> }, { service.jsAppInputConnection = null })
            dialog.render(Json.parseToJsonElement(spec).jsonObject)
            val content = dialog.findViewById<ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0)
            val density = service.resources.displayMetrics.density
            root.measure(View.MeasureSpec.makeMeasureSpec((340 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.AT_MOST))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            System.getenv("AI_DIALOG_PREVIEW_PREFIX")?.let { prefix ->
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(theme.backgroundColor)
                root.draw(canvas)
                File("$prefix-$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val edit = descendants(root).filterIsInstance<EditText>().single { it.hint?.toString() == "输入要处理的内容" }
            assertTrue("The declared input height must not include the label above it",
                edit.height >= ((if (result) 36 else 68) * density).toInt())
            if (result) {
                val reply = descendants(root).filterIsInstance<EditText>().single { it.keyListener == null }
                assertTrue(reply.height >= (96 * density).toInt())
                assertTrue(reply.text.toString().startsWith("这是 AI"))
            }
            assertTrue(root.height <= (360 * density).toInt())
        } finally { dialog?.dismiss(); js.close() }
    }

    @Test fun lightThemeKeepsTheDeclaredEditorHeight() = captureAndCheck(ThemePreset.PixelLight, "light")
    @Test fun darkThemeKeepsTheDeclaredEditorHeight() = captureAndCheck(dark, "dark")

    @Test fun lightReplyIsVisibleAndKeepsTheFooterWithinBounds() = captureAndCheck(ThemePreset.PixelLight, "light-result", true)
    @Test fun darkReplyIsVisibleAndKeepsTheFooterWithinBounds() = captureAndCheck(dark, "dark-result", true)

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()
}
