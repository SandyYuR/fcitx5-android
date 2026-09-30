/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** 编辑器底部操作键的声明，避免 Foxy 风格把操作布局硬编码成一串 View。 */
data class TextEditingKeySpec(
    @StringRes val labelRes: Int? = null,
    @StringRes val contentDescriptionRes: Int? = null,
    val iconSlot: String? = null,
    @DrawableRes val fallbackIconRes: Int? = null,
    val altStyle: Boolean = true
)
