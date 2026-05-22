/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.Context
import android.util.AttributeSet
import io.github.rosemoe.sora.lang.brackets.OnlineBracketsMatcher
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorRenderer
import kotlin.math.max

class ArrowTabCodeEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : CodeEditor(context, attrs, defStyleAttr, defStyleRes) {

    override fun onCreateRenderer(): EditorRenderer = ArrowTabEditorRenderer(this)

    // sora's matched-bracket highlight needs a BracketsProvider pushed via the current
    // editorLanguage.analyzeManager. PlainLanguage and TextMate languages without a
    // languageConfiguration never push one, so foundPair stays null and nothing is drawn.
    // Wire a language-agnostic on-demand matcher for the common pairs. Must be called after
    // setEditorLanguage(): updateBracketProvider drops the call if the passed AnalyzeManager
    // isn't the editor's current one.
    fun installOnlineBracketsMatcher() {
        styleDelegate.updateBracketProvider(
            editorLanguage.analyzeManager,
            OnlineBracketsMatcher(BRACKET_PAIRS, BRACKET_SCAN_LIMIT)
        )
    }

    // Upstream indentOrCommitTab() takes a special "at line start" path that calls indentLines(),
    // and indentLines() always pads with literal spaces (the `" ".repeat(needed)` branch) when the
    // current leading whitespace is uniform — so pressing Tab on an empty line inserts spaces even
    // when Language.useTab() is true. Bypass that path: outside of a selection, always commitTab(),
    // which goes through createTabString() and therefore honors useTab().
    override fun indentOrCommitTab() {
        if (cursor.isSelected) {
            indentSelection()
        } else {
            commitTab()
        }
    }

    // Upstream subtracts only width/2, which leaves half a viewport of empty space to the right of
    // the longest line when word-wrap is off. Subtract the full width so max horizontal scroll
    // aligns the longest line's right edge with the viewport's right edge.
    override fun getScrollMaxX(): Int =
        max(
            0f,
            resolveLayoutWidthForScroll() +
                measureTextRegionOffset() +
                resolveTrailingScrollPadding() -
                width.toFloat(),
        ).toInt()

    private fun resolveLayoutWidthForScroll(): Float {
        val raw = layout.layoutWidth.toFloat()
        if (raw in 0f..MAX_REASONABLE_LAYOUT_WIDTH_PX) return raw

        // LineBreakLayout returns a huge sentinel when width cache is not ready yet.
        // Fall back to measuring each line end offset so horizontal range matches real max line width.
        val content = text
        val lineCount = content.lineCount
        var maxWidth = 0f
        for (line in 0 until lineCount) {
            val width = layout.getCharLayoutOffset(line, content.getColumnCount(line))[1]
            if (width > maxWidth) maxWidth = width
        }
        return maxWidth
    }

    private fun resolveTrailingScrollPadding(): Float {
        if (isWordwrap) return 0f
        val charWidth = textPaint.measureText("a")
        return max(0f, charWidth * EXTRA_SCROLL_PADDING_CHARS)
    }

    companion object {
        private const val MAX_REASONABLE_LAYOUT_WIDTH_PX = 1_000_000f
        private const val EXTRA_SCROLL_PADDING_CHARS = 3f
        private val BRACKET_PAIRS = charArrayOf('(', ')', '[', ']', '{', '}')
        // OnlineBracketsMatcher scans outward up to this many chars per direction to find a match.
        // 100k is generous — even for a multi-megabyte file, the matching brace is usually within a
        // few hundred lines, and capping prevents a worst-case scan over the whole file on every
        // selection change.
        private const val BRACKET_SCAN_LIMIT = 100_000
    }
}
