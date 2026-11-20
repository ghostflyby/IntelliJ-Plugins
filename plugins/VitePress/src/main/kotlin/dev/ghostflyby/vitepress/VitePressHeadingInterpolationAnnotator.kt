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
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
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
        elementType: IElementType,
        element: PsiElement,
    ): TextAttributesKey? {
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
        elementType: IElementType,
        element: PsiElement,
    ): List<TextRange> {
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
private val headingAttributesByType: Map<IElementType, TextAttributesKey?> =
    listOf(
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1,
        MarkdownElementTypes.SETEXT_2,
    ).associateWith { markdownSyntaxHighlighter.getTokenHighlights(it).firstOrNull() }
private val linkTextAttributes =
    markdownSyntaxHighlighter.getTokenHighlights(MarkdownElementTypes.LINK_TEXT).firstOrNull()

private fun parentHeadingType(element: PsiElement) = PsiUtilCore.getElementType(element.parent)
