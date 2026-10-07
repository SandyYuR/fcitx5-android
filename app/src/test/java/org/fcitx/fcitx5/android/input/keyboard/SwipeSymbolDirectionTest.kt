/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeSymbolDirectionTest {
    @Test
    fun autoAcceptsBothPhysicalDirections() {
        assertTrue(SwipeSymbolDirection.Auto.checkY(-1))
        assertTrue(SwipeSymbolDirection.Auto.checkY(1))
        assertFalse(SwipeSymbolDirection.Auto.checkY(0))
    }

    @Test
    fun legacyDirectionsKeepTheirOriginalConstraint() {
        assertTrue(SwipeSymbolDirection.Up.checkY(-1))
        assertFalse(SwipeSymbolDirection.Up.checkY(1))
        assertTrue(SwipeSymbolDirection.Down.checkY(1))
        assertFalse(SwipeSymbolDirection.Down.checkY(-1))
        assertFalse(SwipeSymbolDirection.Disabled.checkY(-1))
        assertFalse(SwipeSymbolDirection.Disabled.checkY(1))
    }
}
