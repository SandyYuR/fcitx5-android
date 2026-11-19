/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 布局管理页读入颜色时与运行时、键编辑器保持同一套字面量语义。
 *
 * 这里曾是第三份手写的十六进制解析：它只把十六进制位转成整数，六位 #RRGGBB 得到
 * alpha=0，管理页打开再保存后键面颜色会变成透明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class LayoutDataManagerColorTest {
    @Test
    fun layoutRowColorsKeepTheirAlpha() {
        val manager = LayoutDataManager(RuntimeEnvironment.getApplication())
        val rows = manager.parseLayoutRows(
            Json.parseToJsonElement(
                """
                [[{
                  "type": "MacroKey",
                  "backgroundColor": "#5C6B89",
                  "textColor": "#abc",
                  "shadowColor": "#8abc"
                }]]
                """.trimIndent()
            ) as JsonArray
        )
        val key = rows.single().single()
        assertEquals(0xFF5C6B89.toInt(), key["backgroundColor"])
        assertEquals(0xFFAABBCC.toInt(), key["textColor"])
        assertEquals(0x88AABBCC.toInt(), key["shadowColor"])
    }
}
