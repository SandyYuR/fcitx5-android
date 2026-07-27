/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

enum class AuxBarPosition { Top, Bottom, Left, Right, AbovePreedit }

data class AuxBarConfig(
    val position: AuxBarPosition,
    val sizePercent: Float
)
