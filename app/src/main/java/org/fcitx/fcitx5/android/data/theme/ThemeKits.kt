/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

/**
 * 成套主题：一个主题 + 配套的键盘布局 + 配套的图标主题，外加可选的按键副字体。
 *
 * 关联按主题名登记在代码里，而不是写进主题 JSON：布局、图标主题与字体是各自独立的文件，
 * 导出/分享主题时它们不会跟着走，写进 JSON 只会得到一条指向不存在资源的悬空引用。
 * 主题被用户复制或改名后不再成套，这也是期望的行为——副本不是内置套装。
 */
object ThemeKits {

    data class Kit(
        /** 键盘布局 profile 名，即 `TextKeyboardLayout.<名称>.json` 里的 `<名称>`。 */
        val layoutProfile: String,
        /** 图标主题名，即 `icon_themes/<名称>.json` 里的 `<名称>`。 */
        val iconTheme: String,
        /**
         * 配套的按键副字体，即 `fonts/` 下的文件名（与「字体设定」里列出的名字一致）。
         * 为 null 表示这套主题不改动用户的字体设定。
         */
        val keyAltFont: String? = null,
        /**
         * 这套主题要求的操作按键形状，对应「使用 Gboard 风格的操作按键」偏好。
         * 为 null 表示这套主题不改动用户的选择。
         */
        val specialKeyOvalShape: Boolean? = null
    )

    private val kits = mapOf(
        // 副字体是水彩自带的图标字体：布局里用单字（全/剪/复/贴…）当副标签时直接显示成图标。
        "水彩" to Kit(
            layoutProfile = "水彩",
            iconTheme = "水彩",
            keyAltFont = "字体图标.otf",
            specialKeyOvalShape = false
        ),
        // 普蓝（原「蓝狐狸」）与水彩共用同一份图标字体（两包字体文件 sha256 相同）。
        // 普蓝在 foxy 上的操作键是圆角矩形，默认的胶囊形会让回车与退格变成圆/椭圆。
        "普蓝" to Kit(
            layoutProfile = "普蓝",
            iconTheme = "普蓝",
            keyAltFont = "字体图标.otf",
            specialKeyOvalShape = false
        )
    )

    /** 该主题的配套资源；为 null 表示它只是一套配色。 */
    fun kitOf(theme: Theme): Kit? = kits[theme.name]
}
