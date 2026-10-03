/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分体键盘的键宽分配。
 *
 * 这里守的是用户报的那个具体问题，前后有过两版：
 *
 * 1. **分体时按键被压窄**——旧实现无条件把两侧都缩放到恰好半宽、以换取中缝严格居中。
 *    中缝对齐因此成了一个可关闭的选项。
 * 2. **关闭对齐后按键宽度仍被按键数牵着走**——第一版"关闭"实现只是把整行统一乘
 *    `(1 - gap)`，于是往 `asdfghjkl` 里加一枚空白占位键，全行每一枚键都被压窄一点，
 *    用户要的"两枚 g 向中缝凸出来"根本没发生。
 *
 * 现在的契约（见 [SplitRowWidths] 类注释）：关闭对齐时**承载键按池内归一化**（空占位键
 * 不在池里），**占位键按自己的绝对宽度位移**，中缝是被挤剩下的空间。下面逐条钉住。
 */
class SplitRowWidthsTest {

    private val gap = 0.20f

    /** 除 `maxOf(0f, ...)` 之外与 `BaseKeyboard.resolveAbsoluteRowWidths` 等价。 */
    private fun absolute(row: List<Float>): List<Float> {
        if (row.isEmpty()) return emptyList()
        val fixedSum = row.filter { it > 0f }.sum()
        val flexCount = row.count { it <= 0f }
        val flexWidth = if (flexCount > 0) ((1f - fixedSum).coerceAtLeast(0f)) / flexCount else 0f
        return row.map { if (it > 0f) it else flexWidth }
    }

    /** 与 `BaseKeyboard.resolveRowWidths` 等价：绝对宽度按行和归一化。 */
    private fun normalize(row: List<Float>): List<Float> {
        val abs = absolute(row)
        val sum = abs.sum()
        return if (sum > 0f) abs.map { it / sum } else abs.map { 1f / abs.size }
    }

    /** 一行 10 键、等宽，全部是弹性键（布局里没写 percentWidth）。 */
    private val tenFlexKeys = normalize(List(10) { 0f })

    private fun widths(
        indices: IntRange,
        row: List<Float>,
        fixed: Set<Int> = emptySet(),
        pooled: List<Boolean> = row.map { true },
        alignHalves: Boolean
    ): SplitRowWidths.SideWidths = SplitRowWidths.forSide(
        indices = indices,
        normalizedWidths = normalize(row),
        absoluteWidths = absolute(row),
        pooled = pooled,
        isFixed = { it in fixed },
        gap = gap,
        alignHalves = alignHalves
    )

    @Test
    fun alignedModeGivesEachSideExactlyHalfTheKeyboard() {
        // 对齐模式的基本契约：两侧各占 (1 - gap) / 2，中缝严格居中。
        val row = List(10) { 0.1f }
        val left = widths(0..4, row, alignHalves = true)
        val right = widths(5..9, row, alignHalves = true)
        assertEquals(SplitRowWidths.sideCapacity(gap), left.total, 1e-4f)
        assertEquals(SplitRowWidths.sideCapacity(gap), right.total, 1e-4f)
    }

    @Test
    fun unalignedModeKeepsEveryKeyAtItsDockedWidth() {
        // 关闭对齐：每一枚键都严格等于合体状态下的宽度 × (1 - gap)，一枚都不许被改写。
        val row = List(10) { 0.1f }
        val abs = absolute(row)
        val left = widths(0..4, row, alignHalves = false)
        val right = widths(5..9, row, alignHalves = false)
        (0..9).forEach { i ->
            val side = if (i <= 4) left else right
            assertEquals(abs[i] * (1f - gap), side[i]!!, 1e-4f)
        }
    }

