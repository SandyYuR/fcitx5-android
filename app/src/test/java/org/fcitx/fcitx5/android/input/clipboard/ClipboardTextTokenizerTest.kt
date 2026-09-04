/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D13: tokenizing a clipboard entry must stay bounded.
 *
 * A clipboard entry can be an entire document; the tokenizer is regex scanning plus an ICU word
 * break per CJK run, and each token becomes a View. With no bound, pasting one froze the
 * keyboard for seconds.
 *
 * **CJK segmentation is not asserted here.** [ClipboardTextTokenizer] splits Han runs with
 * `android.icu.text.BreakIterator`, which does not exist on the JVM: under unit tests
 * (`returnDefaultValues = true`) the factory returns null, the tokenizer's own runCatching falls
 * back to keeping the run whole, and the exact split becomes an artifact of the test environment
 * rather than of this code. The assertions below hold either way.
 */
class ClipboardTextTokenizerTest {

    @Test
    fun asciiWordsBecomeTheirOwnTokens() {
        val tokens = ClipboardTextTokenizer.tokenize("hello 世界 foo")
        val texts = tokens.map { it.text }
        assertTrue("expected 'hello' among $texts", "hello" in texts)
        assertTrue("expected 'foo' among $texts", "foo" in texts)
    }

    @Test
    fun everyTokenOffsetMatchesItsText() {
        val raw = "hello 世界 foo bar.baz"
        val normalized = ClipboardTextTokenizer.normalizeForTokens(raw)
        ClipboardTextTokenizer.tokenize(raw).forEach { token ->
            assertTrue("offsets out of range: $token", token.start in 0..normalized.length)
            assertTrue("offsets out of range: $token", token.end in token.start..normalized.length)
            assertEquals(
                "token text must match its own offsets",
                token.text,
                normalized.substring(token.start, token.end)
            )
        }
    }

    @Test
    fun selectingEveryTokenReconstructsTheText() {
        val raw = "hello 世界 foo"
        val normalized = ClipboardTextTokenizer.normalizeForTokens(raw)
        val tokens = ClipboardTextTokenizer.tokenize(raw)
        assertEquals(raw, ClipboardTextTokenizer.joinSelection(normalized, tokens))
    }

    @Test
    fun oversizedInputIsTruncatedToTheLimit() {
        val huge = "a".repeat(ClipboardTextTokenizer.MAX_TOKENIZE_LENGTH * 3)
        val tokens = ClipboardTextTokenizer.tokenize(huge)
        assertTrue(tokens.size <= ClipboardTextTokenizer.MAX_TOKENS)
        tokens.forEach { token ->
            assertTrue(
                "offset ${token.end} beyond the truncation limit",
                token.end <= ClipboardTextTokenizer.MAX_TOKENIZE_LENGTH
            )
        }
    }

    @Test
    fun tokenCountIsBoundedForDenseInput() {
        // The coarse pattern's last branch is [^\s], so each punctuation character becomes its
        // own token. That is the real worst case, and unlike CJK it does not depend on ICU.
        val dense = "!".repeat(ClipboardTextTokenizer.MAX_TOKENIZE_LENGTH)
        val tokens = ClipboardTextTokenizer.tokenize(dense)
        assertEquals(
            "the token cap must actually be reached by this input",
            ClipboardTextTokenizer.MAX_TOKENS,
            tokens.size
        )
    }

    @Test
    fun crlfIsCollapsedBeforeOffsetsAreAssigned() {
        val tokens = ClipboardTextTokenizer.tokenize("a\r\nb")
        assertEquals(listOf("a", "b"), tokens.map { it.text })
        assertEquals("a\nb", ClipboardTextTokenizer.normalizeForTokens("a\r\nb"))
    }

    @Test
    fun blankInputYieldsNothing() {
        assertTrue(ClipboardTextTokenizer.tokenize("").isEmpty())
        assertTrue(ClipboardTextTokenizer.tokenize("   \n  ").isEmpty())
    }
}
