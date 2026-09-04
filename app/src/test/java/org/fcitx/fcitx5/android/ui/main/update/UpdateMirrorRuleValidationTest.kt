/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.update

import org.fcitx.fcitx5.android.ui.main.update.UpdateRepository.MirrorRuleValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5: a mirror rule must be rejected at save time if applying it would throw.
 *
 * Compiling the pattern is not enough — group references in the replacement are resolved
 * lazily by Regex.replace, so an illegal reference or a trailing backslash only failed at
 * download time, as an exception that the download path's catch could not intercept.
 */
class UpdateMirrorRuleValidationTest {

    @Test
    fun validRuleIsAccepted() {
        assertEquals(
            MirrorRuleValidation.VALID,
            UpdateRepository.validateMirrorRule("github\\.com", "mirror.example")
        )
    }

    @Test
    fun groupReferenceWithMatchingGroupIsAccepted() {
        assertEquals(
            MirrorRuleValidation.VALID,
            UpdateRepository.validateMirrorRule("https://(github\\.com)", "https://mirror.example/\$1")
        )
    }

    @Test
    fun illegalGroupReferenceIsRejected() {
        // Pattern has no capturing group, so $1 in the replacement is out of range.
        assertEquals(
            MirrorRuleValidation.REPLACEMENT_INVALID,
            UpdateRepository.validateMirrorRule("github\\.com", "mirror.example/\$1")
        )
    }

    @Test
    fun trailingBackslashInReplacementIsRejected() {
        assertEquals(
            MirrorRuleValidation.REPLACEMENT_INVALID,
            UpdateRepository.validateMirrorRule("github\\.com", "mirror.example\\")
        )
    }

    @Test
    fun unparseablePatternIsRejected() {
        assertEquals(
            MirrorRuleValidation.PATTERN_INVALID,
            UpdateRepository.validateMirrorRule("(unclosed", "mirror.example")
        )
    }

    @Test
    fun overlongPatternIsRejected() {
        val huge = "a".repeat(UpdateRepository.MAX_MIRROR_PATTERN_LENGTH + 1)
        assertEquals(
            MirrorRuleValidation.PATTERN_INVALID,
            UpdateRepository.validateMirrorRule(huge, "mirror.example")
        )
    }

    @Test
    fun applyMirrorRewritesTheUrl() {
        val rule = MirrorRule("id", "name", "github\\.com", "mirror.example")
        assertEquals(
            "https://mirror.example/owner/repo/releases/download/v1/app.apk",
            UpdateRepository.applyMirror(
                "https://github.com/owner/repo/releases/download/v1/app.apk",
                rule
            )
        )
    }

    @Test
    fun applyMirrorWithoutRuleIsIdentity() {
        val url = "https://github.com/owner/repo/releases/download/v1/app.apk"
        assertEquals(url, UpdateRepository.applyMirror(url, null))
    }

    @Test
    fun hostsSourceUrlMustBeAbsoluteHttp() {
        assertTrue(UpdateRepository.isValidHttpUrl("https://mirror.example/hosts"))
        assertTrue(UpdateRepository.isValidHttpUrl("http://mirror.example/hosts"))
        // C26: a scheme-less string used to reach OkHttp and throw IllegalArgumentException,
        // reported to the user as a generic "update failed".
        assertFalse(UpdateRepository.isValidHttpUrl("mirror.example/hosts"))
        assertFalse(UpdateRepository.isValidHttpUrl(""))
        assertFalse(UpdateRepository.isValidHttpUrl("ftp://mirror.example/hosts"))
        assertFalse(UpdateRepository.isValidHttpUrl("https://"))
    }
}
