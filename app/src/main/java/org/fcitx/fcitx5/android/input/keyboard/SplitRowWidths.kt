/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * 分体键盘「断开型」行里各键的宽度分配。
 *
 * 抽成纯函数是为了能测：这段逻辑决定每一枚键有多宽，而它出问题的表现是**手感**而不是
 * 崩溃——按键被压得比预期窄、某个键忽然变得特别小，都只能靠肉眼比对，很容易被当成
 * "主题/分辨率问题"糊过去。这里只依赖数字，可以用单测把不变量钉住。
 *
 * ## 中缝对齐（alignHalves）
 *
 * 分体键盘的一个固有矛盾：**中缝必须够宽**（两根拇指要伸得进去），而两侧各自的键数与
 * 键宽并不相等。两种取舍：
 *
 * - **对齐开启**（历史行为，默认）：两侧都缩放成恰好 `(1 - gap) / 2` 宽，于是中缝严格
 *   居中、上下行的断点完全对齐。代价是**按键宽度被改写**——某一侧键多时，该侧每一枚键
 *   都要被压窄到刚好塞满半宽，与另一侧、也与合体状态下的键宽都不一致。用户看到的就是
 *   "分体后按键变窄了"。
 * - **对齐关闭**：两侧各自靠外沿固定，按键按**原宽**排布，中缝是被挤出来的剩余空间，
 *   位置与宽度都由两侧的真实宽度决定。代价是各行断点不再对齐——这正是"不为了对齐而
 *   压缩按键"的目的。
 *
 * ## 关闭对齐时「原宽」到底指什么（[unalignedWidths]）
 *
 * 这是被用户反复纠正过的一点，写清楚免得又被"优化"回去。一行里有两类键：
 *
 * - **承载键**（字母、符号……）：宽度按**池内**归一化后乘 `(1 - gap)`。池 = 该行里所有
 *   承载键，**空白占位键不在池里**。于是往一行里加空白占位键**不会**让别的键变窄——
 *   旧实现按整行归一化，加一枚键就把全行每一枚都压窄一点，用户看到的是"加键反而更挤"。
 * - **空白占位键**：按布局里写的**绝对**宽度乘 `(1 - gap)` 占位，不参与分摊。
 *
 * 两者合起来的手感就是：占位键把同一侧的承载键整体往中缝方向推，**推多少完全由占位键
 * 自己的宽度决定**；中缝则相应地被挤窄。典型用法是在 `asdfghjkl` 这类奇数行两侧各加
 * 一枚占位键（配合中间键复制成为 `asdfg‖ghjkl`），两枚 `g` 就会向中缝凸出。
 *
 * 两条可以直接引用的恒等式（[unalignedWidths] 保证，单测钉住）：
 *
 * 1. **承载键合计恒等于 `1 - gap`**，与占位键有多少、多宽都无关。因此某一行不加占位键
 *    时，中缝恰好等于用户设定的 `gap`——与改动前的行为逐位一致。
 * 2. **承载键之间的比例 = 布局里写的比例**（都乘同一个 `(1 - gap) / 承载键之和`）。
 *
 * 注意关闭对齐时**行总宽不再等于 `1 - gap`**：它等于 `1 - gap + 占位键之和 × (1 - gap)`，
 * 中缝是 1 减去这个和。开启对齐时两条路径的总宽仍然恰好是 `1 - gap`（中缝宽度固定）。
 */
object SplitRowWidths {

    /** 中缝在任何情况下都要留下的最小可见宽度，避免空白占位键加太多时两侧贴死。 */
    const val MIN_GAP = 0.02f

    /** 一侧的宽度分配结果，键为整行下标。 */
    class SideWidths(private val widths: Map<Int, Float>) {
        operator fun get(index: Int): Float? = widths[index]
        val total: Float get() = widths.values.sum()

        /** 该侧每枚键的宽度，按整行下标排列（测试与调试用）。 */
        val entries: Map<Int, Float> get() = widths

