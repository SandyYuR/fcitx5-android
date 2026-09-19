/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

/**
 * Target size (in pixels) for one icon after fitting it to a box.
 *
 * Pure data, deliberately free of Android types so the fitting rule stays unit testable on the
 * JVM (see `IconSizeTest`).
 */
internal data class ScaledIconSize(
    val width: Int,
    val height: Int
)

/**
 * Fit an icon of [width]x[height] pixels into a [targetSize]-pixel box, keeping its aspect ratio.
 *
 * Returns `null` when the icon already fits — callers can then keep the original drawable instead
 * of re-allocating a bitmap.
 *
 * This exists because a drawable's *intrinsic* size is what a `wrap_content` image view reports
 * to its parent: an imported file icon that carries a large intrinsic size (an SVG icon has no
 * meaningful pixel size at all and is rasterized at its document size) widens its button far
 * beyond the icon that is actually drawn, leaving blank space on both sides of the icon. Fitting
 * every imported icon to the standard icon box keeps such a button the same width as a built-in
 * one.
 */
internal fun fitIconSize(width: Int, height: Int, targetSize: Int): ScaledIconSize? {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val target = targetSize.coerceAtLeast(1)
    if (w <= target && h <= target) return null
    val scale = minOf(target.toFloat() / w, target.toFloat() / h)
    return ScaledIconSize(
        width = (w * scale).toInt().coerceAtLeast(1),
        height = (h * scale).toInt().coerceAtLeast(1)
    )
}