    @Test
    fun addingPlaceholderKeysDoesNotSqueezeCarriersAndPushesThemInward() {
        // 用户报的那个手感，原样复现：
        //   asdfghjkl   →  asdfghjkl 两侧各加一枚空白占位键
        // 断言 1：a~l 的宽度**完全不变**（加占位键不该压缩任何承载键）。
        // 断言 2：左侧最后一枚承载键的右边界向右移动（= 向中缝凸出），位移量正好是占位键宽度。
        val carriers = List(9) { 0.1f }
        val spacer = 0.05f
        val before = absolute(carriers)
        val after = absolute(listOf(spacer) + carriers + listOf(spacer))
        val pooledAfter = listOf(false) + List(9) { true } + listOf(false)

        val leftBefore = SplitRowWidths.forSide(
            indices = 0..3,
            normalizedWidths = normalize(carriers),
            absoluteWidths = before,
            pooled = List(9) { true },
            isFixed = { false },
            gap = gap,
            alignHalves = false
        )
        // 左侧现在是「占位键, a, s, d, f」→ 承载键 a~f 占下标 1..4。
        val leftAfter = SplitRowWidths.forSide(
            indices = 0..4,
            normalizedWidths = normalize(listOf(spacer) + carriers + listOf(spacer)),
            absoluteWidths = after,
            pooled = pooledAfter,
            isFixed = { false },
            gap = gap,
            alignHalves = false
        )

        // 键 a~f（占位键之后的第 0..3 枚可点键）宽度不变。
        val pooledSum = after.filterIndexed { i, _ -> pooledAfter[i] }.sum()
        (1..4).forEach { index ->
            val expected = before[index - 1] * (1f - gap) / pooledSum
            assertEquals("承载键宽度不该被占位键改写", expected, leftAfter[index]!!, 1e-4f)
        }

        // 承载键的合计宽度不变，占位键的宽度就是"向中缝的位移量"。
        val carriersBeforeTotal = leftBefore.total
        val carriersAfterTotal = outOf(leftAfter, setOf(0))
        assertEquals(carriersBeforeTotal, carriersAfterTotal, 1e-4f)
        assertEquals(spacer * (1f - gap), leftAfter[0]!!, 1e-4f)
        assertEquals(
            "左侧内沿的位移量 = 占位键宽度（× 整体缩放）",
            spacer * (1f - gap),
            leftAfter.total - leftBefore.total,
            1e-4f
        )
    }

    @Test
    fun theAsdfgScenarioProtrudesBothGKeysWithoutTouchingLetterWidths() {
        // 用户现场的那个配置，整条链路跑一遍：
        //   asdfghjkl（9 键）→ 两端各加一枚 0.05 的空白占位键 → 11 键
        //   → 分体复制中间键 → 12 键 [占位, a..g, g..l, 占位]
        // 断言：两侧各自的内沿比"不加占位键"时更靠中间，位移量 = 占位键宽 × (1 - gap)，
        // 而 10 枚字母键的宽度一枚都没变。
        val spacer = 0.05f
        val letters = List(9) { 0.1f }
        val plain = letters + letters[4] // 复制中间键后：10 枚等宽字母键
        val withSpacers = listOf(spacer) + letters + listOf(spacer)

        val plainAbs = absolute(plain)
        val plainPooled = List(plain.size) { true }
        val plainLeft = SplitRowWidths.forSide(
            indices = 0..4,
            normalizedWidths = normalize(plain),
            absoluteWidths = plainAbs,
            pooled = plainPooled,
            isFixed = { false },
            gap = gap,
            alignHalves = false
        )
        val plainRight = SplitRowWidths.forSide(
            indices = 5..9,
            normalizedWidths = normalize(plain),
            absoluteWidths = plainAbs,
            pooled = plainPooled,
            isFixed = { false },
            gap = gap,
            alignHalves = false
        )

        // 复制后的行：占位键在首尾，中间 10 枚字母键。分体断点落在两枚 g 之间（下标 5 之后）。
        val row = listOf(spacer) + List(10) { 0.1f } + listOf(spacer)
        val pooled = listOf(false) + List(10) { true } + listOf(false)
        val left = SplitRowWidths.forSide(
            indices = 0..5,
            normalizedWidths = normalize(row),
            absoluteWidths = absolute(row),
            pooled = pooled,
            isFixed = { false },
            gap = gap,
            alignHalves = false
        )
        val right = SplitRowWidths.forSide(
            indices = 6..11,
            normalizedWidths = normalize(row),
            absoluteWidths = absolute(row),
            pooled = pooled,
            isFixed = { false },
            gap = gap,
            alignHalves = false
        )

        // 字母键宽度不变（左边是下标 1..5，右边是 6..10）。
        val letterWidth = 0.1f * (1f - gap)
        (1..5).forEach { assertEquals(letterWidth, left[it]!!, 1e-4f) }
        (6..10).forEach { assertEquals(letterWidth, right[it]!!, 1e-4f) }

        // 中缝被占位键挤窄，两侧内沿各向中间凸出"占位键宽 × (1 - gap)"。
        val gapBefore = 1f - (plainLeft.total + plainRight.total)
        val gapAfter = 1f - (left.total + right.total)
        val pushed = spacer * (1f - gap)
        assertEquals(gap - pushed * 2f, gapAfter, 1e-4f)
        assertTrue("中缝确实被挤窄了", gapAfter < gapBefore)
        assertEquals(pushed, left.total - plainLeft.total, 1e-4f)
        assertEquals(pushed, right.total - plainRight.total, 1e-4f)
    }

