/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlin.random.Random

/**
 * Pure random theme generation ported from https://ime.lutrip.com/theme.html.
 *
 * The website candidate is intentionally kept intact for scoring. [toCustomTheme] is the one
 * central adapter from that website palette to this app's [Theme.Custom] fields.
 */
object ThemeRandomizer {
    const val DEFAULT_THEME_NAME = "Random Theme"
    const val MAX_ATTEMPTS = 100
    const val EARLY_STOP_SCORE = 88

    val SCHEMES = listOf("analogous", "complementary", "triadic", "split-comp", "mono")

    /** Generates one website candidate, preserving the website's random call order and formulas. */
    fun generateCandidate(random: Random = Random.Default): ThemeCandidate {
        val isDark = random.nextDouble() < 0.38
        val scheme = SCHEMES[(random.nextDouble() * SCHEMES.size).toInt()]
        val baseHue = random.nextDouble() * 360.0
        var accentHue = when (scheme) {
            "analogous" -> baseHue + (20.0 + random.nextDouble() * 30.0)
            "complementary" -> baseHue + 180.0
            "triadic" -> baseHue + 120.0
            "split-comp" -> baseHue + 150.0
            "mono" -> baseHue
            else -> baseHue
        }
        accentHue = ((accentHue % 360.0) + 360.0) % 360.0

        val baseSat = 22.0 + random.nextDouble() * 45.0
        val palette = if (isDark) {
            PaletteParameters(
                bgL = 9.0 + random.nextDouble() * 8.0,
                bgS = baseSat * 0.25,
                keyBgL = 22.0 + random.nextDouble() * 8.0,
                keyBgS = baseSat * 0.42,
                keyTextL = 88.0 + random.nextDouble() * 8.0,
                keyTextS = baseSat * 0.15,
                borderL = 34.0 + random.nextDouble() * 12.0,
                borderS = baseSat * 0.42,
                accentL = 52.0 + random.nextDouble() * 14.0,
                accentS = baseSat * 0.72,
                panelBgL = 15.0 + random.nextDouble() * 8.0,
                panelBgS = baseSat * 0.28,
                pinnerBgL = 26.0 + random.nextDouble() * 10.0,
                pinnerBgS = baseSat * 0.38
            )
        } else {
            PaletteParameters(
                bgL = 96.0 + random.nextDouble() * 3.0,
                bgS = baseSat * 0.12,
                keyBgL = 89.0 + random.nextDouble() * 7.0,
                keyBgS = baseSat * 0.32,
                keyTextL = 16.0 + random.nextDouble() * 14.0,
                keyTextS = baseSat * 0.42,
                borderL = 74.0 + random.nextDouble() * 14.0,
                borderS = baseSat * 0.32,
                accentL = 42.0 + random.nextDouble() * 14.0,
                accentS = baseSat * 0.68,
                panelBgL = 93.0 + random.nextDouble() * 4.0,
                panelBgS = baseSat * 0.18,
                pinnerBgL = 85.0 + random.nextDouble() * 8.0,
                pinnerBgS = baseSat * 0.28
            )
        }

        val accentTextL = if (palette.accentL < 55.0) 96.0 else 12.0
        val accentTextS = if (palette.accentL < 55.0) 8.0 else 15.0
        fun color(hue: Double, saturation: Double, lightness: Double): String =
            ThemeAestheticScore.hslToHex(hue, saturation, lightness)

        val colors = linkedMapOf(
            WebsiteThemeColorKeys.KEY_BACKGROUND to color(baseHue, palette.keyBgS, palette.keyBgL),
            WebsiteThemeColorKeys.KEY_PRESSED to color(
                baseHue,
                palette.keyBgS + 8.0,
                if (isDark) palette.keyBgL + 6.0 else palette.keyBgL - 8.0
            ),
            WebsiteThemeColorKeys.KEY_BORDER_STROKE to color(baseHue, palette.borderS, palette.borderL),
            WebsiteThemeColorKeys.KEY_TEXT to color(baseHue, palette.keyTextS, palette.keyTextL),
            WebsiteThemeColorKeys.SPECIAL_KEY_BACKGROUND to color(
                baseHue,
                palette.keyBgS * 0.85,
                if (isDark) palette.keyBgL + 4.0 else palette.keyBgL - 4.0
            ),
            WebsiteThemeColorKeys.SPECIAL_KEY_PRESSED to color(
                baseHue,
                palette.keyBgS + 6.0,
                if (isDark) palette.keyBgL + 8.0 else palette.keyBgL - 10.0
            ),
            WebsiteThemeColorKeys.SPECIAL_KEY_BORDER_STROKE to color(
                baseHue,
                palette.borderS,
                palette.borderL
            ),
            WebsiteThemeColorKeys.SPECIAL_KEY_TEXT to color(
                baseHue,
                palette.keyTextS,
                palette.keyTextL
            ),
            WebsiteThemeColorKeys.ACCENT_KEY_BACKGROUND to color(
                accentHue,
                palette.accentS,
                palette.accentL
            ),
            WebsiteThemeColorKeys.ACCENT_KEY_PRESSED to color(
                accentHue,
                palette.accentS,
                palette.accentL - 10.0
            ),
            WebsiteThemeColorKeys.ACCENT_KEY_BORDER_STROKE to color(
                accentHue,
                palette.accentS,
                palette.accentL - 5.0
            ),
            WebsiteThemeColorKeys.ACCENT_KEY_TEXT to color(
                accentHue,
                accentTextS,
                accentTextL
            ),
            WebsiteThemeColorKeys.ALT_TEXT to color(
                baseHue,
                palette.keyTextS * 0.5,
                if (isDark) palette.keyTextL - 22.0 else palette.keyTextL + 26.0
            ),
            WebsiteThemeColorKeys.BACKGROUND to color(baseHue, palette.bgS, palette.bgL),
            WebsiteThemeColorKeys.PANEL_BACKGROUND to color(
                baseHue,
                palette.panelBgS,
                palette.panelBgL
            ),
            WebsiteThemeColorKeys.PANEL_TOOLBAR_TEXT to color(
                baseHue,
                palette.keyTextS * 0.7,
                palette.keyTextL - 4.0
            ),
            WebsiteThemeColorKeys.PANEL_TOOLBAR_ACTIVED to color(
                accentHue,
                palette.accentS * 0.92,
                palette.accentL
            ),
            WebsiteThemeColorKeys.PANEL_TOOLBAR_ICON to color(
                baseHue,
                palette.keyTextS * 0.7,
                palette.keyTextL - 4.0
            ),
            WebsiteThemeColorKeys.PANEL_CANDIDATE_BACKGROUND to color(
                baseHue,
                palette.panelBgS,
                palette.panelBgL
            ),
            WebsiteThemeColorKeys.PANEL_CANDIDATE_TEXT to color(
                baseHue,
                palette.keyTextS,
                palette.keyTextL
            ),
            WebsiteThemeColorKeys.PANEL_CANDIDATE_INDEX to color(
                accentHue,
                palette.accentS * 0.88,
                palette.accentL
            ),
            WebsiteThemeColorKeys.PANEL_CANDIDATE_DIVIDER to color(
                baseHue,
                palette.borderS * 0.85,
                palette.borderL
            ),
            WebsiteThemeColorKeys.PANEL_TOOLBAR_PRESSED to color(
                accentHue,
                palette.accentS * 0.5,
                if (isDark) palette.panelBgL + 10.0 else palette.panelBgL - 5.0
            ),
            WebsiteThemeColorKeys.PINNER_BACKGROUND to color(
                baseHue,
                palette.pinnerBgS,
                palette.pinnerBgL
            ),
            WebsiteThemeColorKeys.PINNER_TEXT_COLOR to color(
                baseHue,
                palette.keyTextS,
                palette.keyTextL
            ),
            WebsiteThemeColorKeys.PINNER_SECONDARY_TEXT_COLOR to color(
                baseHue,
                palette.keyTextS * 0.5,
                if (isDark) palette.keyTextL - 22.0 else palette.keyTextL + 22.0
            ),
            WebsiteThemeColorKeys.TOAST_BACKGROUND to color(
                baseHue,
                palette.keyBgS * 0.6,
                if (isDark) 32.0 else 28.0
            ),
            WebsiteThemeColorKeys.TOAST_TEXT to color(baseHue, 8.0, 96.0)
        )

        return ThemeCandidate(
            colors = colors,
            scheme = scheme,
            baseHue = baseHue,
            accentHue = accentHue,
            baseSat = baseSat,
            isDark = isDark,
            surfaceStyle = if (random.nextDouble() * 2.0 < 1.0) "Raised" else "Flat"
        )
    }

