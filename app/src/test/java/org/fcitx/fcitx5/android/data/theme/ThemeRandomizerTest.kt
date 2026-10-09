/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeRandomizerTest {

    @Test
    fun hslRgbAndHexBoundariesMatchWebsite() {
        assertEquals(RgbColor(255, 0, 0), ThemeAestheticScore.hslToRgb(0.0, 100.0, 50.0))
        assertEquals(RgbColor(0, 255, 0), ThemeAestheticScore.hslToRgb(120.0, 100.0, 50.0))
        assertEquals(RgbColor(0, 0, 255), ThemeAestheticScore.hslToRgb(240.0, 100.0, 50.0))
        assertEquals(RgbColor(255, 0, 0), ThemeAestheticScore.hslToRgb(360.0, 100.0, 50.0))
        assertEquals(RgbColor(128, 128, 128), ThemeAestheticScore.hslToRgb(-720.0, 0.0, 50.0))
        assertEquals(RgbColor(0, 0, 0), ThemeAestheticScore.hslToRgb(31.0, 150.0, -1.0))
        assertEquals(RgbColor(255, 255, 255), ThemeAestheticScore.hslToRgb(31.0, -1.0, 101.0))
        assertEquals("#ff0000", ThemeAestheticScore.hslToHex(0.0, 100.0, 50.0))
        assertEquals(RgbColor(170, 187, 204), ThemeAestheticScore.hexToRgb("#abc"))
        assertEquals(RgbColor(17, 34, 51), ThemeAestheticScore.hexToRgb("#80112233"))
    }

    @Test
    fun rgbToHslBoundariesAndCircularHueDistance() {
        assertEquals(HslColor(0.0, 0.0, 0.0), ThemeAestheticScore.rgbToHsl(0, 0, 0))
        assertEquals(HslColor(0.0, 0.0, 100.0), ThemeAestheticScore.rgbToHsl(255, 255, 255))
        assertEquals(0.0, ThemeAestheticScore.rgbToHsl(255, 0, 0).hue, 0.0)
        assertEquals(120.0, ThemeAestheticScore.rgbToHsl(0, 255, 0).hue, 0.0)
        assertEquals(180.0, ThemeAestheticScore.hueDist(350.0, 170.0), 0.0)
        assertEquals(20.0, ThemeAestheticScore.hueDist(350.0, 10.0), 0.0)
    }

    @Test
    fun seededGenerationIsDeterministicAndUsesWebsiteRanges() {
        val first = ThemeRandomizer.generateCandidate(Random(713))
        val second = ThemeRandomizer.generateCandidate(Random(713))
        assertEquals(first, second)
        assertEquals(28, first.colors.size)
        assertEquals(ThemeRandomizer.SCHEMES.size, ThemeRandomizer.SCHEMES.toSet().size)
        assertTrue(first.scheme in ThemeRandomizer.SCHEMES)
        assertTrue(first.baseHue >= 0.0 && first.baseHue < 360.0)
        assertTrue(first.accentHue >= 0.0 && first.accentHue < 360.0)
        assertTrue(first.baseSat >= 22.0 && first.baseSat < 67.0)
        if (first.scheme == "analogous") {
            val delta = ThemeAestheticScore.hueDist(first.baseHue, first.accentHue)
            assertTrue(delta >= 20.0 && delta < 50.0)
        }
        assertTrue(first.surfaceStyle == "Raised" || first.surfaceStyle == "Flat")
        first.colors.values.forEach { hex ->
            assertTrue(hex.matches(Regex("#[0-9a-f]{6}")))
            val rgb = ThemeAestheticScore.hexToRgb(hex)
            assertTrue(rgb.red in 0..255 && rgb.green in 0..255 && rgb.blue in 0..255)
        }
    }

    @Test
    fun zeroRandomSourceProducesExpectedDarkAnalogousPalette() {
        val candidate = ThemeRandomizer.generateCandidate(ZeroRandom)
        assertTrue(candidate.isDark)
        assertEquals("analogous", candidate.scheme)
        assertEquals(0.0, candidate.baseHue, 0.0)
        assertEquals(20.0, candidate.accentHue, 0.0)
        assertEquals(22.0, candidate.baseSat, 0.0)
        assertEquals(28, candidate.colors.size)
        assertEquals("#3d3333", candidate.colors[WebsiteThemeColorKeys.KEY_BACKGROUND])
        assertEquals("#181616", candidate.colors[WebsiteThemeColorKeys.BACKGROUND])
        assertEquals("Raised", candidate.surfaceStyle)
    }

    @Test
    fun customThemeMappingUsesWebsiteColorsAndExplicitArgb() {
        val candidate = ThemeRandomizer.generateCandidate(ZeroRandom)
        val theme = ThemeRandomizer.toCustomTheme(candidate, "generated")
        assertEquals("generated", theme.name)
        assertTrue(theme.isDark)
        assertEquals(null, theme.backgroundImage)
        assertEquals(opaque(candidate, WebsiteThemeColorKeys.BACKGROUND), theme.backgroundColor)
        assertEquals(opaque(candidate, WebsiteThemeColorKeys.PANEL_BACKGROUND), theme.barColor)
        assertEquals(opaque(candidate, WebsiteThemeColorKeys.KEY_BACKGROUND), theme.keyBackgroundColor)
        assertEquals(opaque(candidate, WebsiteThemeColorKeys.KEY_TEXT), theme.keyTextColor)
        assertEquals(opaque(candidate, WebsiteThemeColorKeys.PANEL_CANDIDATE_TEXT), theme.candidateTextColor)
        assertEquals(opaque(candidate, WebsiteThemeColorKeys.PANEL_CANDIDATE_INDEX), theme.candidateLabelColor)
        assertEquals(
            opaque(candidate, WebsiteThemeColorKeys.PINNER_SECONDARY_TEXT_COLOR),
            theme.candidateCommentColor
        )
        assertEquals(opaque(candidate, WebsiteThemeColorKeys.SPECIAL_KEY_BACKGROUND), theme.altKeyBackgroundColor)
        assertEquals(opaque(candidate, WebsiteThemeColorKeys.ACCENT_KEY_BACKGROUND), theme.accentKeyBackgroundColor)
        assertEquals(opaque(candidate, WebsiteThemeColorKeys.TOAST_BACKGROUND), theme.popupBackgroundColor)
        assertEquals(0x33, (theme.keyPressHighlightColor ushr 24) and 0xff)
        assertEquals(
            opaque(candidate, WebsiteThemeColorKeys.KEY_PRESSED) and 0x00ffffff,
            theme.keyPressHighlightColor and 0x00ffffff
        )
        val ripple = theme.waterRippleColor ?: error("generated theme must provide a ripple color")
        assertEquals(0x80, (ripple ushr 24) and 0xff)
        assertEquals(
            opaque(candidate, WebsiteThemeColorKeys.ACCENT_KEY_PRESSED) and 0x00ffffff,
            ripple and 0x00ffffff
        )
        assertEquals(theme, candidate.toCustomTheme("generated"))
    }

    @Test
    fun scoreUsesExactContrastBoundariesAndMinimumRatioPenalties() {
        val thresholdColors = contrastPalette().apply {
            put(WebsiteThemeColorKeys.KEY_TEXT, "#777777")
        }
        val threshold = ThemeAestheticScore.scoreTheme(thresholdColors)
        assertTrue(threshold.minRatio >= 4.5)
        assertTrue(
            ThemeAestheticScore.contrastRatio("#737373", "#000000") < 4.5
        )

        val good = ThemeAestheticScore.scoreTheme(contrastPalette())
        val severePenalty = ThemeAestheticScore.scoreTheme(
            contrastPalette().apply { put(WebsiteThemeColorKeys.KEY_TEXT, "#303030") }
        )
        val moderatePenalty = ThemeAestheticScore.scoreTheme(
            contrastPalette().apply { put(WebsiteThemeColorKeys.KEY_TEXT, "#595959") }
        )
        assertTrue(good.contrast > severePenalty.contrast)
        assertTrue(severePenalty.minRatio < 2.0)
        assertTrue(moderatePenalty.minRatio >= 2.0 && moderatePenalty.minRatio < 3.0)
        assertTrue(severePenalty.contrast <= good.contrast / 4 + 1)
        assertTrue(moderatePenalty.contrast < good.contrast)
    }

    @Test
    fun scoreAppliesHueMonoSaturationAndLightnessPenalties() {
        val harmonious = scoringPalette()
        val badHue = scoringPalette().apply {
            put(WebsiteThemeColorKeys.ACCENT_KEY_BACKGROUND, "#ffff00")
        }
        val monoBadHue = ThemeCandidate(badHue, scheme = "mono")
        val scoreHarmony = ThemeAestheticScore.scoreTheme(ThemeCandidate(harmonious, scheme = "mono"))
        val scoreBadHue = ThemeAestheticScore.scoreTheme(ThemeCandidate(badHue, scheme = "mono"))
        assertTrue(scoreBadHue.hue < scoreHarmony.hue)

        val highSaturation = scoringPalette().apply {
            WebsiteThemeColorKeys.entries().forEach { put(it, "#ff0000") }
        }
        val saturationScore = ThemeAestheticScore.scoreTheme(highSaturation)
        assertTrue(saturationScore.sat <= 5)

        val flat = scoringPalette().apply {
            WebsiteThemeColorKeys.entries().forEach { put(it, "#808080") }
        }
        val lightnessScore = ThemeAestheticScore.scoreTheme(flat)
        assertEquals(5, lightnessScore.light)
        assertEquals(25, scoreHarmony.hue)
    }

    @Test
    fun bestGenerationStopsAtThresholdOrAtOneHundredCandidates() {
        val stopsEarly = ThemeRandomizer.generateBestRandomTheme(ZeroRandom)
        assertTrue(stopsEarly.attempts in 1..ThemeRandomizer.MAX_ATTEMPTS)
        if (stopsEarly.total >= ThemeRandomizer.EARLY_STOP_SCORE) {
            assertEquals(1, stopsEarly.attempts)
        }

        val bounded = ThemeRandomizer.generateBestRandomTheme(ZeroRandom)
        assertEquals(ThemeRandomizer.MAX_ATTEMPTS, bounded.attempts)
        assertTrue(bounded.total < ThemeRandomizer.EARLY_STOP_SCORE)
        assertNotNull(bounded.theme)
        assertEquals(bounded.candidate.colors, bounded.colors)
        assertEquals(bounded.score.minRatio, bounded.minRatio, 0.0)
    }

    @Test
    fun metadataAndScoreAreExposedOnResult() {
        val result = ThemeRandomizer.generateBestRandomTheme(Random(20))
        assertEquals(result.candidate.scheme, result.scheme)
        assertEquals(result.candidate.baseHue, result.baseHue, 0.0)
        assertEquals(result.candidate.accentHue, result.accentHue, 0.0)
        assertEquals(result.candidate.baseSat, result.baseSat, 0.0)
        assertEquals(result.candidate.isDark, result.isDark)
        assertEquals(result.score.total, result.total)
        assertEquals(result.score.contrast, result.contrast)
        assertEquals(result.score.hue, result.hue)
        assertEquals(result.score.sat, result.sat)
        assertEquals(result.score.light, result.light)
        assertTrue(result.minRatio > 0.0)
    }

    private fun opaque(candidate: ThemeCandidate, key: String): Int {
        val rgb = ThemeAestheticScore.hexToRgb(candidate.colors.getValue(key))
        return (0xff shl 24) or (rgb.red shl 16) or (rgb.green shl 8) or rgb.blue
    }

    private fun contrastPalette(): MutableMap<String, String> =
        WebsiteThemeColorKeys.entries().associateWithTo(linkedMapOf()) { "#000000" }.apply {
            put(WebsiteThemeColorKeys.KEY_TEXT, "#ffffff")
            put(WebsiteThemeColorKeys.SPECIAL_KEY_TEXT, "#ffffff")
            put(WebsiteThemeColorKeys.ACCENT_KEY_TEXT, "#ffffff")
            put(WebsiteThemeColorKeys.PANEL_CANDIDATE_TEXT, "#ffffff")
            put(WebsiteThemeColorKeys.PANEL_TOOLBAR_TEXT, "#ffffff")
            put(WebsiteThemeColorKeys.PANEL_CANDIDATE_INDEX, "#ffffff")
            put(WebsiteThemeColorKeys.PINNER_TEXT_COLOR, "#ffffff")
            put(WebsiteThemeColorKeys.PINNER_SECONDARY_TEXT_COLOR, "#ffffff")
            put(WebsiteThemeColorKeys.ALT_TEXT, "#ffffff")
        }

    private fun scoringPalette(): MutableMap<String, String> =
        WebsiteThemeColorKeys.entries().associateWithTo(linkedMapOf()) { "#808080" }.apply {
            put(WebsiteThemeColorKeys.KEY_TEXT, "#ffffff")
            put(WebsiteThemeColorKeys.SPECIAL_KEY_TEXT, "#ffffff")
            put(WebsiteThemeColorKeys.ACCENT_KEY_TEXT, "#ffffff")
            put(WebsiteThemeColorKeys.PANEL_CANDIDATE_TEXT, "#ffffff")
            put(WebsiteThemeColorKeys.PANEL_TOOLBAR_TEXT, "#ffffff")
            put(WebsiteThemeColorKeys.PANEL_CANDIDATE_INDEX, "#ffffff")
            put(WebsiteThemeColorKeys.PINNER_TEXT_COLOR, "#ffffff")
            put(WebsiteThemeColorKeys.PINNER_SECONDARY_TEXT_COLOR, "#ffffff")
            put(WebsiteThemeColorKeys.ALT_TEXT, "#ffffff")
            put(WebsiteThemeColorKeys.ACCENT_KEY_BACKGROUND, "#ff0000")
            put(WebsiteThemeColorKeys.PANEL_BACKGROUND, "#808080")
            put(WebsiteThemeColorKeys.PINNER_BACKGROUND, "#808080")
        }

    private object ZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    private fun WebsiteThemeColorKeys.entries(): List<String> = listOf(
        WebsiteThemeColorKeys.KEY_BACKGROUND,
        WebsiteThemeColorKeys.KEY_PRESSED,
        WebsiteThemeColorKeys.KEY_BORDER_STROKE,
        WebsiteThemeColorKeys.KEY_TEXT,
        WebsiteThemeColorKeys.SPECIAL_KEY_BACKGROUND,
        WebsiteThemeColorKeys.SPECIAL_KEY_PRESSED,
        WebsiteThemeColorKeys.SPECIAL_KEY_BORDER_STROKE,
        WebsiteThemeColorKeys.SPECIAL_KEY_TEXT,
        WebsiteThemeColorKeys.ACCENT_KEY_BACKGROUND,
        WebsiteThemeColorKeys.ACCENT_KEY_PRESSED,
        WebsiteThemeColorKeys.ACCENT_KEY_BORDER_STROKE,
        WebsiteThemeColorKeys.ACCENT_KEY_TEXT,
        WebsiteThemeColorKeys.ALT_TEXT,
        WebsiteThemeColorKeys.BACKGROUND,
        WebsiteThemeColorKeys.PANEL_BACKGROUND,
        WebsiteThemeColorKeys.PANEL_TOOLBAR_TEXT,
        WebsiteThemeColorKeys.PANEL_TOOLBAR_ACTIVED,
        WebsiteThemeColorKeys.PANEL_TOOLBAR_ICON,
        WebsiteThemeColorKeys.PANEL_CANDIDATE_BACKGROUND,
        WebsiteThemeColorKeys.PANEL_CANDIDATE_TEXT,
        WebsiteThemeColorKeys.PANEL_CANDIDATE_INDEX,
        WebsiteThemeColorKeys.PANEL_CANDIDATE_DIVIDER,
        WebsiteThemeColorKeys.PANEL_TOOLBAR_PRESSED,
        WebsiteThemeColorKeys.PINNER_BACKGROUND,
        WebsiteThemeColorKeys.PINNER_TEXT_COLOR,
        WebsiteThemeColorKeys.PINNER_SECONDARY_TEXT_COLOR,
        WebsiteThemeColorKeys.TOAST_BACKGROUND,
        WebsiteThemeColorKeys.TOAST_TEXT
    )
}
