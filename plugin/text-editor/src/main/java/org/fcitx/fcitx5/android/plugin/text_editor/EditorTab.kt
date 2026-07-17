/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.net.Uri

class EditorTab(
    var uri: Uri,
    var displayName: String,
    var editor: ArrowTabCodeEditor? = null,
    var originalText: String = "",
    var isLargeFile: Boolean = false,
    var isDirty: Boolean = false,
    var loadedLastModified: Long = 0L,
    var loadedFileSize: Long = -1L,
) {
    internal var largeFilePager: TextFileEditActivity.LargeFilePager? = null
    internal var largeFileFullyLoaded: Boolean = false
    internal var largeFileLoadInFlight: Boolean = false
    internal var largeFileDirty: Boolean = false
    internal var suppressLargeFileDirtyTracking: Boolean = false
}
