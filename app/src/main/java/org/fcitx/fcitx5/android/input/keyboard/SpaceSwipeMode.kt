/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * 空格键划动手势的归属判定。
 *
 * 空格键是唯一一个手势语义被设置开关接管的键，两种模式互斥：
 *
 * 1. **光标模式**——「划动空格键以移动光标」（`spaceSwipeMoveCursor`）开启：横向/纵向
 *    划动移动光标，按键自身配置的划动动作不生效。
 * 2. **划动动作模式**——开关关闭且按键配了划动动作：划动触发该动作。
 *
 * 两者都不成立时不参与划动手势（保持 `swipeEnabled = false`），这样手指在空格键上
 * 移动不会取消长按——语音输入的「按住说话」依赖长按判定，误取消会让录音半途中断。
 *
 * 抽成纯函数是为了让这条优先级规则可以被单元测试直接覆盖：它是本功能里最容易在
 * 后续改动中被改反的一处，而 `BaseKeyboard` 依赖 `AppPrefs`/`Context`，无法在 JVM
 * 测试里构造。
 */
internal fun resolveSpaceSwipeEnabled(
    cursorSwipeEnabled: Boolean,
    swipeAction: MacroAction?
): Boolean = cursorSwipeEnabled || swipeAction != null
