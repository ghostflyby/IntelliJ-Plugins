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

package dev.ghostflyby.vitepress

import com.intellij.lexer.Lexer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.templateLanguages.TemplateDataElementType
import com.intellij.psi.templateLanguages.TemplateDataModifications
import dev.ghostflyby.vitepress.markdown.InlineHtmlAwareToplevelLexer
import dev.ghostflyby.vitepress.markdown.VitePressFlavourDescriptor
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownElementTypes.MARKDOWN_OUTER_BLOCK
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes

internal object VitePressTemplateDataElementType : TemplateDataElementType(
    "VITEPRESS_TEMPLATE_DATA",
    VitePressLanguage,
    MarkdownTokenTypes.HTML_BLOCK_CONTENT,
    MARKDOWN_OUTER_BLOCK,
) {
    override fun collectTemplateModifications(sourceCode: CharSequence, baseLexer: Lexer): TemplateDataModifications {
        val outerRanges = mutableListOf<TextRange>()
        baseLexer.start(sourceCode)
        var hasTopLevelMustache = false

        while (baseLexer.tokenType != null) {
            val range = TextRange.create(baseLexer.tokenStart, baseLexer.tokenEnd)
            when (baseLexer.tokenType) {
                MarkdownTokenTypes.HTML_BLOCK_CONTENT -> Unit
                MarkdownElementTypes.PARAGRAPH -> {
                    if (!isPlainParagraphHost(sourceCode, range)) {
                        outerRanges += range
                        baseLexer.advance()
                        continue
                    }
                    val mustacheRanges = collectMustacheRanges(sourceCode, range)
                    if (mustacheRanges.isEmpty()) {
                        outerRanges += range
                    } else {
                        hasTopLevelMustache = true
                        var cursor = range.startOffset
                        mustacheRanges.forEach { mustacheRange ->
                            if (cursor < mustacheRange.startOffset) {
                                outerRanges += TextRange.create(cursor, mustacheRange.startOffset)
                            }
                            cursor = mustacheRange.endOffset
                        }
                        if (cursor < range.endOffset) {
                            outerRanges += TextRange.create(cursor, range.endOffset)
                        }
                    }
                }

                else -> outerRanges += range
            }
            baseLexer.advance()
        }

        val modifications = TemplateDataModifications()
        if (hasTopLevelMustache) {
            modifications.addRangeToRemove(0, TEMPLATE_ROOT_PREFIX)
        }
        outerRanges.forEach { outerRange ->
            modifications.addOuterRange(outerRange)
        }
        if (hasTopLevelMustache) {
            modifications.addRangeToRemove(sourceCode.length, TEMPLATE_ROOT_SUFFIX)
        }
        return modifications
    }

    internal fun buildTemplateDataText(sourceCode: String): CharSequence {
        val modifications =
            collectTemplateModifications(
                sourceCode,
                InlineHtmlAwareToplevelLexer(VitePressFlavourDescriptor),
            )
        val applied = modifications.applyToText(sourceCode, this)
        return applied.first as CharSequence
    }
}

internal fun buildVitePressTemplateDataText(sourceCode: String): CharSequence {
    return VitePressTemplateDataElementType.buildTemplateDataText(sourceCode)
}

private fun collectMustacheRanges(sourceCode: CharSequence, hostRange: TextRange): List<TextRange> {
    val result = mutableListOf<TextRange>()
    var cursor = hostRange.startOffset
    while (cursor < hostRange.endOffset) {
        val start =
            sourceCode.indexOf(TEMPLATE_INTERPOLATION_OPEN, cursor).takeIf { it in cursor until hostRange.endOffset }
                ?: break
        val end =
            sourceCode.indexOf(TEMPLATE_INTERPOLATION_CLOSE, start + TEMPLATE_INTERPOLATION_OPEN.length)
                .takeIf { it in (start + TEMPLATE_INTERPOLATION_OPEN.length)..<hostRange.endOffset }
                ?.plus(TEMPLATE_INTERPOLATION_CLOSE.length)
                ?: hostRange.endOffset
        result += TextRange.create(start, end)
        cursor = end
    }
    return result
}

private fun isPlainParagraphHost(sourceCode: CharSequence, hostRange: TextRange): Boolean {
    val mustacheRanges = collectMustacheRanges(sourceCode, hostRange)
    if (mustacheRanges.isEmpty()) return true

    var cursor = hostRange.startOffset
    mustacheRanges.forEach { mustacheRange ->
        if (containsMarkdownInlineMarker(sourceCode, cursor, mustacheRange.startOffset)) {
            return false
        }
        cursor = mustacheRange.endOffset
    }
    return !containsMarkdownInlineMarker(sourceCode, cursor, hostRange.endOffset)
}

private fun containsMarkdownInlineMarker(sourceCode: CharSequence, start: Int, end: Int): Boolean {
    for (index in start until end) {
        if (sourceCode[index] in MARKDOWN_INLINE_MARKERS) {
            return true
        }
    }
    return false
}

private const val TEMPLATE_INTERPOLATION_OPEN: String = "{{"
private const val TEMPLATE_INTERPOLATION_CLOSE: String = "}}"
private const val TEMPLATE_ROOT_PREFIX: String = "<vitepress-template-root>"
private const val TEMPLATE_ROOT_SUFFIX: String = "</vitepress-template-root>"
private val MARKDOWN_INLINE_MARKERS: Set<Char> = setOf('`', '[', ']', '(', ')', '!', '*', '_')