    @Test
    fun placeholderKeysDoNotDragTheBreakpointAwayFromTheTwoGKeys() {
        // 复制中间键后的 asdfghjkl 行是 10 枚等宽字母键，断点正好落在两枚 g 之间。
        // 两端各加一枚占位键后，如果断点按"整行累计宽度过半"来挑，就会被推偏到 f|g，
        // 于是只有一侧有 g——而用户加占位键的本意恰恰是"让两枚 g 都往中间凸"。
        // breakpointWidths 把占位键算作 0，断点因此不受影响。
        val row = List(12) { 0.1f }.toMutableList()
        row[0] = 0.05f
        row[11] = 0.05f
        val pooled = List(12) { it != 0 && it != 11 }
        val breaks = SplitRowWidths.breakpointWidths(absolute(row), pooled)

        var prefix = 0f
        var best = 0
        var bestDistance = Float.MAX_VALUE
        (0 until row.lastIndex).forEach { i ->
            prefix += breaks[i]
            val d = kotlin.math.abs(prefix - 0.5f)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        assertEquals("断点应当落在两枚 g 之间（左侧下标 5）", 5, best)
        assertEquals("承载键之间的宽度向量和为 1", 1f, breaks.sum(), 1e-4f)
        assertEquals("占位键不参与断点", 0f, breaks[0], 0f)
    }

    @Test
    fun placeholderOnTheRightHalfPushesThatHalfTowardTheMiddleToo() {
        // 左右必须对称：上面几条都在测左半，这里把占位键放到右半，断言右侧内沿同样向中间
        // 移动、字母宽度同样不变。否则"任意一行任意一侧"就只是左半的特例。
        val spacer = 0.04f
        val row = List(10) { 0.1f }
        val withoutSpacer = SplitRowWidths.forSide(
            indices = 5..9,
            normalizedWidths = normalize(row),
            absoluteWidths = absolute(row),
            pooled = List(10) { true },
            isFixed = { false },
            gap = gap,
            alignHalves = false
        )
        val withSpacer = listOf(spacer) + row + listOf(spacer)
        val right = SplitRowWidths.forSide(
            indices = 6..11,
            normalizedWidths = normalize(withSpacer),
            absoluteWidths = absolute(withSpacer),
            pooled = listOf(false) + List(10) { true } + listOf(false),
            isFixed = { false },
            gap = gap,
            alignHalves = false
        )
        assertEquals(0.1f * (1f - gap), right[10]!!, 1e-4f)
        assertEquals(
            "右侧内沿同时向中间移动",
            spacer * (1f - gap),
            right.total - withoutSpacer.total,
            1e-4f
        )
    }

    @Test
    fun placeholderPushesTheInnerEdgeByItsOwnWidthWhereverItSitsInTheHalf() {
        // 占位键放在半边的哪个位置都一样：半边内沿的位移量恒等于占位键宽度。
        // 放在最外沿 → 整半平移；放在中间 → 它自己那个位置留出空白，它右边（朝中缝那侧）
        // 的键整体内移。两种情况对称的两半都得让出同样多的空间。
        val spacer = 0.06f
        val carriers = List(10) { 0.1f }
        fun leftTotalAt(insertAt: Int): Float {
            val row = carriers.toMutableList().apply { add(insertAt, spacer) }
            val pooled = row.map { it != spacer }
            return SplitRowWidths.forSide(
                indices = 0..5,
                normalizedWidths = normalize(row),
                absoluteWidths = absolute(row),
                pooled = pooled,
                isFixed = { false },
                gap = gap,
                alignHalves = false
            ).total
        }
        val expected = 0.5f * (1f - gap) + spacer * (1f - gap)
        listOf(0, 2, 5).forEach { at ->
            assertEquals("占位键插在下标 $at 时左半总宽应当一致", expected, leftTotalAt(at), 1e-4f)
        }
    }

    @Test
    fun mixedWidthRowAlsoProtrudesAndKeepsItsLayoutRatios() {
        // 「任意一行」的另一个要点：行里各键宽度不一样时（默认布局 zxcvbnm 行就是
        // Caps 0.15 + 七个字母 0.1 + Backspace 0.15）同样成立——占位键照样只位移、不改宽。
        val spacer = 0.05f
        val zxcv = listOf(0.15f) + List(7) { 0.1f } + listOf(0.15f) // 9 键，复制中间键后 10 键
        val duplicated = zxcv + zxcv[4] // 复制 V
        val row = listOf(spacer) + duplicated + listOf(spacer)
        val pooled = listOf(false) + List(duplicated.size) { true } + listOf(false)
        val left = SplitRowWidths.forSide(
            indices = 0..5,
            normalizedWidths = normalize(row),
            absoluteWidths = absolute(row),
            pooled = pooled,
            isFixed = { false },
            gap = gap,
            alignHalves = false
        )
        val scale = (1f - gap) / duplicated.sum()
        assertEquals("Caps 保持布局里写的比例", 0.15f * scale, left[1]!!, 1e-4f)
        assertEquals("字母保持布局里写的比例", 0.1f * scale, left[2]!!, 1e-4f)
        assertEquals("占位键宽度就是位移量", spacer * (1f - gap), left[0]!!, 1e-4f)
    }

    @Test
    fun carrierTotalIsExactlyOneMinusGapNoMatterHowManyPlaceholders() {
        // 不变量 1（见 SplitRowWidths 类注释）：承载键合计恒为 1 - gap，与占位键无关。
        // 这条保证了"不加占位键的行，中缝仍恰好等于用户设定的 gap"——改动前后一致。
        val carriers = List(10) { 0.1f }
        listOf(emptyList(), listOf(0.03f), listOf(0.05f, 0.05f), listOf(0.02f, 0.1f, 0.03f))
            .forEach { spacers ->
                val row = listOf(spacers.firstOrNull() ?: 0.0f) + carriers +
                    listOf(spacers.lastOrNull() ?: 0.0f)
                val pooled = listOf(spacers.isNotEmpty()) + List(10) { true } +
                    listOf(spacers.size > 1)
                val widths = SplitRowWidths.unalignedWidths(absolute(row), pooled, gap)
                val carrierTotal = widths.filterIndexed { i, _ -> pooled[i] }.sum()
                assertEquals("占位键 $spacers", 1f - gap, carrierTotal, 1e-4f)
            }
    }

    private fun outOf(side: SplitRowWidths.SideWidths, skip: Set<Int>): Float =
        side.entries.filterKeys { it !in skip }.values.sum()

    @Test
    fun unalignedSidesSumToLessThanOneSoTheGapIsWhateverIsLeft() {
        // 关闭对齐的中缝是"挤剩下的空间"：两侧之和越小，中缝越宽。
        // 没有任何占位键时，两侧之和恰好是 1 - gap（中缝 = 用户设定的 gap）。
        val row = List(10) { 0.1f }
        val left = widths(0..4, row, alignHalves = false)
        val right = widths(5..9, row, alignHalves = false)
        assertEquals(1f - gap, left.total + right.total, 1e-4f)

        // 两侧各加一枚占位键后，中缝被挤窄，挤窄的量正好是两枚占位键之和。
        val withSpacers = listOf(0.05f) + row + listOf(0.05f)
        val pooled = listOf(false) + List(10) { true } + listOf(false)
        val l2 = widths(0..5, withSpacers, pooled = pooled, alignHalves = false)
        val r2 = widths(6..11, withSpacers, pooled = pooled, alignHalves = false)
        val middle = 1f - (l2.total + r2.total)
        assertEquals("中缝被两枚占位键挤窄", gap - 0.1f * (1f - gap), middle, 1e-4f)
    }

    @Test
    fun fitSidesOnlyKicksInWhenTheTwoHalvesWouldOverlap() {
        // 兜底：占位键宽度加起来超过中缝时，两侧之和已 > 1，只能等比缩放（重新压窄按键）。
        // 这是唯一还会动按键宽度的路径，所以要有边界断言。
        val row = List(10) { 0.1f }
        val comfortable = SplitRowWidths.forSide(
            indices = 0..4,
            normalizedWidths = normalize(row),
            absoluteWidths = absolute(row),
            pooled = List(10) { true },
            isFixed = { false },
            gap = gap,
            alignHalves = false
        )
        val same = SplitRowWidths.fitSides(comfortable, comfortable)
        assertEquals(comfortable.total, same.first.total, 1e-5f)

        val tooWide = listOf(0.5f) + row
        val left = SplitRowWidths.forSide(
            indices = 0..5,
            normalizedWidths = normalize(tooWide),
            absoluteWidths = absolute(tooWide),
            pooled = listOf(false) + List(10) { true },
            isFixed = { false },
            gap = gap,
            alignHalves = false
        )
        val right = SplitRowWidths.forSide(
            indices = 6..10,
            normalizedWidths = normalize(tooWide),
            absoluteWidths = absolute(tooWide),
            pooled = listOf(false) + List(10) { true },
            isFixed = { false },
            gap = gap,
            alignHalves = false
        )
        val fitted = SplitRowWidths.fitSides(left, right)
        assertEquals(1f - SplitRowWidths.MIN_GAP, fitted.first.total + fitted.second.total, 1e-4f)
    }

    @Test
    fun unalignedModeNeverMakesKeysNarrowerThanAlignedMode() {
        // 回归断言：关掉中缝对齐后，任何一枚键都不应当比开启时更窄。
        val row = List(10) { 0.1f }
        (0..9).forEach { i ->
            val side = if (i <= 4) 0..4 else 5..9
            val aligned = widths(side, row, alignHalves = true)[i]!!
            val unaligned = widths(side, row, alignHalves = false)[i]!!
            assertTrue(
                "键 $i：不对齐时($unaligned)不应比对齐时($aligned)更窄",
                unaligned >= aligned - 1e-5f
            )
        }
    }

    @Test
    fun unbalancedSidesStopSqueezingKeysWhenAlignmentIsOff() {
        // 左侧 4 键、右侧 6 键（断点被 splitAfter 挪偏后就是这样）。
        // 对齐开启时两侧都被强行拉到半宽：键多的那一侧每枚键被压窄到 0.0667；
        // 关闭后两侧都只做整行缩放（0.1 × 0.8 = 0.08），右侧不再被额外压缩。
        val row = List(10) { 0.1f }
        val alignedRight = widths(4..9, row, alignHalves = true)
        val unalignedRight = widths(4..9, row, alignHalves = false)
        assertTrue(
            "键多的一侧在开启对齐时确实是被压窄的（这是问题的来源）",
            alignedRight[5]!! < 0.1f * (1f - gap) - 1e-4f
        )
        assertEquals("0.1 × (1 - 0.2)", 0.08f, unalignedRight[5]!!, 1e-4f)
    }

    @Test
    fun unalignedModeWithFixedAndFlexibleKeysStillKeepsDockedWidths() {
        // 混合行：显式宽度的键与弹性键（空格）。关闭对齐时两者一视同仁，
        // 都只做整行缩放——关闭对齐的意义就是"不对按键做任何二次分配"。
        val row = listOf(0.1f, 0.1f, 0.3f, 0.0f, 0.5f)
        val abs = absolute(row)
        val left = widths(0..1, row, fixed = setOf(0, 1), alignHalves = false)
        val right = widths(2..4, row, fixed = setOf(2), alignHalves = false)
        assertEquals(1f - gap, left.total + right.total, 1e-4f)
        assertEquals(abs[0] * (1f - gap), left[0]!!, 1e-4f)
        assertEquals(abs[3] * (1f - gap), right[3]!!, 1e-4f)
    }

    @Test
    fun emptySideYieldsNoWidths() {
        val side = widths(3..2, tenFlexKeys, alignHalves = true)
        assertEquals(0f, side.total, 0f)
        assertEquals(null, side[3])
    }

    @Test
    fun sideCapacityIsHalfRemainingSpaceAndNeverCollapses() {
        assertEquals(0.4f, SplitRowWidths.sideCapacity(0.2f), 1e-5f)
        // 中缝大到极端时也不会塌成 0：键盘至少要能画出两枚可见的键。
        assertTrue(SplitRowWidths.sideCapacity(0.99f) >= 0.05f)
    }

    @Test
    fun minFlexShareGrowsWithGapAndStaysBounded() {
        assertTrue(SplitRowWidths.minFlexShare(0.05f) >= 0.30f)
        assertTrue(SplitRowWidths.minFlexShare(0.60f) <= 0.55f)
        assertTrue(
            "中缝越大，弹性键（空格）应当分到越多半宽",
            SplitRowWidths.minFlexShare(0.50f) > SplitRowWidths.minFlexShare(0.10f)
        )
    }

    @Test
    fun alignedModeStillGivesSpaceEnoughRoomOnItsSide() {
        // 对齐模式下空格所在的弹性键必须拿到约定的半宽比例，否则中缝大时空格会小到点不中。
        val row = listOf(0.1f, 0.1f, 0.1f, 0.0f, 0.1f, 0.1f, 0.1f, 0.1f)
        val left = widths(0..3, row, fixed = setOf(0, 1, 2), alignHalves = true)
        val flexibleShare = left[3]!! / SplitRowWidths.sideCapacity(gap)
        assertTrue(
            "空格占半宽的比例应当不低于 minFlexShare，实际 $flexibleShare",
            flexibleShare >= SplitRowWidths.minFlexShare(gap) - 0.01f
        )
    }
}
