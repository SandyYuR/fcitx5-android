/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardPreviewDimensionsTest {
    @Test fun heightUsesTheConfiguredScreenBaseWithoutAnEditorCap() {
        val realSize = KeyboardPreviewDimensions.resolve(2400, 40, 1f, 3f, 8, 12, 1080)
        assertEquals(960, realSize.keyboardHeightPx)
        assertEquals(24, realSize.sidePaddingPx)
        assertEquals(36, realSize.bottomPaddingPx)
        val displayMetrics = KeyboardPreviewDimensions.resolve(2200, 40, 1f, 3f, 8, 12, 1080)
        assertEquals(880, displayMetrics.keyboardHeightPx)
    }

    @Test fun layoutAndLandscapePercentagesKeepTheirOwnAspectRatio() {
        val portrait = KeyboardPreviewDimensions.resolve(2400, 50, 1f, 3f, 4, 10, 1080)
        val landscape = KeyboardPreviewDimensions.resolve(1080, 35, 1f, 3f, 12, 6, 2400)
        assertEquals(1200, portrait.keyboardHeightPx)
        assertEquals(378, landscape.keyboardHeightPx)
        assertEquals(36, landscape.sidePaddingPx)
        assertEquals(18, landscape.bottomPaddingPx)
    }

    @Test fun paddingUsesTheSameSafeWidthLimitAsTheKeyboard() {
        val dimensions = KeyboardPreviewDimensions.resolve(2400, 40, 1f, 3f, 200, 0, 1080)
        assertEquals(270, dimensions.sidePaddingPx)
        assertEquals(960, dimensions.keyboardHeightPx)
    }
}
