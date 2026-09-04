/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.clipboardsync.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A3: percent signs in a storage path must not crash the settings dialog.
 *
 * URLDecoder.decode throws IllegalArgumentException on a bare or truncated escape, and this
 * ran inside an AlertDialog positive-button callback, so the exception reached the framework.
 */
class StoragePathUtilsTest {

    @Test
    fun bareOrTruncatedPercentEscapesDoNotThrow() {
        // "100%" is a perfectly legal directory name.
        assertEquals("/sdcard/Download/100%", StoragePathUtils.formatStoragePath("/sdcard/Download/100%"))
        assertEquals("/%", StoragePathUtils.formatStoragePath("/%"))
        assertEquals("/a%zz", StoragePathUtils.formatStoragePath("/a%zz"))
        // Truncated multi-byte UTF-8 escape.
        assertEquals("/x/%E4%B8", StoragePathUtils.formatStoragePath("/x/%E4%B8"))
        assertEquals("/50%%", StoragePathUtils.formatStoragePath("/50%%"))
    }

    @Test
    fun wellFormedEscapesAreStillDecoded() {
        assertEquals("/sdcard/My Files", StoragePathUtils.formatStoragePath("/sdcard/My%20Files"))
        assertEquals("/sdcard/中文", StoragePathUtils.formatStoragePath("/sdcard/%E4%B8%AD%E6%96%87"))
    }

    @Test
    fun blankInputYieldsNull() {
        assertNull(StoragePathUtils.formatStoragePath(null))
        assertNull(StoragePathUtils.formatStoragePath(""))
        assertNull(StoragePathUtils.formatStoragePath("   "))
    }

    @Test
    fun primaryStoragePrefixIsCollapsed() {
        assertEquals("/", StoragePathUtils.formatStoragePath("/storage/emulated/0"))
        assertEquals("/Download", StoragePathUtils.formatStoragePath("/storage/emulated/0/Download"))
        // Duplicate separators are normalized away.
        assertEquals("/Download/a", StoragePathUtils.formatStoragePath("/storage/emulated/0//Download//a"))
    }

    @Test
    fun relativePathGainsLeadingSlash() {
        assertNotNull(StoragePathUtils.formatStoragePath("Download"))
        assertEquals("/Download", StoragePathUtils.formatStoragePath("Download"))
    }
}
