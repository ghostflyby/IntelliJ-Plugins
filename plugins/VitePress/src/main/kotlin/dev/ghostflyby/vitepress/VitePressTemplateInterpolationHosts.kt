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
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import org.intellij.markdown.ExperimentalApi
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

internal enum class VitePressTemplateHostKind {
    Paragraph,
    AtxHeading,
    SetextHeading,
    LinkText,
    TableCell,
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
    // The primary constructor is the only non-deprecated one across the bundled markdown versions
    // (@ExperimentalApi in 2026.1's 0.7.3; the (flavour) convenience constructor is deprecated from
    // 2026.2's version on).
    @OptIn(ExperimentalApi::class)
    val root =
        MarkdownParser(VitePressFlavourDescriptor, true, CancellationToken.NonCancellable)
            .buildMarkdownTreeFromStringCompat(sourceCode)
    val result = mutableListOf<VitePressTemplateInterpolationHost>()
    collectTemplateInterpolationHosts(root, null, sourceCode, result)
    return result
}

private fun collectTemplateInterpolationHosts(
    node: ASTNode,
    parentType: IElementType?,
    sourceCode: CharSequence,
    result: MutableList<VitePressTemplateInterpolationHost>,
) {
    when (node.type) {
        MarkdownElementTypes.IMAGE -> return // image alt text is never a template host

        MarkdownElementTypes.LINK_TEXT ->
            // Reference links reuse LINK_TEXT under FULL_REFERENCE_LINK; only `](url)` inline forms are hosts.
            // The host range excludes the wrapping brackets.
            if (parentType == MarkdownElementTypes.INLINE_LINK) {
                addTemplateInterpolationHost(
                    VitePressTemplateHostKind.LinkText,
                    sourceCode,
                    TextRange(node.startOffset + 1, node.endOffset - 1),
                    result,
                )
            }

        MarkdownElementTypes.PARAGRAPH ->
            addTemplateInterpolationHost(
                VitePressTemplateHostKind.Paragraph,
                sourceCode,
                TextRange(node.startOffset, node.endOffset),
                result,
            )

        MarkdownTokenTypes.ATX_CONTENT ->
            addTemplateInterpolationHost(
                VitePressTemplateHostKind.AtxHeading,
                sourceCode,
                TextRange(node.startOffset, node.endOffset),
                result,
            )

        MarkdownTokenTypes.SETEXT_CONTENT ->
            addTemplateInterpolationHost(
                VitePressTemplateHostKind.SetextHeading,
                sourceCode,
                TextRange(node.startOffset, node.endOffset),
                result,
            )

        GFMTokenTypes.CELL ->
            addTemplateInterpolationHost(
                VitePressTemplateHostKind.TableCell,
                sourceCode,
                TextRange(node.startOffset, node.endOffset),
                result,
            )
    }
    node.children.forEach { collectTemplateInterpolationHosts(it, node.type, sourceCode, result) }
}

private fun addTemplateInterpolationHost(
    kind: VitePressTemplateHostKind,
    sourceCode: CharSequence,
    hostRange: TextRange,
    result: MutableList<VitePressTemplateInterpolationHost>,
) {
    buildTemplateInterpolationHost(kind, sourceCode, hostRange)?.let { result += it }
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
        val htmlRange = findVitePressHtmlRange(sourceCode, nextLt, hostRange.endOffset)
        if (htmlRange != null) {
            result += htmlRange
            cursor = htmlRange.endOffset
        } else {
            cursor = nextLt + 1
        }
    }
    return mergeRanges(result)
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

private fun buildTemplateInterpolationHost(
    kind: VitePressTemplateHostKind,
    sourceCode: CharSequence,
    hostRange: TextRange,
): VitePressTemplateInterpolationHost? {
    val interpolationRanges = collectMustacheRanges(sourceCode, hostRange)
    if (!isPlainTextTemplateHost(sourceCode, hostRange, interpolationRanges)) {
        return null
    }

    val htmlGuestRanges = collectHtmlGuestRanges(sourceCode, hostRange)
    val guestRanges = mergeRanges(interpolationRanges + htmlGuestRanges)
    if (guestRanges.isEmpty()) {
        return null
    }

    return VitePressTemplateInterpolationHost(
        kind = kind,
        hostRange = hostRange,
        interpolationRanges = interpolationRanges,
        htmlGuestRanges = htmlGuestRanges,
        guestRanges = guestRanges,
    )
}

private fun isPlainTextTemplateHost(
    sourceCode: CharSequence,
    hostRange: TextRange,
    mustacheRanges: List<TextRange>,
): Boolean {
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

// buildMarkdownTreeFromString(CharSequence) is the non-deprecated entry point from markdown 0.7.4
// on, but 2026.1's bundled 0.7.3 only has the String overload — a pure forwarder that newer
// runtimes keep for ABI yet deprecate. Both overloads are resolved via MethodHandle, like
// PersistentSetAddAll.kt: any direct invocation would leave a deprecated method reference in the
// bytecode for the Plugin Verifier to flag, so the fallback path must stay reflective too.
private val buildMarkdownTreeFromCharSequenceHandle: MethodHandle? = runCatching {
    MethodHandles.publicLookup().findVirtual(
        MarkdownParser::class.java,
        "buildMarkdownTreeFromString",
        MethodType.methodType(ASTNode::class.java, CharSequence::class.java),
    )
}.getOrNull()

private val buildMarkdownTreeFromStringHandle: MethodHandle = runCatching {
    MethodHandles.publicLookup().findVirtual(
        MarkdownParser::class.java,
        "buildMarkdownTreeFromString",
        MethodType.methodType(ASTNode::class.java, String::class.java),
    )
}.getOrElse { e ->
    throw NoSuchMethodError(
        "Neither MarkdownParser.buildMarkdownTreeFromString(CharSequence) nor buildMarkdownTreeFromString(String) exists",
    ).also { it.initCause(e) }
}

private fun MarkdownParser.buildMarkdownTreeFromStringCompat(text: CharSequence): ASTNode {
    return buildMarkdownTreeFromCharSequenceHandle?.invoke(this, text) as ASTNode?
        ?: buildMarkdownTreeFromStringHandle.invoke(this, text.toString()) as ASTNode
}