        /** 等比缩放整侧。只用于[fitSides]兜底。 */
        fun scaled(factor: Float): SideWidths =
            SideWidths(widths.mapValues { (_, w) -> w * factor })
    }

    /**
     * 挑分体断点用的宽度向量：**只看承载键**，空白占位键计 0。
     *
     * 占位键是"把这一侧往中缝推"的工具，不是内容，所以它不该改变断点落在哪两个键之间。
     * 否则会出现这种很别扭的结果：`asdfghjkl` 复制中间键后是 10 枚等宽字母键，本来正好
     * 从两枚 g 之间断开；两端各加一枚 0.05 的占位键后，累计宽度被推偏，断点滑到 `f|g`，
     * 于是只有一侧有 g——用户加占位键本意是"让两枚 g 都往中间凸"。
     *
     * 返回的向量和为 1（承载键之和归一到 1；整行都是占位键时原样退回 [absoluteWidths]），
     * 因此可以直接喂给按"累计宽度过半"找断点的调用方。
     */
    fun breakpointWidths(absoluteWidths: List<Float>, pooled: List<Boolean>): List<Float> {
        if (absoluteWidths.isEmpty()) return absoluteWidths
        val carrierSum = absoluteWidths.indices
            .filter { pooled.getOrElse(it) { true } }
            .sumOf { absoluteWidths[it].coerceAtLeast(0f).toDouble() }
            .toFloat()
        if (carrierSum <= 0f) return absoluteWidths
        return absoluteWidths.indices.map { i ->
            if (pooled.getOrElse(i) { true }) absoluteWidths[i].coerceAtLeast(0f) / carrierSum else 0f
        }
    }

    /**
     * 两侧合起来超过 `1 - [minGap]` 时等比收窄，保证中缝至少还剩 [minGap]。
     *
     * 关闭中缝对齐后，中缝是被两侧真实宽度"挤剩下"的空间：空白占位键加得越多，中缝越窄。
     * 加过头（两侧之和 > 1）时会真正贴死甚至重叠，这时只能重新开始压窄按键——这是唯一的
     * 兜底路径，正常用量不会触发（把占位键宽度加起来控制在 `gap / 2` 以内即可）。
     *
     * 放在这里而不是渲染代码里，是为了让"到底什么时候还会压窄按键"有一条可测的边界。
     */
    fun fitSides(
        left: SideWidths,
        right: SideWidths,
        minGap: Float = MIN_GAP
    ): Pair<SideWidths, SideWidths> {
        val usable = 1f - minGap
        val sum = left.total + right.total
        if (sum <= usable || sum <= 0f) return left to right
        val factor = usable / sum
        return left.scaled(factor) to right.scaled(factor)
    }

    /** 单侧可用宽度（对齐模式下两侧都恰好是它）。 */
    fun sideCapacity(gap: Float): Float = ((1f - gap) / 2f).coerceAtLeast(0.05f)

    /** 弹性键（空格等）应当占用的半宽比例：中缝越大给得越多（`0.30 → 0.55`）。 */
    fun minFlexShare(gap: Float): Float = (0.30f + (gap - 0.20f) * 0.80f).coerceIn(0.30f, 0.55f)

    /**
     * 关闭中缝对齐时，整行各键的实际占用宽度（已含 `(1 - gap)` 缩放）。
     *
     * @param absoluteWidths 布局里写的**绝对**宽度（未按行归一化；弹性键已在调用方按行内
     *   剩余空间均分）
     * @param pooled 该下标的键是否属于「承载键池」。空白占位键传 `false`——它们按绝对宽度
     *   占位，不参与归一化，于是加占位键不会改写其它键的宽度。
     * @param gap 中缝宽度（0..1 的比例），对承载键是"统一摊薄"，对占位键是"位移量"
     */
    fun unalignedWidths(
        absoluteWidths: List<Float>,
        pooled: List<Boolean>,
        gap: Float
    ): List<Float> {
        val scale = (1f - gap).coerceAtLeast(0.05f)
        if (absoluteWidths.isEmpty()) return emptyList()
        val poolSum = absoluteWidths.indices
            .filter { pooled.getOrElse(it) { true } }
            .sumOf { absoluteWidths[it].coerceAtLeast(0f).toDouble() }
            .toFloat()
        // 池内和为 0（整行都是占位键之类）时退化：所有键都只做 (1 - gap) 缩放。
        val poolScale = if (poolSum > 0f) scale / poolSum else scale
        return absoluteWidths.indices.map { i ->
            val width = absoluteWidths[i].coerceAtLeast(0f)
            if (pooled.getOrElse(i) { true }) width * poolScale else width * scale
        }
    }

