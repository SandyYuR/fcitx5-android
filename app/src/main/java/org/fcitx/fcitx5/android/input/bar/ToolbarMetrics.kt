/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import kotlin.math.roundToInt

/**
 * 工具栏（Kawaii Bar）自身尺寸的缩放：一个百分比只放大工具栏与它内部的按钮/图标/标签。
 *
 * ## 为什么需要它
 *
 * 工具栏高度长期是写死的 40dp（[KawaiiBarComponent.HEIGHT]），用户想让它更高更醒目
 * 只能靠改代码。这个百分比就是把高度交出去：栏变高，栏里的按钮、图标、状态区一起变大。
 *
 * ## 候选项字号**不在**这个模块的职责内
 *
 * 候选字由「字体设定」（`fontset.json` 的 `cand_font`）独立控制，那是用户为「字多大」
 * 专门准备的旋钮。早期版本曾让工具栏百分比同时放大候选字，结果是：
 * 用户只想要一条高一点的栏，字却被顺手放大，还得回头去调字体设定抵消；
 * 而真正想放大字的人去调字体设定时又只能放大到栏高封顶（`AutoScaleTextView` 的
 * Proportional 模式**只缩不放**）。两个旋钮互相干扰，属于设计错误。
 *
 * 因此：**放大工具栏不改变任何候选文字的字号与候选格子的尺寸**。想让候选字更大，
 * 请走高一点的工具栏 + 「字体设定」调大 `cand_font`。
 *
 * ## 关键约束：横向空间不随百分比变化
 *
 * 这是本模块所有取整与封顶的出发点。工具栏**只有高度**受这个百分比控制；宽度永远是屏幕
 * 宽度减去两侧边距。因此图标不能无限放大：
 *
 * - 中间那一行按钮的总宽 = 按钮个数 × 足迹。足迹一旦跟着百分比长大，8 个按钮很快就
 *   不再满足「平分宽度」的条件，整行退回横向滚动，用户只看得见 3 个
 *   （这就是初版 200% 时只剩 3 个图标的原因）。
 * - 所以 **足迹恒为 [BUTTON_FOOTPRINT_DP]，不随百分比变化**；图标长大多少，
 *   四周留白就等量减少多少。图标因此只是在同一块格子里"填满"，不会挤走别的按钮。
 *
 * 这里只做**纯数值换算**，不碰 Android 类型，因此可以脱离设备在 JVM 单测里钉住
 * （见 `ToolbarMetricsTest`）。dp/sp 换算由调用方各自完成。
 */
object ToolbarMetrics {

    /** 工具栏基准高度，对齐历史上写死的 40dp。 */
    const val BASE_HEIGHT_DP = 40

    /**
     * 100% 时的有效图标绘制边长。
     *
     * 内置 vector 的 intrinsic 通常是 24dp，但旧实现的 ImageView 高度会被 40dp 工具栏
     * 约束，减去上下各 10dp 留白后实际绘制为 20dp；这里保留这个有效尺寸，才能让新
     * 的显式方框在 100% 时与旧实现逐像素一致。
     */
    const val BASE_ICON_DP = 20

    /** 基准图标留白（原实现里 `ToolButton.image` 的固定 10dp）。 */
    const val BASE_ICON_PADDING_DP = 10

    /**
     * 一个工具栏按钮的横向格子宽度（dp）：旧实现的按钮足迹为 44dp；新实现的 100% 图标
     * 方框为 40dp（20dp 有效图标 + 两侧各 10dp 留白），方框外仍保留 4dp 的横向余量。
     *
     * **不随百分比变化**——这是「调大工具栏不会让可见按钮个数骤减」的根本保证。
     */
    const val BUTTON_FOOTPRINT_DP = 44

    /**
     * 中间按钮行的最小按钮宽度（dp），行布局用它决定「平分宽度」还是「横向滚动」。
     *
     * 取 40 而不是 [BUTTON_FOOTPRINT_DP]：这是改动前就有的阈值，动它会让窄屏上
     * 100% 的默认外观也跟着变化。**不随百分比变化**才是重点——阈值若跟着长大，
     * 8 个按钮很快就满足不了，整行退回横向滚动，用户只看得见 3 个。
     */
    const val MIN_BUTTON_WIDTH_DP = 40

