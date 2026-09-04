/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.clipboardsync

/**
 * Decoded-size estimate for a base64 payload, without decoding it.
 *
 * ClipCascade delivers images and files as base64 inside a STOMP text frame. The receive filter
 * and the hard size cap have to be applied *before* `Base64.decode`, or the allocation the cap
 * exists to prevent has already happened (see A9).
 *
 * Free of Android dependencies so the arithmetic is unit-testable.
 */
object Base64Payload {

    /**
     * Upper bound on the number of bytes [payload] decodes to.
     *
     * Whitespace is excluded: MIME-style base64 wraps every 76 characters, and counting those
     * line breaks overestimated the decoded size by up to ~3%, which rejected payloads sitting
     * just under the user's size filter.
     *
     * The result is an upper bound — padding makes the real size 1 or 2 bytes smaller — so a
     * caller that must be exact still re-checks after decoding.
     */
    fun estimatedDecodedBytes(payload: String): Long {
        val encodedChars = payload.count { !it.isWhitespace() }.toLong()
        return encodedChars / 4L * 3L
    }
}
