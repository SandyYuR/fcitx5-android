/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.preview

import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode

object PreviewInputMethodEntry {

    /**
     * 设置页预览的兜底布局名。
     *
     * 布局文件按输入法名分节（本应用是 "rime"），真实输入法尚未上报时也要落在这套分节
     * 上，不能退回内置 QWERTY，否则布局里逐键的 backgroundColor 会全部丢失。
     */
    const val PREVIEW_FALLBACK_LAYOUT = "rime"

    fun create(
        layoutName: String = "Preview",
        subModeLabel: String? = null,
        base: InputMethodEntry? = null
    ): InputMethodEntry {
        val selectedSubModeLabel = subModeLabel?.trim().orEmpty()
        return base
            ?.copy(
                uniqueName = layoutName,
                name = layoutName,
                subMode = if (selectedSubModeLabel.isNotEmpty()) {
                    base.subMode.copy(
                        label = selectedSubModeLabel,
                        name = selectedSubModeLabel
                    )
                } else {
                    base.subMode.copy(
                        name = "",
                        label = "",
                        icon = ""
                    )
                }
            )
            ?: InputMethodEntry(layoutName).copy(
                // 没有真实输入法可复制时也要带上子布局标签：预览 entry 只靠 subMode.label
                // 定位子布局，丢掉它会让「层：数字」这类目标退回基线排列。
                subMode = if (selectedSubModeLabel.isNotEmpty()) {
                    InputMethodSubMode(selectedSubModeLabel, selectedSubModeLabel, "")
                } else InputMethodSubMode()
            )
    }

    /**
     * 设置页预览键盘该用的布局名。
     *
     * [create] 的默认名 "Preview" 在任何布局文件里都没有对应分节，预览会一路回退到内置
     * QWERTY，布局里逐键的 backgroundColor 全部丢失，看起来就是"预览只套了主题统一底色"。
     * 主题页、分体键盘校准页这些"照搬真实键盘外观"的预览必须画用户真正在用的那套布局，
     * 所以跟随真实输入法的名字（[org.fcitx.fcitx5.android.input.keyboard.TextKeyboard.ime]）。
     */
    fun resolveRealLayoutName(realIme: InputMethodEntry?): String =
        realIme?.uniqueName?.takeIf { it.isNotBlank() } ?: PREVIEW_FALLBACK_LAYOUT

    /**
     * 按真实输入法布局创建的预览 IME：布局名跟随真实输入法，子模式清空以取该分节的默认排列。
     *
     * 布局名只用于查布局分节；空格键显示的是输入法名，不能被它顶替成 "rime"。
     * 真实输入法尚未上报时，[InputMethodEntry.uniqueName] 也要显式置成布局分节名：
     * 查布局先看 uniqueName 再看 displayName，两者都不等于分节名就会退回内置 QWERTY。
     */
    fun createRealLayoutPreview(realIme: InputMethodEntry?): InputMethodEntry {
        val layoutName = resolveRealLayoutName(realIme)
        val preview = create(layoutName = layoutName, base = realIme)
        if (realIme != null) return preview.copy(name = realIme.name)
        // 布局分节名是内部 id（"rime"），直接当空格键文案会像未翻译的英文残留；
        // 首字母大写让它看起来是产品名，查布局仍靠 uniqueName。
        return preview.copy(
            uniqueName = layoutName,
            name = layoutName.replaceFirstChar { it.uppercaseChar() }
        )
    }
}
