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
import org.intellij.plugins.markdown.lang.MarkdownElementTypes.MARKDOWN_OUTER_BLOCK
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes

internal object VitePressTemplateDataElementType : TemplateDataElementType(
    "VITEPRESS_TEMPLATE_DATA",
    VitePressLanguage,
    MarkdownTokenTypes.HTML_BLOCK_CONTENT,
    MARKDOWN_OUTER_BLOCK,
) {
    override fun collectTemplateModifications(sourceCode: CharSequence, baseLexer: Lexer): TemplateDataModifications {
        val guestRanges = mutableListOf<TextRange>()
        val interpolationHosts = collectTemplateInterpolationHosts(sourceCode, baseLexer)
        val hasTopLevelMustache = interpolationHosts.isNotEmpty()
        guestRanges += interpolationHosts.flatMap { it.interpolationRanges }
        baseLexer.start(sourceCode)

        while (baseLexer.tokenType != null) {
            val range = TextRange.create(baseLexer.tokenStart, baseLexer.tokenEnd)
            when (baseLexer.tokenType) {
                MarkdownTokenTypes.HTML_BLOCK_CONTENT -> guestRanges += range
            }
            baseLexer.advance()
        }

        val outerRanges = subtractRanges(TextRange(0, sourceCode.length), mergeRanges(guestRanges))

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

private fun mergeRanges(ranges: List<TextRange>): List<TextRange> {
    if (ranges.isEmpty()) return emptyList()
    val sortedRanges = ranges.sortedBy { it.startOffset }
    val result = mutableListOf<TextRange>()
    var current = sortedRanges.first()
    sortedRanges.drop(1).forEach { range ->
        if (range.startOffset <= current.endOffset) {
            current = TextRange(current.startOffset, maxOf(current.endOffset, range.endOffset))
        } else {
            result += current
            current = range
        }
    }
    result += current
    return result
}
private const val TEMPLATE_ROOT_PREFIX: String = "<vitepress-template-root>"
private const val TEMPLATE_ROOT_SUFFIX: String = "</vitepress-template-root>"
