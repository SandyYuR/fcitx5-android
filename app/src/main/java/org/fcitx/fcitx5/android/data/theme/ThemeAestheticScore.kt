/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** RGB components in the same 0..255 representation used by the website. */
data class RgbColor(
    val red: Int,
    val green: Int,
    val blue: Int
)

/** HSL components: hue in degrees, saturation and lightness in percent. */
data class HslColor(
    val hue: Double,
    val saturation: Double,
    val lightness: Double
)

/** The complete color map emitted by the website candidate generator. */
object WebsiteThemeColorKeys {
    const val KEY_BACKGROUND = "keyBackground"
    const val KEY_PRESSED = "keyPressed"
    const val KEY_BORDER_STROKE = "keyBorderStroke"
    const val KEY_TEXT = "keyText"
    const val SPECIAL_KEY_BACKGROUND = "specialKeyBackground"
    const val SPECIAL_KEY_PRESSED = "specialKeyPressed"
    const val SPECIAL_KEY_BORDER_STROKE = "specialKeyBorderStroke"
    const val SPECIAL_KEY_TEXT = "specialKeyText"
    const val ACCENT_KEY_BACKGROUND = "accentKeyBackground"
    const val ACCENT_KEY_PRESSED = "accentKeyPressed"
    const val ACCENT_KEY_BORDER_STROKE = "accentKeyBorderStroke"
    const val ACCENT_KEY_TEXT = "accentKeyText"
    const val ALT_TEXT = "altText"
    const val BACKGROUND = "background"
    const val PANEL_BACKGROUND = "panel.background"
    const val PANEL_TOOLBAR_TEXT = "panel.toolbarText"
    const val PANEL_TOOLBAR_ACTIVED = "panel.toolbarActived"
    const val PANEL_TOOLBAR_ICON = "panel.toolbarIcon"
    const val PANEL_CANDIDATE_BACKGROUND = "panel.candidateBackground"
    const val PANEL_CANDIDATE_TEXT = "panel.candidateText"
    const val PANEL_CANDIDATE_INDEX = "panel.candidateIndex"
    const val PANEL_CANDIDATE_DIVIDER = "panel.candidateDivider"
    const val PANEL_TOOLBAR_PRESSED = "panel.toolbarPressed"
    const val PINNER_BACKGROUND = "pinner.background"
    const val PINNER_TEXT_COLOR = "pinner.textColor"
    const val PINNER_SECONDARY_TEXT_COLOR = "pinner.secondaryTextColor"
    const val TOAST_BACKGROUND = "toastBackground"
    const val TOAST_TEXT = "toastText"
}

/** Metadata retained from the website's candidate object. */
data class ThemeCandidateMetadata(
    val scheme: String,
    val baseHue: Double,
    val accentHue: Double,
    val baseSat: Double,
    val isDark: Boolean,
    val surfaceStyle: String
)

/**
 * A website theme candidate. [colors] deliberately remains the website's complete hex/RGB map;
 * scoring must see the same values that the website scores, before the app-specific mapping.
 */
data class ThemeCandidate(
    val colors: Map<String, String>,
    val scheme: String,
    val baseHue: Double = 0.0,
    val accentHue: Double = 0.0,
    val baseSat: Double = 0.0,
    val isDark: Boolean = false,
    val surfaceStyle: String = "Raised"
) {
    val metadata: ThemeCandidateMetadata
        get() = ThemeCandidateMetadata(scheme, baseHue, accentHue, baseSat, isDark, surfaceStyle)

    val meta: ThemeCandidateMetadata
        get() = metadata
}

/** The rounded score values returned by the website's scoreTheme function. */
data class ThemeScore(
    val total: Int,
    val contrast: Int,
    val hue: Int,
    val sat: Int,
    val light: Int,
    val minRatio: Double
) {
    val saturation: Int
        get() = sat

    val lightness: Int
        get() = light

    val minRatioText: String
        get() = String.format(Locale.ROOT, "%.2f", minRatio)
}

