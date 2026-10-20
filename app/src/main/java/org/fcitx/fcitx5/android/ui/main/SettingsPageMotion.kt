/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.animation.TimeInterpolator
import androidx.fragment.app.Fragment
import androidx.transition.Transition
import com.google.android.material.transition.FadeProvider
import com.google.android.material.transition.MaterialFade
import com.google.android.material.transition.MaterialSharedAxis
import com.google.android.material.transition.ScaleProvider
import com.google.android.material.transition.SlideDistanceProvider
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences.Style
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** All transitions target the page's transition group, keeping title and content together. */
internal fun Fragment.applySettingsPageMotion(
    selection: SettingsUiMotionPreferences.Selection = SettingsUiMotionPreferences.read(requireContext())
) {
    val metrics = resources.displayMetrics
    val viewport = parentFragment?.view
    val width = viewport?.width?.takeIf { it > 0 } ?: metrics.widthPixels
    val height = viewport?.height?.takeIf { it > 0 } ?: metrics.heightPixels

    fun sharedAxis(axis: Int, forward: Boolean, covered: Boolean) =
        MaterialSharedAxis(axis, forward).apply {
            when (val provider = primaryAnimatorProvider) {
                is SlideDistanceProvider -> {
                    val extent = if (axis == MaterialSharedAxis.X) width else height
                    provider.slideDistance = (extent * if (covered) 0.25f else 1f).roundToInt()
                    // Keep pages opaque. Fragment's disappearing page may live in an overlay,
                    // so a forward exit must leave the viewport before that overlay is removed.
                    secondaryAnimatorProvider = null
                }
                is ScaleProvider -> {
                    provider.incomingStartScale = 0.94f
                    provider.outgoingEndScale = 1.04f
                    secondaryAnimatorProvider = FadeProvider()
                }
            }
        }

    fun transition(style: Style, forward: Boolean, covered: Boolean): Transition? {
        val result: Transition? = when (style) {
            Style.HORIZONTAL -> sharedAxis(MaterialSharedAxis.X, forward, covered)
            Style.VERTICAL -> sharedAxis(MaterialSharedAxis.Y, forward, covered)
            Style.SCALE -> sharedAxis(MaterialSharedAxis.Z, forward, covered)
            Style.FADE -> MaterialFade().apply {
                primaryAnimatorProvider.incomingEndThreshold = 1f
                secondaryAnimatorProvider = null
            }
            Style.NONE -> null
            Style.FOLLOW -> error("Resolve the return style before creating a transition")
        }
        return result?.apply {
            duration = selection.durationMillis
            interpolator = SettingsNavigationEasing
        }
    }

    enterTransition = transition(selection.enter, forward = true, covered = false)
    exitTransition = transition(selection.enter, forward = true, covered = false)
    reenterTransition = transition(selection.returnStyle, forward = false, covered = true)
    returnTransition = transition(selection.returnStyle, forward = false, covered = false)
}

/** Damped-spring step response matching Miuix's 0.8 response / 0.95 damping navigation timing. */
private object SettingsNavigationEasing : TimeInterpolator {
    private val angularFrequency = 2.0 * PI / 0.8
    private val decay = angularFrequency * 0.95
    private val frequency = angularFrequency * sqrt(1.0 - 0.95 * 0.95)
    private val endResponse = 1.0 - exp(-decay) * (cos(frequency) + decay / frequency * sin(frequency))

    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f
        val phase = frequency * input
        return ((1.0 - exp(-decay * input) * (cos(phase) + decay / frequency * sin(phase))) / endResponse)
            .toFloat().coerceIn(0f, 1f)
    }
}
