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
     * Account for omitted padding as accepted by Android's decoder. In particular, two or
     * three Base64 characters produce one or two bytes, rather than zero. Invalid Base64 is
     * still rejected by the decoder; callers also re-check the actual decoded length.
     */
    fun estimatedDecodedBytes(payload: String): Long {
        var encodedChars = 0L
        var previous = '\u0000'
        var last = '\u0000'
        for (char in payload) {
            if (char.isWhitespace()) continue
            encodedChars++
            previous = last
            last = char
        }
        val remainder = encodedChars % 4
        val padding = if (remainder == 0L) {
            (if (last == '=') 1 else 0) + (if (previous == '=') 1 else 0)
        } else 0
        val tail = when (remainder) { 0L -> 0; 3L -> 2; else -> 1 }
        return (encodedChars / 4L * 3L + tail - padding).coerceAtLeast(0)
    }
}