    /** 图标与按钮边缘的最小间隙，避免图案紧贴边缘。 */
    const val MIN_ICON_GAP_DP = 2

    /** 文字型工具栏按钮的基准字号（原实现用 16dip）。 */
    const val BASE_LABEL_SIZE = 16f

    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 200
    const val DEFAULT_PERCENT = 100

    /** 百分比一律夹到 [MIN_PERCENT]..[MAX_PERCENT]，越界值（含脏数据）不会放大出界。 */
    fun clampPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /**
     * 百分比 → 缩放系数，范围 0.8f..2.0f。
     *
     * 这是工具栏内**部件**（返回箭头、展开按钮、状态区/隐藏键盘按钮、文字标签）的系数：
     * 栏变矮时它们必须跟着缩，否则会互相挤压甚至被裁掉。
     *
     * **不适用于候选项**：候选字由「字体设定」控制，工具栏百分比不碰它（见类注释）。
     */
    fun scale(percent: Int): Float = clampPercent(percent) / 100f

    /** 工具栏高度（dp）。100% 时等于 [BASE_HEIGHT_DP]。 */
    fun heightDp(percent: Int): Int =
        (BASE_HEIGHT_DP * scale(percent)).roundToInt().coerceAtLeast(1)

    /**
     * 图标实际画多大（dp）。
     *
     * 取「百分比算出的期望尺寸」与「格子宽度减去两侧最小间隙」的较小值：
     *
     * | 百分比 | 图标 | 留白 | 合计 |
     * |---|---|---|---|
     * | 100% | 20dp 有效图标 | 10dp | 40dp 图标方框（44dp 按钮足迹）|
     * | 200% | 40dp | 2dp | 44dp |
     *
     * 上限存在的原因是**横向空间不随百分比变化**：不封顶的话 200% 会得到 48dp 图标，
     * 顶破 44dp 格子，中间那一行被撑到放不下，整行退回横向滚动、可见按钮个数骤减
     * （初版 200% 时只剩 3 个就是这么来的）。
     *
     * @param slotDp 这个按钮的格子宽度；默认取中间按钮行的 [BUTTON_FOOTPRINT_DP]，
     *   工具栏两侧的方形按钮也传同一个值，图标尺寸因此与中间行一致。
     */
    fun iconSizeDp(percent: Int, slotDp: Int = BUTTON_FOOTPRINT_DP): Int {
        val desired = (BASE_ICON_DP * scale(percent)).roundToInt()
        val limit = (slotDp - MIN_ICON_GAP_DP * 2).coerceAtLeast(1)
        return desired.coerceAtMost(limit).coerceAtLeast(1)
    }

    /**
     * 图标四周留白（dp）。
     *
     * 取「按百分比缩放的留白」与「格子分完图标后剩下的空间」的较小值，并保底
     * [MIN_ICON_GAP_DP]。100% 时正好是原来的 10dp；200% 时被压到 2dp，
     * 把省下的横向空间全让给图标——**格子总宽始终 44dp**，按钮个数因此不变。
     */
    fun iconPaddingDp(percent: Int, slotDp: Int = BUTTON_FOOTPRINT_DP): Int {
        val desired = (BASE_ICON_PADDING_DP * scale(percent)).roundToInt()
        val slack = (slotDp - iconSizeDp(percent, slotDp)) / 2
        return maxOf(MIN_ICON_GAP_DP, minOf(desired, slack))
    }

    /**
     * 图标连同留白的整体方框边长（dp），即 `Image` 的 `maxWidth`/`maxHeight` 应该设的值。
     *
     * 必须是**方框**而不是图标本身：[ImageView] 在 `wrap_content` 下按
     * 「intrinsic + padding」测量，若把 `maxWidth` 直接设成图标尺寸，留白会被算进这个
     * 上限里，真正的图案反而被压到几乎看不见。
     */
    fun iconBoxDp(percent: Int, slotDp: Int = BUTTON_FOOTPRINT_DP): Int =
        iconSizeDp(percent, slotDp) + iconPaddingDp(percent, slotDp) * 2
}
