/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * 悬浮键盘移动把手的摆放决策。
 *
 * 背景（用户反馈）：移动把手平时居中悬在键盘**顶部之上**（`keyboardY - 把手高 - 间距`）。
 * 当键盘被拖到太靠近屏幕顶部时，把手整体跑到 InputView 之外——手指松开后看到的
 * 键盘再也拖不动（把手点不到），只能退出悬浮重进。
 *
 * 决策：当把手顶部空间不足时，把把手翻到键盘**底部之下**；底部也没空间时
 * （键盘几乎占满整个高度的极端情形），退回顶部，保证行为与旧版本一致而不是乱跳。
 *
 * 这是一个纯函数（无 Android 依赖），方便 JVM 单测覆盖翻转/迟滞逻辑。
 */
object FloatingMoveHandlePlacement {

    /** 把手摆在键盘上方。 */
    const val ABOVE = 0

    /** 把手摆在键盘下方。 */
    const val BELOW = 1

    /**
     * 判断移动把手应该摆在键盘上方还是下方。
     *
     * @param keyboardTop 键盘顶部相对 InputView 的 Y（即 keyboardView.translationY）。
     * @param keyboardHeight 键盘总高度（含工具栏与底部边距）。
     * @param containerHeight InputView 高度（悬浮时为 matchParent，即整屏可用高度）。
     * @param needAbove 把手摆在上方时，键盘顶部上方需要的最小空间（把手高 + 间距）。
     * @param needBelow 把手摆在下方时，键盘底部下方需要的最小空间（把手高 + 间距
     *   + 底部缩放手柄触摸区的一半，避让两者触摸区重叠）。
     * @param currentlyAbove 当前把手是否在上方；传入 null 表示首次摆放（无迟滞偏好）。
     * @return [ABOVE] 或 [BELOW]。
     *
     * 迟滞规则：两侧都放得下、或两侧都放不下时，都保持现状（首次摆放则回落到
     * 上方，与旧行为一致），避免键盘在临界高度附近拖动时把手来回横跳。
     */
    fun placement(
        keyboardTop: Float,
        keyboardHeight: Float,
        containerHeight: Float,
        needAbove: Float,
        needBelow: Float,
        currentlyAbove: Boolean?
    ): Int {
        val aboveFits = keyboardTop >= needAbove
        val belowFits = keyboardTop + keyboardHeight + needBelow <= containerHeight
        return when {
            aboveFits && belowFits -> if (currentlyAbove == false) BELOW else ABOVE
            aboveFits -> ABOVE
            belowFits -> BELOW
            // 两侧都放不下：保持现状；首次摆放回落到上方（旧行为）。
            else -> if (currentlyAbove == false) BELOW else ABOVE
        }
    }
}
