/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import androidx.core.view.descendants
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class PopupKeyboardCapacityTest {
    @Test fun everyOptionIsRenderedAndReachableEvenWhenTheLastRowIsPartial() {
        for (count in listOf(1, 5, 6, 13, 17, 21, 22, 64)) {
            val values = Array(count) { "item$it" }
            val popup = PopupKeyboardUi(RuntimeEnvironment.getApplication(), ThemeManager.DefaultTheme,
                Rect(0, 0, 1080, 800), Rect(400, 500, 450, 550), radius = 0f,
                keyWidth = 50, keyHeight = 50, popupHeight = 50, keys = values, labels = values)
            val rendered = popup.root.descendants.filterIsInstance<AutoScaleTextView>()
                .map { it.text.toString() }.toSet()
            assertEquals("Rendered keys for count=$count", values.toSet(), rendered)
            val rows = ceil(count / 5f).toInt()
            val columns = ceil(count.toFloat() / rows).toInt()
            val reachable = mutableSetOf<String>()
            for (row in 0 until rows) for (column in 0 until columns) {
                popup.onChangeFocus((column + .5f) * 50, (rows - row + .2f) * 50)
                val action = popup.onTrigger()
                if (action is KeyAction.CommitAction) reachable += action.text
            }
            assertEquals("Reachable keys for count=$count", values.toSet(), reachable)
        }
    }
}
