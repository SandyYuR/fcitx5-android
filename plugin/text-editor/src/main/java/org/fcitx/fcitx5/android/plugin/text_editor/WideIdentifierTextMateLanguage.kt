/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.completion.CompletionHelper
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.IdentifierAutoComplete
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration

/**
 * Variant of [TextMateLanguage] that broadens the autocomplete word boundary to include `-` and `.`
 * (so YAML/Rime keys like `compile-time` and `chinese.dict` are completable), and seeds the
 * completion list from a buffer scan rather than the analyzer's tokenised identifier set — many
 * grammars (YAML, plain text, .conf) don't emit identifier scopes the analyzer recognises.
 */
class WideIdentifierTextMateLanguage private constructor(
    grammar: IGrammar,
    languageConfiguration: LanguageConfiguration?,
    grammarRegistry: GrammarRegistry,
    themeRegistry: ThemeRegistry,
    collectIdentifiers: Boolean
) : TextMateLanguage(grammar, languageConfiguration, grammarRegistry, themeRegistry, collectIdentifiers) {

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        if (!isAutoCompleteEnabled) return
        val prefix = CompletionHelper.computePrefix(content, position) { ch -> isWordPart(ch) }
        if (prefix.isEmpty()) return
        val identifiers = collectBufferIdentifiers(content, prefix, position)
        getAutoCompleter().requireAutoComplete(content, position, prefix, publisher, identifiers)
    }

    private fun collectBufferIdentifiers(
        content: ContentReference,
        prefix: String,
        position: CharPosition
    ): IdentifierAutoComplete.DisposableIdentifiers {
        val ids = IdentifierAutoComplete.DisposableIdentifiers()
        ids.beginBuilding()
        val seen = HashSet<String>()
        val sb = StringBuilder()
        val lineCount = content.lineCount
        outer@ for (line in 0 until lineCount) {
            val text = content.getLine(line)
            val len = text.length
            var col = 0
            while (col <= len) {
                val c = if (col < len) text[col] else '\u0000'
                if (col < len && isWordPart(c)) {
                    sb.append(c)
                } else if (sb.isNotEmpty()) {
                    val word = sb.toString()
                    sb.setLength(0)
                    // Skip the token the caret is currently inside — completing a word with itself is noise.
                    val wordEndCol = col
                    val wordStartCol = wordEndCol - word.length
                    val isCaretToken = line == position.line &&
                        position.column in wordStartCol..wordEndCol
                    if (!isCaretToken && word.length > 1 && seen.add(word)) {
                        ids.addIdentifier(word)
                        if (seen.size >= MAX_IDENTIFIERS) break@outer
                    }
                }
                col++
            }
        }
        ids.finishBuilding()
        return ids
    }

    companion object {
        private const val MAX_IDENTIFIERS = 2000

        private fun isWordPart(c: Char): Boolean =
            Character.isJavaIdentifierPart(c) || c == '-' || c == '.'

        fun create(scopeName: String): WideIdentifierTextMateLanguage {
            val registry = GrammarRegistry.getInstance()
            val grammar = registry.findGrammar(scopeName)
                ?: throw IllegalArgumentException("Grammar not found for scope $scopeName")
            val langConf = registry.findLanguageConfiguration(grammar.scopeName)
            return WideIdentifierTextMateLanguage(
                grammar,
                langConf,
                registry,
                ThemeRegistry.getInstance(),
                true
            )
        }
    }
}
