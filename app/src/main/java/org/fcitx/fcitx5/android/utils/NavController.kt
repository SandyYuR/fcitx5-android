/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import androidx.navigation.NavController

fun <T : Any> NavController.navigateWithAnim(route: T) {
    // Destinations own the motion for their entire page, including the header. Forward
    // navigation and predictive back therefore use the same Material transition.
    navigate(route)
}
