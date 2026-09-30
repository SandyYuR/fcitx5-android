/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class TextEditingStyle(override val stringRes: Int) : ManagedPreferenceEnum {
    Default(R.string.text_editing_style_default),
    FoxySwipe(R.string.text_editing_style_foxy_swipe)
}
