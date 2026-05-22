/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.graphics.Canvas
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorRenderer
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Replaces sora-editor's default tab marker (a solid horizontal line) with an arrow → glyph.
 * The horizontal bar can read like a real character (em-dash / strikethrough); an arrow conveys
 * "this is a tab" without ambiguity. Everything else delegates to the parent implementation.
 *
 * Method body mirrors EditorRenderer.drawWhitespaces from sora-editor 0.23.4 — we override at
 * the only fork we care about (the `paintLine` branch for '\t').
 */
class ArrowTabEditorRenderer(private val codeEditor: CodeEditor) : EditorRenderer(codeEditor) {

    override fun drawWhitespaces(
        canvas: Canvas,
        offset: Float,
        line: Int,
        row: Int,
        rowStart: Int,
        rowEnd: Int,
        min: Int,
        max: Int,
    ) {
        val paintStart = maxOf(rowStart, minOf(rowEnd, min))
        val paintEnd = maxOf(rowStart, minOf(rowEnd, max))
        paintOther.color = codeEditor.colorScheme.getColor(EditorColorScheme.NON_PRINTABLE_CHAR)
        // paintOther is shared — the matching-delimiter underline pass leaves strokeWidth at
        // ~row-height, which made our arrow balloon once the cursor touched a bracket. Pin
        // strokeWidth (drawLine ignores Paint.Style, so don't touch that — it bleeds into the
        // line-number text drawn with the same Paint and makes glyphs look bold).
        paintOther.strokeWidth = codeEditor.dpUnit * 1.2f
        if (paintStart >= paintEnd) return

        val spaceWidth = paintGeneral.spaceWidth
        val rowCenter =
            (codeEditor.getRowTop(row) + codeEditor.getRowBottom(row)) / 2f - codeEditor.offsetY
        var currentOffset = offset + measureText(lineBuf, line, rowStart, paintStart - rowStart)
        val chars = lineBuf.value
        var lastPos = paintStart
        var i = paintStart
        while (i < paintEnd) {
            val ch = chars[i]
            var paintCount = 0
            var paintArrow = false
            if (ch == ' ' || ch == '\t') {
                currentOffset += measureText(lineBuf, line, lastPos, i - lastPos)
            }
            if (ch == ' ') {
                paintCount = 1
            } else if (ch == '\t') {
                if ((codeEditor.nonPrintablePaintingFlags and CodeEditor.FLAG_DRAW_TAB_SAME_AS_SPACE) != 0) {
                    paintCount = codeEditor.tabWidth
                } else {
                    paintArrow = true
                }
            }
            for (k in 0 until paintCount) {
                val centerX = currentOffset + spaceWidth * k + spaceWidth / 2f
                bufferedDrawPoints.drawPoint(centerX, rowCenter)
            }
            if (paintArrow) {
                val charWidth = codeEditor.tabWidth * spaceWidth
                val delta = charWidth * 0.05f
                val startX = currentOffset + delta
                val endX = currentOffset + charWidth - delta
                val headSize = minOf(spaceWidth * 0.35f, (endX - startX) * 0.25f)
                canvas.drawLine(startX, rowCenter, endX, rowCenter, paintOther)
                canvas.drawLine(endX, rowCenter, endX - headSize, rowCenter - headSize * 0.7f, paintOther)
                canvas.drawLine(endX, rowCenter, endX - headSize, rowCenter + headSize * 0.7f, paintOther)
            }
            if (ch == ' ' || ch == '\t') {
                currentOffset += if (ch == ' ') spaceWidth else spaceWidth * codeEditor.tabWidth
                lastPos = i + 1
            }
            i++
        }
    }
}