    /**
     * Generate at most [MAX_ATTEMPTS] candidates and stop as soon as the website's total reaches
     * [EARLY_STOP_SCORE]. The raw candidate is scored before its app-specific conversion.
     */
    fun generateBestRandomTheme(
        random: Random = Random.Default,
        name: String = DEFAULT_THEME_NAME
    ): RandomThemeResult {
        var bestCandidate: ThemeCandidate? = null
        var bestScore: ThemeScore? = null
        var bestTotal = Int.MIN_VALUE
        var attempts = 0

        while (attempts < MAX_ATTEMPTS) {
            val candidate = generateCandidate(random)
            val score = ThemeAestheticScore.scoreTheme(candidate)
            attempts++
            if (score.total > bestTotal) {
                bestTotal = score.total
                bestCandidate = candidate
                bestScore = score
            }
            if (bestTotal >= EARLY_STOP_SCORE) break
        }

        val candidate = bestCandidate ?: error("No random theme candidate was generated")
        val score = bestScore ?: error("No random theme score was generated")
        return RandomThemeResult(
            theme = toCustomTheme(candidate, name),
            candidate = candidate,
            score = score,
            attempts = attempts
        )
    }

    /**
     * Convert the complete website palette to this app's theme model in one place.
     *
     * Mapping:
     * - keyboard/background surfaces: `background` and `panel.background`;
     * - normal, functional, and accent keys: `key*`, `specialKey*`, and `accentKey*`;
     * - candidate text/index/comment: `panel.candidateText`, `panel.candidateIndex`, and
     *   `pinner.secondaryTextColor`;
     * - popup, space, divider, clipboard, and active colors: the closest toast/panel colors.
     *
     * Website colors are #RRGGBB. Every normal app color is explicitly promoted to opaque
     * #AARRGGBB; the optional water ripple is explicitly translucent. No background image is used.
     */
    fun toCustomTheme(
        candidate: ThemeCandidate,
        name: String = DEFAULT_THEME_NAME
    ): Theme.Custom {
        val colors = candidate.colors
        fun websiteColor(key: String): Int = opaqueArgb(
            colors[key] ?: error("Theme candidate is missing website color '$key'")
        )

        return Theme.Custom(
            name = name,
            isDark = candidate.isDark,
            backgroundImage = null,
            backgroundColor = websiteColor(WebsiteThemeColorKeys.BACKGROUND),
            barColor = websiteColor(WebsiteThemeColorKeys.PANEL_BACKGROUND),
            keyboardColor = websiteColor(WebsiteThemeColorKeys.BACKGROUND),
            keyBackgroundColor = websiteColor(WebsiteThemeColorKeys.KEY_BACKGROUND),
            keyTextColor = websiteColor(WebsiteThemeColorKeys.KEY_TEXT),
            candidateTextColor = websiteColor(WebsiteThemeColorKeys.PANEL_CANDIDATE_TEXT),
            candidateLabelColor = websiteColor(WebsiteThemeColorKeys.PANEL_CANDIDATE_INDEX),
            candidateCommentColor = websiteColor(WebsiteThemeColorKeys.PINNER_SECONDARY_TEXT_COLOR),
            altKeyBackgroundColor = websiteColor(WebsiteThemeColorKeys.SPECIAL_KEY_BACKGROUND),
            altKeyTextColor = websiteColor(WebsiteThemeColorKeys.SPECIAL_KEY_TEXT),
            accentKeyBackgroundColor = websiteColor(WebsiteThemeColorKeys.ACCENT_KEY_BACKGROUND),
            accentKeyTextColor = websiteColor(WebsiteThemeColorKeys.ACCENT_KEY_TEXT),
            keyPressHighlightColor = withAlpha(
                websiteColor(WebsiteThemeColorKeys.KEY_PRESSED),
                if (candidate.isDark) DARK_KEY_PRESS_ALPHA else LIGHT_KEY_PRESS_ALPHA
            ),
            keyShadowColor = websiteColor(WebsiteThemeColorKeys.KEY_BORDER_STROKE),
            popupBackgroundColor = websiteColor(WebsiteThemeColorKeys.TOAST_BACKGROUND),
            popupTextColor = websiteColor(WebsiteThemeColorKeys.TOAST_TEXT),
            spaceBarColor = websiteColor(WebsiteThemeColorKeys.SPECIAL_KEY_BACKGROUND),
            dividerColor = websiteColor(WebsiteThemeColorKeys.PANEL_CANDIDATE_DIVIDER),
            clipboardEntryColor = websiteColor(WebsiteThemeColorKeys.PANEL_TOOLBAR_PRESSED),
            genericActiveBackgroundColor = websiteColor(WebsiteThemeColorKeys.PANEL_TOOLBAR_ACTIVED),
            genericActiveForegroundColor = websiteColor(WebsiteThemeColorKeys.PANEL_TOOLBAR_TEXT),
            waterRippleColor = withAlpha(
                websiteColor(WebsiteThemeColorKeys.ACCENT_KEY_PRESSED),
                WATER_RIPPLE_ALPHA
            )
        )
    }

