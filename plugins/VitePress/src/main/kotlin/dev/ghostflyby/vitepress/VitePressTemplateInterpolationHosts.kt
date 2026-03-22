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

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import dev.ghostflyby.vitepress.markdown.VitePressFlavourDescriptor
import dev.ghostflyby.vitepress.markdown.findVitePressHtmlRange
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.lexer.MarkdownToplevelLexer

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
    val htmlGuestRanges: List<TextRange>,
    val guestRanges: List<TextRange>,
)

internal data class VitePressTemplateInterpolationSnapshot(
    val hosts: List<VitePressTemplateInterpolationHost>,
    val headingInterpolationRanges: List<TextRange>,
    val headingGuestRanges: List<TextRange>,
    val linkGuestRanges: List<TextRange>,
)

internal fun collectTemplateInterpolationHosts(
    sourceCode: CharSequence,
): List<VitePressTemplateInterpolationHost> {
    val result = mutableListOf<VitePressTemplateInterpolationHost>()
    val hostLexer = MarkdownToplevelLexer(VitePressFlavourDescriptor)
    hostLexer.start(sourceCode)

    while (hostLexer.tokenType != null) {
        val kind =
            when (hostLexer.tokenType) {
                MarkdownElementTypes.PARAGRAPH -> VitePressTemplateHostKind.Paragraph
                MarkdownTokenTypes.ATX_CONTENT -> VitePressTemplateHostKind.AtxHeading
                MarkdownTokenTypes.SETEXT_CONTENT -> VitePressTemplateHostKind.SetextHeading
                else -> null
            }
        if (kind != null) {
            val hostRange = TextRange.create(hostLexer.tokenStart, hostLexer.tokenEnd)
            if (isPlainTextTemplateHost(sourceCode, hostRange)) {
                val interpolationRanges = collectMustacheRanges(sourceCode, hostRange)
                val htmlGuestRanges = collectHtmlGuestRanges(sourceCode, hostRange)
                val guestRanges = mergeRanges(interpolationRanges + htmlGuestRanges)
                if (guestRanges.isNotEmpty()) {
                    result +=
                        VitePressTemplateInterpolationHost(
                            kind = kind,
                            hostRange = hostRange,
                            interpolationRanges = interpolationRanges,
                            htmlGuestRanges = htmlGuestRanges,
                            guestRanges = guestRanges,
                        )
                }
            }
        }
        hostLexer.advance()
    }

    result += collectLinkTextInterpolationHosts(sourceCode)
    return result
}

private fun buildTemplateInterpolationSnapshot(
    sourceCode: CharSequence,
): VitePressTemplateInterpolationSnapshot {
    val hosts = collectTemplateInterpolationHosts(sourceCode)
    val headingHosts =
        hosts.filter { it.kind == VitePressTemplateHostKind.AtxHeading || it.kind == VitePressTemplateHostKind.SetextHeading }
    val linkHosts = hosts.filter { it.kind == VitePressTemplateHostKind.LinkText }
    return VitePressTemplateInterpolationSnapshot(
        hosts = hosts,
        headingInterpolationRanges = headingHosts.flatMap { it.interpolationRanges },
        headingGuestRanges = headingHosts.flatMap { it.guestRanges },
        linkGuestRanges = linkHosts.flatMap { it.guestRanges },
    )
}

internal fun PsiFile.getVitePressTemplateInterpolationHosts(): List<VitePressTemplateInterpolationHost> {
    return getVitePressTemplateInterpolationSnapshot().hosts
}

internal fun PsiFile.getVitePressTemplateInterpolationSnapshot(): VitePressTemplateInterpolationSnapshot {
    return CachedValuesManager.getCachedValue(this, TEMPLATE_INTERPOLATION_HOSTS_KEY) {
        CachedValueProvider.Result.create(
            buildTemplateInterpolationSnapshot(text),
            this,
        )
    }
}

internal fun PsiFile.getVitePressHeadingInterpolationRanges(): List<TextRange> {
    return getVitePressTemplateInterpolationSnapshot().headingInterpolationRanges
}

internal fun PsiFile.getVitePressHeadingGuestRanges(): List<TextRange> {
    return getVitePressTemplateInterpolationSnapshot().headingGuestRanges
}

internal fun PsiFile.getVitePressLinkGuestRanges(): List<TextRange> {
    return getVitePressTemplateInterpolationSnapshot().linkGuestRanges
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

private fun collectHtmlGuestRanges(sourceCode: CharSequence, hostRange: TextRange): List<TextRange> {
    val result = mutableListOf<TextRange>()
    var cursor = hostRange.startOffset
    while (cursor < hostRange.endOffset) {
        val nextLt = sourceCode.indexOf('<', cursor).takeIf { it in cursor until hostRange.endOffset } ?: break
        val htmlRange = findHtmlGuestRange(sourceCode, nextLt, hostRange.endOffset)
        if (htmlRange != null) {
            result += htmlRange
            cursor = htmlRange.endOffset
        } else {
            cursor = nextLt + 1
        }
    }
    return mergeRanges(result)
}

private fun findHtmlGuestRange(
    sourceCode: CharSequence,
    startOffset: Int,
    endOffset: Int,
): TextRange? {
    return findVitePressHtmlRange(sourceCode, startOffset, endOffset)
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

private fun collectLinkTextInterpolationHosts(
    sourceCode: CharSequence,
): List<VitePressTemplateInterpolationHost> {
    return collectInlineLinkTextRanges(sourceCode).mapNotNull { hostRange ->
        if (isPlainTextTemplateHost(sourceCode, hostRange)) {
            val interpolationRanges = collectMustacheRanges(sourceCode, hostRange)
            val htmlGuestRanges = collectHtmlGuestRanges(sourceCode, hostRange)
            val guestRanges = mergeRanges(interpolationRanges + htmlGuestRanges)
            if (guestRanges.isNotEmpty()) {
                return@mapNotNull VitePressTemplateInterpolationHost(
                    kind = VitePressTemplateHostKind.LinkText,
                    hostRange = hostRange,
                    interpolationRanges = interpolationRanges,
                    htmlGuestRanges = htmlGuestRanges,
                    guestRanges = guestRanges,
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

private fun isPlainTextTemplateHost(sourceCode: CharSequence, hostRange: TextRange): Boolean {
    val mustacheRanges = collectMustacheRanges(sourceCode, hostRange)
    if (mustacheRanges.isEmpty()) {
        return !containsMarkdownInlineMarker(sourceCode, hostRange.startOffset, hostRange.endOffset)
    }

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
    Key.create<CachedValue<VitePressTemplateInterpolationSnapshot>>("dev.ghostflyby.vitepress.templateInterpolationHosts")

private const val TEMPLATE_INTERPOLATION_OPEN: String = "{{"
private const val TEMPLATE_INTERPOLATION_CLOSE: String = "}}"
private val MARKDOWN_INLINE_MARKERS: Set<Char> = setOf('`', '[', ']', '(', ')', '!', '*', '_')

internal fun mergeRanges(ranges: List<TextRange>): List<TextRange> {
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
