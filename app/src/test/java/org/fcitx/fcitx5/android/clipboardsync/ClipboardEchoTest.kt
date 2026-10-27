/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync

import org.junit.Assert.*
import org.junit.Test

class ClipboardEchoTest {
    private val raw = "https://example.test/?keep=1&utm_source=old-server"
    private val clean = "https://example.test/?keep=1"
    private val normalize: (String) -> String = { if (it == raw) clean else it }

    @Test fun rawRemoteStateFromBeforeTheUpgradeCannotEchoBackAfterFiltering() {
        assertTrue(isClipboardEcho(clean, clean, raw, null, normalize))
    }
    @Test fun rawUploadedStateFromBeforeTheUpgradeCannotEchoBackAfterFiltering() {
        assertTrue(isClipboardEcho(clean, clean, null, raw, normalize))
    }
    @Test fun aSystemNotificationOfTheRawRemoteClipIsAlsoAnEcho() {
        assertTrue(isClipboardEcho(raw, clean, raw, null) { error("Exact matches need no second filtering") })
    }
    @Test fun anAlreadyNormalizedRemoteClipNeedsNoSecondFiltering() {
        assertTrue(isClipboardEcho(raw, clean, clean, null) { error("Canonical matches need no second filtering") })
    }
    @Test fun unrelatedLocalContentIsRetained() {
        assertFalse(isClipboardEcho("new local clip", "new local clip", raw, "older", normalize))
    }
    @Test fun absentRemoteAndUploadedStateCannotHideALocalCopy() {
        assertFalse(isClipboardEcho(raw, clean, null, null) { error("No state needs no filtering") })
    }
}
