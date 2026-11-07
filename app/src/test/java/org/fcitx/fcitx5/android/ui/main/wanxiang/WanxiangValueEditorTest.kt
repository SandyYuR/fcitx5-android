/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.WanxiangYaml
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.yaml.snakeyaml.nodes.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class WanxiangValueEditorTest {
    private val y = WanxiangYaml()
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun click(editor: View, label: Int) = descendants(editor).filterIsInstance<MaterialButton>().first { it.text == editor.context.getString(label) }.performClick()
    private fun host(block: (Context) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            block(SettingsUiPreferences.themedContext(activity, SettingsUiPreferences.read(activity).copy(
                palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun nestedDraftsKeepUnconfirmedScalarTextAndLiteralMappingKeys() = host { context ->
        val original = y.document("'key/with/slash':\n  items: ['old']\n")
        val editor = WanxiangValueEditor(context, y, original)
        repeat(3) { click(editor, R.string.edit) }
        descendants(editor).filterIsInstance<TextInputEditText>().single().setText("new value")
        val restored = WanxiangValueEditor(context, y, original, editor.state())
        val result = restored.result() as MappingNode
        val nested = y.member(result, "key/with/slash")!!
        assertEquals("new value", (y.get(nested, "items/@0") as ScalarNode).value)
        assertEquals("old", (y.get(y.member(original, "key/with/slash")!!, "items/@0") as ScalarNode).value)
    }

    @Test fun editingAPagedListKeepsAllItemsOutsideTheVisiblePage() = host { context ->
        val original = y.sequence((0 until 100).map { y.string("item-$it") })
        val editor = WanxiangValueEditor(context, y, original)
        click(editor, R.string.wanxiang_advanced_next)
        click(editor, R.string.edit)
        descendants(editor).filterIsInstance<TextInputEditText>().single().setText("changed")
        val result = editor.result() as SequenceNode
        assertEquals(100, result.value.size)
        assertEquals("changed", (result.value[30] as ScalarNode).value)
        assertEquals("item-99", (result.value[99] as ScalarNode).value)
        assertEquals("item-0", (result.value[0] as ScalarNode).value)
    }

    @Test fun listMovesKeepTypesAndDoNotModifyTheOriginalDocument() = host { context ->
        val original = y.parse("['a', 2, false]") as SequenceNode
        val editor = WanxiangValueEditor(context, y, original)
        click(editor, R.string.wanxiang_advanced_up)
        val changed = editor.result() as SequenceNode
        assertEquals(listOf(Tag.INT, Tag.STR, Tag.BOOL), changed.value.map { it.tag })
        assertEquals(listOf(Tag.STR, Tag.INT, Tag.BOOL), original.value.map { it.tag })
    }

    @Test fun editingBooleanLikeTextNeverTurnsItIntoABoolean() = host { context ->
        val editor = WanxiangValueEditor(context, y, y.string("true"))
        descendants(editor).filterIsInstance<TextInputEditText>().single().setText("false")
        val result = editor.result() as ScalarNode
        assertEquals(Tag.STR, result.tag); assertEquals("false", result.value)
    }
}
