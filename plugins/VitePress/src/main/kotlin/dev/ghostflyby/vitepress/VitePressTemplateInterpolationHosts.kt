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
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import dev.ghostflyby.vitepress.markdown.InlineHtmlAwareToplevelLexer
import dev.ghostflyby.vitepress.markdown.VitePressFlavourDescriptor
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes

internal enum class VitePressTemplateHostKind {
    Paragraph,
    AtxHeading,
    SetextHeading,
    LinkText,
}

internal data class VitePressTemplateInterpolationHost(
    val kind: VitePressTemplateHostKind,
    val hostRange: TextRange,
    val interpolationRanges: List<TextRange>,
)

internal fun collectTemplateInterpolationHosts(
    sourceCode: CharSequence,
    baseLexer: Lexer,
): List<VitePressTemplateInterpolationHost> {
    val result = mutableListOf<VitePressTemplateInterpolationHost>()
    baseLexer.start(sourceCode)

    while (baseLexer.tokenType != null) {
        val kind =
            when (baseLexer.tokenType) {
                MarkdownElementTypes.PARAGRAPH -> VitePressTemplateHostKind.Paragraph
                MarkdownTokenTypes.ATX_CONTENT -> VitePressTemplateHostKind.AtxHeading
                MarkdownTokenTypes.SETEXT_CONTENT -> VitePressTemplateHostKind.SetextHeading
                MarkdownElementTypes.LINK_TEXT -> VitePressTemplateHostKind.LinkText
                else -> null
            }
        if (kind != null) {
            val hostRange = TextRange.create(baseLexer.tokenStart, baseLexer.tokenEnd)
            if (isPlainTextMustacheHost(sourceCode, hostRange)) {
                val interpolationRanges = collectMustacheRanges(sourceCode, hostRange)
                if (interpolationRanges.isNotEmpty()) {
                    result +=
                        VitePressTemplateInterpolationHost(
                            kind = kind,
                            hostRange = hostRange,
                            interpolationRanges = interpolationRanges,
                        )
                }
            }
        }
        baseLexer.advance()
    }

    result += collectLinkTextInterpolationHosts(sourceCode)
    return result
}

internal fun collectTemplateInterpolationHosts(sourceCode: CharSequence): List<VitePressTemplateInterpolationHost> {
    return collectTemplateInterpolationHosts(
        sourceCode,
        InlineHtmlAwareToplevelLexer(VitePressFlavourDescriptor),
    )
}

internal fun PsiFile.getVitePressTemplateInterpolationHosts(): List<VitePressTemplateInterpolationHost> {
    return CachedValuesManager.getCachedValue(this, TEMPLATE_INTERPOLATION_HOSTS_KEY) {
        CachedValueProvider.Result.create(
            collectTemplateInterpolationHosts(text),
            this,
        )
    }
}

internal fun PsiFile.getVitePressHeadingInterpolationRanges(): List<TextRange> {
    return getVitePressTemplateInterpolationHosts()
        .asSequence()
        .filter { it.kind == VitePressTemplateHostKind.AtxHeading || it.kind == VitePressTemplateHostKind.SetextHeading }
        .flatMap { it.interpolationRanges.asSequence() }
        .toList()
}

internal fun PsiFile.getVitePressLinkInterpolationRanges(): List<TextRange> {
    return getVitePressTemplateInterpolationHosts()
        .asSequence()
        .filter { it.kind == VitePressTemplateHostKind.LinkText }
        .flatMap { it.interpolationRanges.asSequence() }
        .toList()
}

internal fun subtractRanges(baseRange: TextRange, excludedRanges: List<TextRange>): List<TextRange> {
    if (excludedRanges.isEmpty()) return listOf(baseRange)

    val sortedExcludedRanges =
        excludedRanges
            .asSequence()
            .mapNotNull { excludedRange -> excludedRange.intersection(baseRange) }
            .sortedBy { it.startOffset }
            .toList()
    if (sortedExcludedRanges.isEmpty()) return listOf(baseRange)

    val result = mutableListOf<TextRange>()
    var cursor = baseRange.startOffset
    sortedExcludedRanges.forEach { excludedRange ->
        if (cursor < excludedRange.startOffset) {
            result += TextRange(cursor, excludedRange.startOffset)
        }
        cursor = maxOf(cursor, excludedRange.endOffset)
    }
    if (cursor < baseRange.endOffset) {
        result += TextRange(cursor, baseRange.endOffset)
    }
    return result
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

private fun collectLinkTextInterpolationHosts(sourceCode: CharSequence): List<VitePressTemplateInterpolationHost> {
    return collectInlineLinkTextRanges(sourceCode).mapNotNull { hostRange ->
        if (isPlainTextMustacheHost(sourceCode, hostRange)) {
            val interpolationRanges = collectMustacheRanges(sourceCode, hostRange)
            if (interpolationRanges.isNotEmpty()) {
                return@mapNotNull VitePressTemplateInterpolationHost(
                    kind = VitePressTemplateHostKind.LinkText,
                    hostRange = hostRange,
                    interpolationRanges = interpolationRanges,
                )
            }
        }
        null
    }
}

private fun collectInlineLinkTextRanges(sourceCode: CharSequence): List<TextRange> {
    val result = mutableListOf<TextRange>()
    var index = 0
    while (index < sourceCode.length) {
        if (sourceCode[index] != '[' || (index > 0 && sourceCode[index - 1] == '!')) {
            index++
            continue
        }

        val textStart = index + 1
        var cursor = textStart
        var nestedBrackets = 0
        var textEnd = -1
        while (cursor < sourceCode.length) {
            when (sourceCode[cursor]) {
                '\\' -> {
                    cursor = (cursor + 2).coerceAtMost(sourceCode.length)
                    continue
                }

                '[' -> nestedBrackets++
                ']' -> {
                    if (nestedBrackets == 0 && cursor + 1 < sourceCode.length && sourceCode[cursor + 1] == '(') {
                        textEnd = cursor
                        break
                    }
                    if (nestedBrackets > 0) {
                        nestedBrackets--
                    }
                }
            }
            cursor++
        }

        if (textEnd != -1 && textStart < textEnd) {
            result += TextRange(textStart, textEnd)
            index = textEnd + 1
        } else {
            index++
        }
    }
    return result
}

private fun isPlainTextMustacheHost(sourceCode: CharSequence, hostRange: TextRange): Boolean {
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

private val TEMPLATE_INTERPOLATION_HOSTS_KEY =
    Key.create<CachedValue<List<VitePressTemplateInterpolationHost>>>("dev.ghostflyby.vitepress.templateInterpolationHosts")

private const val TEMPLATE_INTERPOLATION_OPEN: String = "{{"
private const val TEMPLATE_INTERPOLATION_CLOSE: String = "}}"
private val MARKDOWN_INLINE_MARKERS: Set<Char> = setOf('`', '[', ']', '(', ')', '!', '*', '_')
