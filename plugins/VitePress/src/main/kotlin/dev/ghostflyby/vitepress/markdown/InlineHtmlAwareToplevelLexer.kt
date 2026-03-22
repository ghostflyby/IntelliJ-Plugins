/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.vitepress.markdown

import com.intellij.lexer.LexerBase
import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.plugins.markdown.lang.MarkdownTokenTypeSets
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.lexer.MarkdownToplevelLexer
import kotlin.math.min

/**
 * Wraps the upstream Markdown lexer and further splits tokens to isolate inline HTML fragments.
 *
 * Uses the same permissive HTML start rules as [VitePressHtmlBlockProvider] so partially typed/custom tags
 * still get emitted as `HTML_BLOCK_CONTENT`, which allows Vue/HTML completion and highlighting to kick in early.
 */
internal class InlineHtmlAwareToplevelLexer(
    flavourDescriptor: MarkdownFlavourDescriptor,
) : LexerBase() {

    private val delegate = MarkdownToplevelLexer(flavourDescriptor)

    private var buffer: CharSequence = ""
    private var startOffset: Int = 0
    private var endOffset: Int = 0
    private var state: Int = 0

    private val segments = mutableListOf<Segment>()
    private var currentIndex: Int = -1
    private var consumedTopLevelHtmlEndOffset: Int = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.state = 0 // We fully re-lex on every start, so a single state is enough.

        segments.clear()
        currentIndex = -1
        consumedTopLevelHtmlEndOffset = startOffset

        delegate.start(buffer, startOffset, endOffset, initialState)
        while (delegate.tokenType != null) {
            val type = delegate.tokenType!!
            val tokenStart = delegate.tokenStart
            val tokenEnd = delegate.tokenEnd
            splitAndAdd(type, tokenStart, tokenEnd)
            delegate.advance()
        }
        advance()
    }

    override fun getState(): Int = state

    override fun getTokenType(): IElementType? = segments.getOrNull(currentIndex)?.type

    override fun getTokenStart(): Int = segments.getOrNull(currentIndex)?.start ?: 0

    override fun getTokenEnd(): Int = segments.getOrNull(currentIndex)?.end ?: 0

    override fun advance() {
        currentIndex++
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = endOffset

    private fun splitAndAdd(type: IElementType, tokenStart: Int, tokenEnd: Int) {
        if (tokenStart >= tokenEnd) return
        if (tokenEnd <= consumedTopLevelHtmlEndOffset) return

        val effectiveStart = maxOf(tokenStart, consumedTopLevelHtmlEndOffset)
        if (effectiveStart >= tokenEnd) return

        if (type == MarkdownTokenTypes.HTML_BLOCK_CONTENT) {
            appendTopLevelHtmlSegment(effectiveStart, tokenEnd)
            return
        }

        if (!SPLITTABLE_TOKEN_TYPES.contains(type)) {
            segments += Segment(effectiveStart, tokenEnd, type)
            return
        }

        var pos = effectiveStart
        while (pos < tokenEnd) {
            val nextLt = buffer.indexOf('<', pos).takeIf { it in pos until tokenEnd } ?: tokenEnd
            if (nextLt > pos) {
                segments += Segment(pos, nextLt, type)
            }

            if (nextLt >= tokenEnd) break

            val htmlRange = findInlineHtmlRange(nextLt, tokenEnd)
            if (htmlRange != null) {
                val end = htmlRange.endOffset.coerceAtMost(tokenEnd)
                if (end > htmlRange.startOffset) {
                    segments += Segment(htmlRange.startOffset, end, MarkdownTokenTypes.HTML_BLOCK_CONTENT)
                    pos = end
                } else {
                    // Fallback: avoid infinite loop on malformed ranges.
                    segments += Segment(nextLt, nextLt + 1, type)
                    pos = nextLt + 1
                }
            } else {
                // Not a valid HTML start; emit the '<' as part of the original token.
                val single = min(nextLt + 1, tokenEnd)
                segments += Segment(nextLt, single, type)
                pos = single
            }
        }
    }

    private fun appendTopLevelHtmlSegment(tokenStart: Int, tokenEnd: Int) {
        val expandedRange = findTopLevelHtmlRange(tokenStart)
        if (expandedRange != null) {
            segments += Segment(
                expandedRange.startOffset,
                expandedRange.endOffset,
                MarkdownTokenTypes.HTML_BLOCK_CONTENT,
            )
            consumedTopLevelHtmlEndOffset = expandedRange.endOffset
        } else {
            segments += Segment(tokenStart, tokenEnd, MarkdownTokenTypes.HTML_BLOCK_CONTENT)
        }
    }

    private fun findTopLevelHtmlRange(start: Int): TextRange? {
        return findHtmlRange(start, endOffset)?.takeIf { it.startOffset == start }
    }

    private fun findInlineHtmlRange(start: Int, tokenEnd: Int): TextRange? {
        return findHtmlRange(start, tokenEnd)
    }

    private fun findHtmlRange(start: Int, searchEnd: Int): TextRange? {
        return findVitePressHtmlRange(buffer, start, searchEnd)
    }

    private data class Segment(val start: Int, val end: Int, val type: IElementType)

    private companion object {
        private val SPLITTABLE_TOKEN_TYPES: TokenSet = TokenSet.orSet(
            MarkdownTokenTypeSets.HEADER_CONTENT,
            TokenSet.create(
                org.intellij.plugins.markdown.lang.MarkdownElementTypes.LINK_TEXT,
                MarkdownTokenTypes.TEXT,
                MarkdownTokenTypes.WHITE_SPACE,
                MarkdownTokenTypes.EOL,
            ),
        )
    }
}
