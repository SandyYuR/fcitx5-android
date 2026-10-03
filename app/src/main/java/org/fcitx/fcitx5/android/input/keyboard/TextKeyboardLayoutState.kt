/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.InputMethodEntry

/**
 * Layout state owned by one [TextKeyboard] instance.
 *
 * Layout resolution used to read the shared `TextKeyboard.ime` companion field, so any
 * instance receiving an input method update — notably the keyboard previews in settings —
 * replaced the layout context of the real keyboard. Every keyboard now resolves its layout
 * and its aux bar from its own state, while the companion field keeps mirroring the real
 * input method for window-level callers such as keyboard height resolution.
 */
internal class TextKeyboardLayoutState(
    var ime: InputMethodEntry? = null
) {
    /**
     * 本次布局要用的排列（普通 / 分体），由 [BaseKeyboard] 每趟布局开始时写入，
     * 见 `BaseKeyboard.reloadLayout`。
     *
     * 放在实例状态里而不是读全局：设置页的预览键盘可能被强制成另一种排列（用户正在编辑
     * 的正是那一份），它绝不能顺手把真实键盘的排列也改掉。
     */
    var variant: LayoutVariant = LayoutVariant.Docked

    /** Aux bar config resolved by the last layout pass of this keyboard. */
    var auxBarConfig: AuxBarConfig? = null

    /** Raw aux bar keys resolved by the last layout pass of this keyboard. */
    var auxBarKeys: List<Map<String, Any?>> = emptyList()

    fun getLayout(variant: LayoutVariant): List<List<KeyDef>> {
        this.variant = variant
        return TextKeyboard.getLayout(this)
    }

    fun getAuxBarKeyDefs(): List<KeyDef> = TextKeyboard.getAuxBarKeyDefs(this)
}