    /**
     * 计算一侧（左半或右半）各键的宽度。
     *
     * @param indices 该侧在整行里的下标范围
     * @param normalizedWidths 整行归一化后的宽度（和为 1，见 `BaseKeyboard.resolveRowWidths`）。
     *   只用于对齐开启的路径。
     * @param absoluteWidths 整行未归一化的绝对宽度（见 [unalignedWidths]），只用于关闭对齐的路径
     * @param pooled 哪些键参与承载键池（空白占位键为 `false`），只用于关闭对齐的路径
     * @param isFixed 该键在布局里是否显式写了宽度（`percentWidth > 0`）。只有固定宽度的键
     *   会被对齐逻辑当作不可伸缩的份，其余（`0` = 弹性）吸收差额。
     * @param gap 中缝宽度（0..1 的比例）
     * @param alignHalves 是否强制两侧各占半宽，见类注释
     */
    fun forSide(
        indices: IntRange,
        normalizedWidths: List<Float>,
        absoluteWidths: List<Float>,
        pooled: List<Boolean>,
        isFixed: (Int) -> Boolean,
        gap: Float,
        alignHalves: Boolean
    ): SideWidths {
        if (indices.isEmpty()) return SideWidths(emptyMap())

        if (!alignHalves) {
            // 不对齐：每侧靠外沿固定，按键按原宽排布，中缝是被挤出来的剩余空间。
            // 各键宽度见 unalignedWidths——承载键按池归一化、占位键按绝对宽度。
            val unaligned = unalignedWidths(absoluteWidths, pooled, gap)
            return SideWidths(indices.associateWith { unaligned.getOrElse(it) { 0f } })
        }

        val base = indices.associateWith { normalizedWidths[it] }
        val sideCapacity = sideCapacity(gap)
        val flexible = indices.filterNot(isFixed)
        val fixed = indices.filter(isFixed)

        val fixedSum = fixed.sumOf { (base[it] ?: 0f).toDouble() }.toFloat()
        val flexSum = flexible.sumOf { (base[it] ?: 0f).toDouble() }.toFloat()
        val total = (fixedSum + flexSum).coerceAtLeast(0.0001f)

        // 该侧没有弹性键：整侧按比例缩放到半宽即可，没有可分配的余地。
        if (flexible.isEmpty()) {
            val ratio = sideCapacity / total
            return SideWidths(base.mapValues { (_, w) -> w * ratio })
        }

        // 给弹性键（典型是空格）留出至少一部分半宽，避免中缝较大时空格被压得过小。
        val targetFlex = maxOf(
            sideCapacity * minFlexShare(gap),
            (sideCapacity - fixedSum).coerceAtLeast(0f)
        ).coerceAtMost(sideCapacity)
        val targetFixed = (sideCapacity - targetFlex).coerceAtLeast(0f)

        val result = mutableMapOf<Int, Float>()
        val fixedScale = if (fixedSum > 0f) targetFixed / fixedSum else 0f
        fixed.forEach { result[it] = (base[it] ?: 0f) * fixedScale }
        val flexScale = if (flexSum > 0f) targetFlex / flexSum else 0f
        flexible.forEach { result[it] = (base[it] ?: 0f) * flexScale }
        return SideWidths(result)
    }
}