/** A candidate and its score, useful when scoring outside the randomizer. */
data class ScoredThemeCandidate(
    val candidate: ThemeCandidate,
    val score: ThemeScore
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

object ThemeAestheticScore {

    /** Port of the website's HSL-to-RGB conversion, including its input clamping. */
    fun hslToRgb(hue: Double, saturation: Double, lightness: Double): RgbColor {
        val h = ((hue % 360.0) + 360.0) % 360.0
        val s = saturation.coerceIn(0.0, 100.0) / 100.0
        val l = lightness.coerceIn(0.0, 100.0) / 100.0
        val c = (1.0 - abs(2.0 * l - 1.0)) * s
        val x = c * (1.0 - abs((h / 60.0 % 2.0) - 1.0))
        val m = l - c / 2.0
        val (r, g, b) = when {
            h < 60.0 -> Triple(c, x, 0.0)
            h < 120.0 -> Triple(x, c, 0.0)
            h < 180.0 -> Triple(0.0, c, x)
            h < 240.0 -> Triple(0.0, x, c)
            h < 300.0 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return RgbColor(
            jsRound((r + m) * 255.0),
            jsRound((g + m) * 255.0),
            jsRound((b + m) * 255.0)
        )
    }

    /** Port of the website's lower-case #RRGGBB formatter. */
    fun hslToHex(hue: Double, saturation: Double, lightness: Double): String =
        hslToRgb(hue, saturation, lightness).let { rgb ->
            "#" + listOf(rgb.red, rgb.green, rgb.blue)
                .joinToString("") { it.toString(16).padStart(2, '0') }
        }

    /** Port of the website's hex-to-RGB handling, including optional #AARRGGBB input. */
    fun hexToRgb(hex: String): RgbColor {
        var value = hex.removePrefix("#")
        if (value.length == 8) value = value.substring(2)
        if (value.length == 3) {
            value = buildString(6) {
                value.forEach { append(it).append(it) }
            }
        }
        val number = value.toLongOrNull(16) ?: 0L
        return RgbColor(
            ((number shr 16) and 0xff).toInt(),
            ((number shr 8) and 0xff).toInt(),
            (number and 0xff).toInt()
        )
    }

    fun rgbToHsl(red: Int, green: Int, blue: Int): HslColor =
        rgbToHsl(RgbColor(red, green, blue))

    /** Port of the website's RGB-to-HSL conversion. */
    fun rgbToHsl(rgb: RgbColor): HslColor {
        val r = rgb.red / 255.0
        val g = rgb.green / 255.0
        val b = rgb.blue / 255.0
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        var h = 0.0
        var s = 0.0
        val l = (maxChannel + minChannel) / 2.0
        if (maxChannel != minChannel) {
            val d = maxChannel - minChannel
            s = if (l > 0.5) d / (2.0 - maxChannel - minChannel) else d / (maxChannel + minChannel)
            h = when (maxChannel) {
                r -> ((g - b) / d + if (g < b) 6.0 else 0.0) * 60.0
                g -> ((b - r) / d + 2.0) * 60.0
                else -> ((r - g) / d + 4.0) * 60.0
            }
        }
        return HslColor(h, s * 100.0, l * 100.0)
    }

    /** WCAG relative luminance ported with the website's 0.03928 breakpoint. */
    fun relativeLuminance(rgb: RgbColor): Double {
        fun toLinear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * toLinear(rgb.red) +
            0.7152 * toLinear(rgb.green) +
            0.0722 * toLinear(rgb.blue)
    }

    fun contrastRatio(first: RgbColor, second: RgbColor): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        val lighter = max(firstLuminance, secondLuminance)
        val darker = min(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun contrastRatio(first: String, second: String): Double =
        contrastRatio(hexToRgb(first), hexToRgb(second))

    fun hueDist(first: Double, second: Double): Double {
        val distance = abs(first - second) % 360.0
        return if (distance > 180.0) 360.0 - distance else distance
    }

    /** Exact port of the website's weighted contrast/harmony/saturation/lightness score. */
    fun scoreTheme(candidate: ThemeCandidate): ThemeScore {
        val colors = candidate.colors
        fun color(key: String): String =
            colors[key] ?: error("Theme candidate is missing website color '$key'")

        val contrastPairs = listOf(
            ContrastPair(WebsiteThemeColorKeys.KEY_TEXT, WebsiteThemeColorKeys.KEY_BACKGROUND, 6),
            ContrastPair(WebsiteThemeColorKeys.SPECIAL_KEY_TEXT, WebsiteThemeColorKeys.SPECIAL_KEY_BACKGROUND, 5),
            ContrastPair(WebsiteThemeColorKeys.ACCENT_KEY_TEXT, WebsiteThemeColorKeys.ACCENT_KEY_BACKGROUND, 6),
            ContrastPair(WebsiteThemeColorKeys.PANEL_CANDIDATE_TEXT, WebsiteThemeColorKeys.PANEL_CANDIDATE_BACKGROUND, 4),
            ContrastPair(WebsiteThemeColorKeys.PANEL_TOOLBAR_TEXT, WebsiteThemeColorKeys.PANEL_BACKGROUND, 4),
            ContrastPair(WebsiteThemeColorKeys.PANEL_CANDIDATE_INDEX, WebsiteThemeColorKeys.PANEL_CANDIDATE_BACKGROUND, 3),
            ContrastPair(WebsiteThemeColorKeys.PINNER_TEXT_COLOR, WebsiteThemeColorKeys.PINNER_BACKGROUND, 4),
            ContrastPair(WebsiteThemeColorKeys.PINNER_SECONDARY_TEXT_COLOR, WebsiteThemeColorKeys.PINNER_BACKGROUND, 2),
            ContrastPair(WebsiteThemeColorKeys.ALT_TEXT, WebsiteThemeColorKeys.BACKGROUND, 3)
        )

        var contrastScore = 0.0
        var totalWeight = 0
        var minRatio = 21.0
        contrastPairs.forEach { pair ->
            val ratio = contrastRatio(color(pair.foreground), color(pair.background))
            minRatio = min(minRatio, ratio)
            val ratioScore = when {
                ratio >= 7.0 -> 1.0
                ratio >= 4.5 -> 0.85
                ratio >= 3.0 -> 0.6
                ratio >= 2.0 -> 0.3
                ratio >= 1.5 -> 0.1
                else -> 0.0
            }
            contrastScore += ratioScore * pair.weight
            totalWeight += pair.weight
        }
        contrastScore = (contrastScore / totalWeight) * 40.0
        if (minRatio < 2.0) contrastScore *= 0.25
        else if (minRatio < 3.0) contrastScore *= 0.55

        val baseHue = rgbToHsl(hexToRgb(color(WebsiteThemeColorKeys.KEY_BACKGROUND))).hue
        val accentHue = rgbToHsl(hexToRgb(color(WebsiteThemeColorKeys.ACCENT_KEY_BACKGROUND))).hue
        val panelHue = rgbToHsl(hexToRgb(color(WebsiteThemeColorKeys.PANEL_BACKGROUND))).hue
        val pinnerHue = rgbToHsl(hexToRgb(color(WebsiteThemeColorKeys.PINNER_BACKGROUND))).hue
        val d1 = hueDist(baseHue, accentHue)
        val d2 = hueDist(baseHue, panelHue)
        val d3 = hueDist(baseHue, pinnerHue)

        var hueScore = 25.0
        fun evalHueDist(distance: Double): Double = when {
            distance <= 15.0 -> 1.0
            distance >= 20.0 && distance <= 50.0 -> 1.0
            distance >= 100.0 && distance <= 140.0 -> 0.92
            distance >= 155.0 && distance <= 205.0 -> 0.95
            distance >= 50.0 && distance <= 100.0 -> 0.35
            distance >= 140.0 && distance <= 155.0 -> 0.7
            distance >= 205.0 && distance <= 300.0 -> 0.25
            else -> 0.15
        }
        hueScore *= evalHueDist(d1)
        if (d2 > 60.0) hueScore -= 5.0
        if (d3 > 60.0) hueScore -= 3.0
        if (candidate.scheme == "mono" && d1 > 30.0) hueScore -= 6.0
        hueScore = max(0.0, hueScore)

        val hslValues = colors.values.map { rgbToHsl(hexToRgb(it)) }
        val saturations = hslValues.map { it.saturation }
        val averageSaturation = saturations.average()
        val maxSaturation = saturations.maxOrNull() ?: 0.0
        var saturationScore = 20.0
        if (maxSaturation > 92.0) saturationScore -= 15.0
        else if (maxSaturation > 82.0) saturationScore -= 8.0
        else if (maxSaturation > 72.0) saturationScore -= 3.0
        if (averageSaturation < 22.0 || averageSaturation > 48.0) {
            if (averageSaturation < 12.0 || averageSaturation > 60.0) saturationScore -= 9.0
            else saturationScore -= 3.0
        }
        saturationScore = max(0.0, saturationScore)

        val lightnesses = hslValues.map { it.lightness }
        val averageLightness = lightnesses.average()
        val variance = lightnesses.sumOf { (it - averageLightness).pow(2) } / lightnesses.size
        val lightnessStandardDeviation = sqrt(variance)
        var lightnessScore = 15.0
        if (lightnessStandardDeviation < 8.0) lightnessScore -= 10.0
        else if (lightnessStandardDeviation < 12.0) lightnessScore -= 5.0
        else if (lightnessStandardDeviation >= 15.0 && lightnessStandardDeviation <= 32.0) {
            lightnessScore -= 0.0
        } else if (lightnessStandardDeviation > 42.0) lightnessScore -= 8.0
        else if (lightnessStandardDeviation > 32.0) lightnessScore -= 3.0
        lightnessScore = max(0.0, lightnessScore)

        val total = contrastScore + hueScore + saturationScore + lightnessScore
        return ThemeScore(
            total = jsRound(total),
            contrast = jsRound(contrastScore),
            hue = jsRound(hueScore),
            sat = jsRound(saturationScore),
            light = jsRound(lightnessScore),
            minRatio = minRatio
        )
    }

    fun scoreTheme(colors: Map<String, String>, scheme: String = "mono"): ThemeScore =
        scoreTheme(ThemeCandidate(colors = colors, scheme = scheme))

    private data class ContrastPair(
        val foreground: String,
        val background: String,
        val weight: Int
    )

    private fun jsRound(value: Double): Int = floor(value + 0.5).toInt()
}
