/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/**
 * Map a displayed code-point position to raw UTF-8 bytes. Only literal input,
 * separator decorations and the known caret/end boundaries are invertible.
 * Arbitrary preedit transforms return null instead of guessing a raw position.
 */
internal fun preeditCursorToRawBytes(
    preedit: String,
    preeditCaretUtf16: Int,
    rawInput: String,
    rawCaretBytes: Int,
    positionCodePoints: Int,
): Int? {
    if (positionCodePoints !in 0..preedit.codePointCount(0, preedit.length)) return null
    if (preeditCaretUtf16 !in 0..preedit.length ||
        (preeditCaretUtf16 in 1 until preedit.length && preedit[preeditCaretUtf16].isLowSurrogate() &&
            preedit[preeditCaretUtf16 - 1].isHighSurrogate())) return null
    val rawCaret = utf16Boundary(rawInput, rawCaretBytes) ?: return null
    val target = preedit.offsetByCodePoints(0, positionCodePoints)
    if (target == preeditCaretUtf16) return rawCaretBytes
    if (target == 0) return 0
    if (target == preedit.length) return rawInput.toByteArray(Charsets.UTF_8).size
    val rawPosition = if (target < preeditCaretUtf16) {
        mapDecoratedInput(preedit.substring(0, preeditCaretUtf16), rawInput.substring(0, rawCaret), target)
    } else {
        mapDecoratedInput(preedit.substring(preeditCaretUtf16), rawInput.substring(rawCaret),
            target - preeditCaretUtf16)?.plus(rawCaret)
    } ?: return null
    return rawInput.substring(0, rawPosition).toByteArray(Charsets.UTF_8).size
}

private fun utf16Boundary(text: String, bytes: Int): Int? {
    if (bytes < 0) return null
    var index = 0
    var consumed = 0
    while (index < text.length && consumed < bytes) {
        val cp = text.codePointAt(index)
        consumed += when {
            cp < 0x80 || cp in 0xd800..0xdfff -> 1 // Java UTF-8 replaces an unpaired surrogate with '?'.
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
        index += Character.charCount(cp)
    }
    return index.takeIf { consumed == bytes }
}

private fun separator(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp) ||
    cp == '\''.code || cp == 0x2019 || cp == 0x00b7 || cp == 0x2038

private fun mapDecoratedInput(display: String, raw: String, target: Int): Int? {
    var displayed = 0
    var input = 0
    var mapped: Int? = if (target == 0) 0 else null
    while (displayed < display.length) {
        val cp = display.codePointAt(displayed)
        val source = if (input < raw.length) raw.codePointAt(input) else -1
        if (cp == source || (source >= 0 && separator(cp) && separator(source))) {
            input += Character.charCount(source)
        } else if (!separator(cp) || raw.contains(String(Character.toChars(cp)))) {
            return null
        }
        displayed += Character.charCount(cp)
        if (displayed == target) mapped = input
    }
    return mapped.takeIf { input == raw.length }
}
