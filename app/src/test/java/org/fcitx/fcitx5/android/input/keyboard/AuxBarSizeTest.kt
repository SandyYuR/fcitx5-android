/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuxBarSizeTest {
    @Test
    fun ordinaryLayoutKeepsTheConfiguredFraction() {
        for (position in AuxBarPosition.entries) {
            assertEquals(0.15f, AuxBarConfig(position, 15f).resolveSizeFraction(LayoutVariant.Docked, 0.6f), 0.00001f)
        }
    }

    @Test
    fun horizontalBarsKeepTheirHeightInSplitLayout() {
        for (position in listOf(AuxBarPosition.Top, AuxBarPosition.Bottom, AuxBarPosition.AbovePreedit)) {
            assertEquals(0.15f, AuxBarConfig(position, 15f).resolveSizeFraction(LayoutVariant.Split, 0.6f), 0.00001f)
        }
    }

    @Test
    fun sideBarsOccupyTheConfiguredShareAfterExcludingTheGap() {
        val fullWidth = 1000f
        val gapFraction = 0.2f
        for (position in listOf(AuxBarPosition.Left, AuxBarPosition.Right)) {
            val barWidth = fullWidth * AuxBarConfig(position, 15f).resolveSizeFraction(LayoutVariant.Split, gapFraction)
            val gapWidth = fullWidth * gapFraction
            assertEquals(120f, barWidth, 0.001f)
            assertEquals((fullWidth - gapWidth) * 0.15f, barWidth, 0.001f)
            assertTrue(barWidth < fullWidth * 0.15f)
        }
    }

    @Test
    fun zeroGapKeepsTheOrdinaryWidth() {
        val config = AuxBarConfig(AuxBarPosition.Left, 15f)
        assertEquals(config.resolveSizeFraction(LayoutVariant.Docked, 0f), config.resolveSizeFraction(LayoutVariant.Split, 0f), 0f)
    }

    @Test
    fun widerGapMakesTheBarNarrower() {
        val config = AuxBarConfig(AuxBarPosition.Right, 15f)
        assertTrue(config.resolveSizeFraction(LayoutVariant.Split, 0.6f) < config.resolveSizeFraction(LayoutVariant.Split, 0.05f))
    }

    @Test
    fun supportedSettingsPreserveTheShareOfUsableWidth() {
        for (sizePercent in 5..95) {
            for (gapPercent in 5..60) {
                val fraction = AuxBarConfig(AuxBarPosition.Left, sizePercent.toFloat())
                    .resolveSizeFraction(LayoutVariant.Split, gapPercent / 100f)
                val usableFraction = 1f - gapPercent / 100f
                assertTrue(fraction.isFinite() && fraction > 0f && fraction <= sizePercent / 100f)
                assertEquals(sizePercent / 100f, fraction / usableFraction, 0.00001f)
            }
        }
    }

    @Test
    fun nonSideBarsKeepTheMainKeyAreaScale() {
        for (position in AuxBarPosition.entries) {
            val config = AuxBarConfig(position, 15f)
            assertEquals(1f, config.resolveKeyAreaScale(LayoutVariant.Docked, 0.6f), 0f)
            if (position != AuxBarPosition.Left && position != AuxBarPosition.Right) {
                assertEquals(1f, config.resolveKeyAreaScale(LayoutVariant.Split, 0.6f), 0f)
            }
        }
    }

    @Test
    fun shrinkingTheSideBarDoesNotEnlargeTheMainKeys() {
        for (position in listOf(AuxBarPosition.Left, AuxBarPosition.Right)) {
            val config = AuxBarConfig(position, 15f)
            for (gapPercent in 5..60) {
                val gap = gapPercent / 100f
                val mainArea = 1f - config.resolveSizeFraction(LayoutVariant.Split, gap)
                val keyAreaScale = config.resolveKeyAreaScale(LayoutVariant.Split, gap)
                assertEquals(0.85f * (1f - gap), mainArea * keyAreaScale * (1f - gap), 0.00001f)
                assertEquals(1f - gap, config.resolveSizeFraction(LayoutVariant.Split, gap) + mainArea * keyAreaScale * (1f - gap), 0.00001f)
            }
        }
    }

    @Test
    fun sandyT9AndNumericLayoutKeepTheirCompensatedKeyWidths() {
        // Sandy 九键：左侧辅助栏 15%，右侧功能键按 15/85 补偿；数字布局两侧各 15%。
        val t9FunctionWidth = 0.17647f
        val t9DigitWidth = (1f - t9FunctionWidth) / 6f
        val numericFunctionWidth = 0.15f
        val numericDigitWidth = (1f - 2f * numericFunctionWidth) / 6f
        val config = AuxBarConfig(AuxBarPosition.Left, 15f)
        for (gap in listOf(0f, 0.05f, 0.2f, 0.6f)) {
            val mainArea = 1f - config.resolveSizeFraction(LayoutVariant.Split, gap)
            val keyScale = mainArea * config.resolveKeyAreaScale(LayoutVariant.Split, gap) * (1f - gap)
            assertEquals(numericFunctionWidth * (1f - gap), t9FunctionWidth * keyScale, 0.00001f)
            assertEquals(numericDigitWidth * (1f - gap), t9DigitWidth * keyScale, 0.00001f)
        }
    }
}
