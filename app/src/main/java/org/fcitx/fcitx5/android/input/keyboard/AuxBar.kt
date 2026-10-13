/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

enum class AuxBarPosition { Top, Bottom, Left, Right, AbovePreedit }

data class AuxBarConfig(
    val position: AuxBarPosition,
    val sizePercent: Float
) {
    /**
     * 分体侧栏的百分比基准扣除设定中缝；其余位置/普通排列沿用原尺寸。
     * 侧栏与主键一起按整体宽度的 (1 - g) 缩放，保持普通排列里配好的宽度关系。
     */
    fun resolveSizeFraction(variant: LayoutVariant, splitGapFraction: Float): Float {
        val fraction = sizePercent / 100f
        if (!variant.isSplit || (position != AuxBarPosition.Left && position != AuxBarPosition.Right)) {
            return fraction
        }
        return fraction * (1f - splitGapFraction)
    }

    /**
     * 侧栏收窄后，主区由 (1 - p) 扩大到 (1 - p * (1 - g))。
     * 行内已有 (1 - g) 缩放，再乘此修正值，避免释放的侧栏宽度重新分给主键。
     */
    fun resolveKeyAreaScale(variant: LayoutVariant, splitGapFraction: Float): Float {
        if (!variant.isSplit || (position != AuxBarPosition.Left && position != AuxBarPosition.Right)) {
            return 1f
        }
        return (1f - sizePercent / 100f) / (1f - resolveSizeFraction(variant, splitGapFraction))
    }
}
