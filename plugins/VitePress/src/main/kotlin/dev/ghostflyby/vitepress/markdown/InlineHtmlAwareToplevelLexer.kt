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
import com.intellij.psi.tree.IElementType
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
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
    private val patterns = VitePressHtmlPatterns

    private var buffer: CharSequence = ""
    private var startOffset: Int = 0
    private var endOffset: Int = 0
    private var state: Int = 0

    private val segments = mutableListOf<Segment>()
    private var currentIndex: Int = -1

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.state = 0 // We fully re-lex on every start, so a single state is enough.

        segments.clear()
        currentIndex = -1

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

        // Skip further processing for already-HTML tokens.
        if (type == MarkdownTokenTypes.HTML_BLOCK_CONTENT) {
            segments += Segment(tokenStart, tokenEnd, type)
            return
        }

        var pos = tokenStart
        while (pos < tokenEnd) {
            val nextLt = buffer.indexOf('<', pos).takeIf { it in pos until tokenEnd } ?: tokenEnd
            if (nextLt > pos) {
                segments += Segment(pos, nextLt, type)
            }

            if (nextLt >= tokenEnd) break

            val htmlRange = findHtmlRange(nextLt, tokenEnd)
            if (htmlRange != null) {
                val end = htmlRange.end.coerceAtMost(tokenEnd)
                if (end > htmlRange.start) {
                    segments += Segment(htmlRange.start, end, MarkdownTokenTypes.HTML_BLOCK_CONTENT)
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

    private fun findHtmlRange(start: Int, tokenEnd: Int): Range? {
        if (start + 1 >= tokenEnd) return null

        // Match using the same permissive start patterns as VitePressHtmlBlockProvider (line-based).
        val lineEndExclusive = buffer.indexOf('\n', start).let { newlineIndex ->
            if (newlineIndex == -1 || newlineIndex > tokenEnd) tokenEnd else newlineIndex
        }
        val lineSlice = buffer.subSequence(start, lineEndExclusive).toString()

        val inlineSelfClosing = patterns.INLINE_SELF_CLOSING_REGEX.find(lineSlice)
            ?.takeIf { it.range.first == 0 }
        if (inlineSelfClosing != null) {
            return Range(start, start + inlineSelfClosing.range.last + 1)
        }

        val match = patterns.FIND_START_REGEX.find(lineSlice) ?: return null
        val matchedGroupIndex = match.groups.drop(2).indexOfFirst { it != null }
        if (matchedGroupIndex == -1) return null

        val afterStart = if (matchedGroupIndex == patterns.OPEN_TAG_BLOCK_GROUP_INDEX) {
            val gtIndex = lineSlice.indexOf('>')
            if (gtIndex >= 0) start + gtIndex + 1 else start + match.range.last + 1
        } else {
            start + match.range.last + 1
        }

        // Immediate tags (e.g., "<tag", "<tag />") are considered complete at the end of the line.
        if (matchedGroupIndex == patterns.IMMEDIATE_TAG_GROUP_INDEX) {
            return Range(start, lineEndExclusive)
        }

        if (matchedGroupIndex == patterns.ENTITY_GROUP_INDEX) {
            return Range(start, afterStart)
        }

        val closeRegex = patterns.OPEN_CLOSE_REGEXES[matchedGroupIndex].second ?: return Range(start, afterStart)

        val searchSlice = buffer.subSequence(afterStart, tokenEnd).toString()
        val closeMatch = closeRegex.find(searchSlice)
        val end = if (closeMatch != null) {
            afterStart + closeMatch.range.last + 1
        } else {
            tokenEnd
        }

        return Range(start, end)
    }

    private data class Segment(val start: Int, val end: Int, val type: IElementType)
    private data class Range(val start: Int, val end: Int)
}
