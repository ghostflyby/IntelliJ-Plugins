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

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiUtilCore
import dev.ghostflyby.vitepress.preview.isVitePressFileType
import org.intellij.plugins.markdown.highlighting.MarkdownSyntaxHighlighter
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes

internal class VitePressHeadingInterpolationAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val elementType = PsiUtilCore.getElementType(element)
        if (elementType !in supportedHostTokenTypes) {
            return
        }
        if (!element.containingFile.virtualFile.isVitePressFileType()) {
            return
        }

        val elementRange = element.textRange
        val hostAttributes = hostAttributes(elementType, element) ?: return
        val guestRanges = guestRanges(elementType, element)
        val overlappingGuestRanges = guestRanges.filter { guestRange -> guestRange.intersectsStrict(elementRange) }
        subtractRanges(elementRange, overlappingGuestRanges).forEach { remainingRange ->
            holder
                .newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(remainingRange)
                .textAttributes(hostAttributes)
                .create()
        }
    }

    private fun hostAttributes(
        elementType: com.intellij.psi.tree.IElementType,
        element: PsiElement,
    ): com.intellij.openapi.editor.colors.TextAttributesKey? {
        return when (elementType) {
            in headingTokenTypes -> {
                val headingType = parentHeadingType(element) ?: return null
                headingAttributesByType[headingType]
            }

            MarkdownElementTypes.LINK_TEXT -> linkTextAttributes

            else -> null
        }
    }

    private fun guestRanges(
        elementType: com.intellij.psi.tree.IElementType,
        element: PsiElement,
    ): List<com.intellij.openapi.util.TextRange> {
        return when (elementType) {
            in headingTokenTypes -> element.containingFile.getVitePressHeadingGuestRanges()
            MarkdownElementTypes.LINK_TEXT -> element.containingFile.getVitePressLinkGuestRanges()
            else -> emptyList()
        }
    }
}

private val headingTokenTypes = setOf(MarkdownTokenTypes.ATX_CONTENT, MarkdownTokenTypes.SETEXT_CONTENT)
private val supportedHostTokenTypes = headingTokenTypes + MarkdownElementTypes.LINK_TEXT
private val markdownSyntaxHighlighter = MarkdownSyntaxHighlighter()
private val headingAttributesByType =
    mapOf(
        MarkdownElementTypes.ATX_1 to markdownSyntaxHighlighter.getTokenHighlights(MarkdownElementTypes.ATX_1)
            .firstOrNull(),
        MarkdownElementTypes.ATX_2 to markdownSyntaxHighlighter.getTokenHighlights(MarkdownElementTypes.ATX_2)
            .firstOrNull(),
        MarkdownElementTypes.ATX_3 to markdownSyntaxHighlighter.getTokenHighlights(MarkdownElementTypes.ATX_3)
            .firstOrNull(),
        MarkdownElementTypes.ATX_4 to markdownSyntaxHighlighter.getTokenHighlights(MarkdownElementTypes.ATX_4)
            .firstOrNull(),
        MarkdownElementTypes.ATX_5 to markdownSyntaxHighlighter.getTokenHighlights(MarkdownElementTypes.ATX_5)
            .firstOrNull(),
        MarkdownElementTypes.ATX_6 to markdownSyntaxHighlighter.getTokenHighlights(MarkdownElementTypes.ATX_6)
            .firstOrNull(),
        MarkdownElementTypes.SETEXT_1 to markdownSyntaxHighlighter.getTokenHighlights(MarkdownElementTypes.SETEXT_1)
            .firstOrNull(),
        MarkdownElementTypes.SETEXT_2 to markdownSyntaxHighlighter.getTokenHighlights(MarkdownElementTypes.SETEXT_2)
            .firstOrNull(),
    )
private val linkTextAttributes =
    markdownSyntaxHighlighter.getTokenHighlights(MarkdownElementTypes.LINK_TEXT).firstOrNull()

private fun parentHeadingType(element: PsiElement) = PsiUtilCore.getElementType(element.parent)
