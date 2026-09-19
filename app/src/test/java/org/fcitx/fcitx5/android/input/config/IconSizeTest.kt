/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconSizeTest {

    @Test
    fun iconThatAlreadyFitsIsLeftAlone() {
        assertNull(fitIconSize(96, 96, 96))
        assertNull(fitIconSize(24, 24, 96))
    }

    @Test
    fun oversizedSquareIsFittedToTheBox() {
        val size = fitIconSize(512, 512, 96)
        assertEquals(96, size!!.width)
        assertEquals(96, size.height)
    }

    @Test
    fun aspectRatioIsPreserved() {
        val wide = fitIconSize(1024, 512, 96)!!
        assertEquals(96, wide.width)
        assertEquals(48, wide.height)

        val tall = fitIconSize(512, 1024, 96)!!
        assertEquals(48, tall.width)
        assertEquals(96, tall.height)
    }

    @Test
    fun extremeAspectRatioNeverProducesZeroPixels() {
        // 10000x3 scaled so the long side fits gives a sub-pixel short side.
        val size = fitIconSize(10000, 3, 96)!!
        assertEquals(96, size.width)
        assertEquals(1, size.height)
    }

    @Test
    fun degenerateDimensionsDoNotCountAsOversized() {
        // Zero/negative dimensions are clamped to 1px, which already fits the box.
        assertNull(fitIconSize(0, 0, 96))
        assertNull(fitIconSize(-5, 10, 96))
    }

    @Test
    fun degenerateTargetStillProducesAPositiveSize() {
        val size = fitIconSize(512, 512, 0)!!
        assertEquals(1, size.width)
        assertEquals(1, size.height)
    }
}
