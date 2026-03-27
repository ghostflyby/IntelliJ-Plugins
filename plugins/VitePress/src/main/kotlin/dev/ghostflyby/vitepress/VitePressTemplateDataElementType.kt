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
        val interpolationHosts = collectTemplateInterpolationHosts(sourceCode)
        val topLevelMustacheRanges = mergeRanges(
            interpolationHosts.flatMap { host ->
                host.interpolationRanges.filter { interpolationRange ->
                    host.htmlGuestRanges.none { htmlGuestRange -> htmlGuestRange.contains(interpolationRange) }
                }
            },
        )
        guestRanges += interpolationHosts.flatMap { it.guestRanges }
        val htmlBlockLexer = InlineHtmlAwareToplevelLexer(VitePressFlavourDescriptor)
        htmlBlockLexer.start(sourceCode)

        while (htmlBlockLexer.tokenType != null) {
            val range = TextRange.create(htmlBlockLexer.tokenStart, htmlBlockLexer.tokenEnd)
            when (htmlBlockLexer.tokenType) {
                MarkdownTokenTypes.HTML_BLOCK_CONTENT -> guestRanges += range
            }
            htmlBlockLexer.advance()
        }

        val outerRanges = subtractRanges(TextRange(0, sourceCode.length), mergeRanges(guestRanges))

        val modifications = TemplateDataModifications()
        val operations =
            buildList {
                topLevelMustacheRanges.forEach { mustacheRange ->
                    add(TemplateModificationOperation.insertion(mustacheRange.startOffset, TEMPLATE_BLOCK_PREFIX))
                    add(TemplateModificationOperation.insertion(mustacheRange.endOffset, TEMPLATE_BLOCK_SUFFIX))
                }
                outerRanges.forEach { outerRange ->
                    add(TemplateModificationOperation.outer(outerRange))
                }
            }.sortedWith(compareBy(TemplateModificationOperation::offset, TemplateModificationOperation::priority))
        operations.forEach { operation ->
            when (operation) {
                is TemplateModificationOperation.Insertion ->
                    modifications.addRangeToRemove(operation.offset, operation.text)

                is TemplateModificationOperation.Outer ->
                    modifications.addOuterRange(operation.range)
            }
        }
        return modifications
    }

    internal fun collectTemplateDataModificationsForTests(sourceCode: String): TemplateDataModifications {
        return collectTemplateModifications(
            sourceCode,
            InlineHtmlAwareToplevelLexer(VitePressFlavourDescriptor),
        )
    }

}
private const val TEMPLATE_BLOCK_PREFIX: String = "<template>"
private const val TEMPLATE_BLOCK_SUFFIX: String = "</template>"

private sealed interface TemplateModificationOperation {
    val offset: Int
    val priority: Int

    data class Insertion(
        override val offset: Int,
        val text: CharSequence,
    ) : TemplateModificationOperation {
        override val priority: Int = 0
    }

    data class Outer(
        val range: TextRange,
    ) : TemplateModificationOperation {
        override val offset: Int = range.startOffset
        override val priority: Int = 1
    }

    companion object {
        fun insertion(offset: Int, text: CharSequence): TemplateModificationOperation = Insertion(offset, text)

        fun outer(range: TextRange): TemplateModificationOperation = Outer(range)
    }
}
