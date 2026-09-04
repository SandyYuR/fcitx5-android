/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.icu.text.BreakIterator
import java.util.Locale

data class ClipboardToken(
    val text: String,
    val start: Int,
    val end: Int
)

object ClipboardTextTokenizer {

    /**
     * Longest prefix of the clipboard text that is tokenized.
     *
     * Tokenizing is regex scanning plus an ICU word break per CJK run, and every token also
     * becomes a View in the picker. A clipboard entry can be an entire document, and there was
     * no bound at all: pasting one froze the keyboard for seconds and could exhaust memory.
     * Roughly a screenful of dense text is far more than anyone picks words from by hand.
     */
    const val MAX_TOKENIZE_LENGTH = 4_000

    /** Upper bound on the number of tokens produced, whatever the input. */
    const val MAX_TOKENS = 1_500

    private fun createWordBreaker() =
        BreakIterator.getWordInstance(Locale.CHINESE)
    private val coarseTokenPattern = Regex(
        """\p{IsHan}+|[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*|[~\\/]+|[^\s]"""
    )
    private val hanPattern = Regex("""^\p{IsHan}+$""")

    /**
     * Split [text] into selectable tokens, bounded by [MAX_TOKENIZE_LENGTH] and [MAX_TOKENS].
     *
     * Token offsets refer to the *normalized* text (CRLF collapsed to LF), which is what
     * [joinSelection] must be given as `sourceText`.
     */
    fun tokenize(text: String): List<ClipboardToken> {
        if (text.isBlank()) return emptyList()
        val normalized = text.replace("\r\n", "\n").take(MAX_TOKENIZE_LENGTH)
        val tokens = mutableListOf<ClipboardToken>()
        for (match in coarseTokenPattern.findAll(normalized)) {
            if (tokens.size >= MAX_TOKENS) break
            val chunk = match.value
            val start = match.range.first
            if (hanPattern.matches(chunk)) {
                appendHanTokens(tokens, chunk, start)
            } else {
                tokens += ClipboardToken(chunk, start, start + chunk.length)
            }
        }
        // appendHanTokens can push past the limit within one CJK run.
        return if (tokens.size > MAX_TOKENS) tokens.subList(0, MAX_TOKENS).toList() else tokens
    }

    /** The text [tokenize] offsets refer to; also what [joinSelection] expects. */
    fun normalizeForTokens(text: String): String =
        text.replace("\r\n", "\n").take(MAX_TOKENIZE_LENGTH)

    fun joinSelection(sourceText: String, tokens: List<ClipboardToken>): String {
        if (tokens.isEmpty()) return ""
        val sorted = tokens.sortedBy { it.start }
        val builder = StringBuilder()
        sorted.forEachIndexed { index, token ->
            if (index > 0) {
                builder.append(joinerBetween(sourceText, sorted[index - 1], token))
            }
            builder.append(token.text)
        }
        return builder.toString()
    }

    private fun appendHanTokens(
        output: MutableList<ClipboardToken>,
        chunk: String,
        baseStart: Int
    ) {
        val words = runCatching {
            val breaker = createWordBreaker()
            val words = mutableListOf<String>()
            breaker.setText(chunk)
            var start = breaker.first()
            var end = breaker.next()
            while (end != BreakIterator.DONE) {
                val word = chunk.substring(start, end)
                if (word.isNotBlank()) {
                    words += word
                }
                start = end
                end = breaker.next()
            }
            words
        }.getOrElse { listOf(chunk) }
        if (words.isEmpty()) {
            output += ClipboardToken(chunk, baseStart, baseStart + chunk.length)
            return
        }

        var cursor = 0
        for (word in words) {
            val relativeStart = chunk.indexOf(word, cursor)
                .takeIf { it >= 0 }
                ?: chunk.indexOf(word)
            if (relativeStart < 0) {
                output += ClipboardToken(chunk, baseStart, baseStart + chunk.length)
                return
            }
            val relativeEnd = relativeStart + word.length
            output += ClipboardToken(word, baseStart + relativeStart, baseStart + relativeEnd)
            cursor = relativeEnd
        }
    }

    private fun joinerBetween(
        sourceText: String,
        previous: ClipboardToken,
        current: ClipboardToken
    ): String {
        if (current.start <= previous.end || previous.end > sourceText.length || current.start > sourceText.length) {
            return ""
        }
        val gap = sourceText.substring(previous.end, current.start)
        return when {
            gap.any { it == '\n' } -> "\n"
            gap.any(Char::isWhitespace) -> " "
            else -> ""
        }
    }
}
