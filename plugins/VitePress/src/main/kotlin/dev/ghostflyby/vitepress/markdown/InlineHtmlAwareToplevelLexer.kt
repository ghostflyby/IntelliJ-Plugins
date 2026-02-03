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
 * Any fragment that forms a balanced HTML inline element (using the same rules the plugin uses in the parser)
 * is emitted as a single `HTML_BLOCK_CONTENT` token; surrounding text keeps the original token type.
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
                segments += Segment(htmlRange.start, htmlRange.end, MarkdownTokenTypes.HTML_BLOCK_CONTENT)
                pos = htmlRange.end
            } else {
                // Not a valid HTML start; emit the '<' as part of the original token.
                val single = min(nextLt + 1, tokenEnd)
                segments += Segment(nextLt, single, type)
                pos = single
            }
        }
    }

    private fun findHtmlRange(start: Int, limit: Int): Range? {
        val firstClose = buffer.indexOf('>', start + 1).takeIf { it in (start + 1) until limit } ?: return null
        val openingText = buffer.subSequence(start, firstClose + 1).toString()
        val opening = parseTag(openingText) ?: return null
        if (opening.isClosing) return null
        if (opening.closesImmediately) return Range(start, firstClose + 1)

        var depth = 1
        var searchPos = firstClose + 1
        while (searchPos < limit) {
            val lt = buffer.indexOf('<', searchPos)
            if (lt == -1 || lt >= limit) break
            val gt = buffer.indexOf('>', lt + 1).takeIf { it in (lt + 1) until limit } ?: break
            val tagText = buffer.subSequence(lt, gt + 1).toString()
            val tag = parseTag(tagText)
            if (tag != null && !tag.closesImmediately && opening.nameEquals(tag.name)) {
                if (tag.isClosing) {
                    depth--
                    if (depth == 0) {
                        return Range(start, gt + 1)
                    }
                } else {
                    depth++
                }
            }
            searchPos = gt + 1
        }
        return null
    }

    private fun parseTag(text: String): TagData? {
        if (!text.startsWith("<")) return null

        if (text.startsWith("<!--") || text.startsWith("<![CDATA[", ignoreCase = true) ||
            text.startsWith("<?") || (text.startsWith("<!", ignoreCase = true) && !text.startsWith("</"))
        ) {
            return TagData(name = null, isClosing = false, closesImmediately = true)
        }

        val isClosing = text.startsWith("</")
        val nameMatch = TAG_NAME_REGEX.find(text) ?: return TagData(null, isClosing, closesImmediately = true)
        val name = nameMatch.groupValues[1]

        val trimmedEnd = text.trimEnd()
        val explicitlySelfClosing = trimmedEnd.endsWith("/>")
        val isVoid = name.lowercase() in VOID_TAGS

        return TagData(
            name = name,
            isClosing = isClosing,
            closesImmediately = explicitlySelfClosing || isVoid,
        )
    }

    private data class Segment(val start: Int, val end: Int, val type: IElementType)
    private data class Range(val start: Int, val end: Int)

    private data class TagData(
        val name: String?,
        val isClosing: Boolean,
        val closesImmediately: Boolean,
    ) {
        fun nameEquals(other: String?): Boolean {
            if (name == null || other == null) return false
            return name.equals(other, ignoreCase = true)
        }
    }

    companion object {
        internal val VOID_TAGS: Set<String> = setOf(
            "area",
            "base",
            "br",
            "col",
            "embed",
            "hr",
            "img",
            "input",
            "keygen",
            "link",
            "meta",
            "param",
            "source",
            "track",
            "wbr",
        )

        internal val TAG_NAME_REGEX = Regex("^</?\\s*([A-Za-z][\\w:-]*)")
    }
}