    private const val LIGHT_KEY_PRESS_ALPHA = 0x1f
    private const val DARK_KEY_PRESS_ALPHA = 0x33
    private const val WATER_RIPPLE_ALPHA = 0x80

    private fun opaqueArgb(hex: String): Int {
        val rgb = ThemeAestheticScore.hexToRgb(hex)
        return (0xff shl 24) or (rgb.red shl 16) or (rgb.green shl 8) or rgb.blue
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00ffffff) or ((alpha.coerceIn(0, 255)) shl 24)

    private data class PaletteParameters(
        val bgL: Double,
        val bgS: Double,
        val keyBgL: Double,
        val keyBgS: Double,
        val keyTextL: Double,
        val keyTextS: Double,
        val borderL: Double,
        val borderS: Double,
        val accentL: Double,
        val accentS: Double,
        val panelBgL: Double,
        val panelBgS: Double,
        val pinnerBgL: Double,
        val pinnerBgS: Double
    )
}

/** Result of best-of-100 generation, retaining both website and app representations. */
data class RandomThemeResult(
    val theme: Theme.Custom,
    val candidate: ThemeCandidate,
    val score: ThemeScore,
    val attempts: Int
) {
    val colors: Map<String, String>
        get() = candidate.colors
    val scheme: String
        get() = candidate.scheme
    val baseHue: Double
        get() = candidate.baseHue
    val accentHue: Double
        get() = candidate.accentHue
    val baseSat: Double
        get() = candidate.baseSat
    val isDark: Boolean
        get() = candidate.isDark
    val total: Int
        get() = score.total
    val contrast: Int
        get() = score.contrast
    val hue: Int
        get() = score.hue
    val sat: Int
        get() = score.sat
    val light: Int
        get() = score.light
    val minRatio: Double
        get() = score.minRatio
}

fun ThemeCandidate.toCustomTheme(name: String = ThemeRandomizer.DEFAULT_THEME_NAME): Theme.Custom =
    ThemeRandomizer.toCustomTheme(this, name)
