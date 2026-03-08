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
    private val syntaxHighlighter = MarkdownSyntaxHighlighter()

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (!element.containingFile.virtualFile.isVitePressFileType()) {
            return
        }
        if (PsiUtilCore.getElementType(element) !in supportedHostTokenTypes) {
            return
        }

        val elementRange = element.textRange
        val hostAttributes = hostAttributes(element) ?: return
        val guestRanges = guestRanges(element)
        val interpolationRanges = guestRanges.filter { interpolationRange ->
                interpolationRange.intersectsStrict(elementRange)
            }
        subtractRanges(elementRange, interpolationRanges).forEach { remainingRange ->
            holder
                .newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(remainingRange)
                .textAttributes(hostAttributes)
                .create()
        }
    }

    private fun hostAttributes(element: PsiElement): com.intellij.openapi.editor.colors.TextAttributesKey? {
        return when (PsiUtilCore.getElementType(element)) {
            in headingTokenTypes -> {
                val headingType = parentHeadingType(element) ?: return null
                syntaxHighlighter.getTokenHighlights(headingType).firstOrNull()
            }

            MarkdownElementTypes.LINK_TEXT -> syntaxHighlighter.getTokenHighlights(MarkdownElementTypes.LINK_TEXT)
                .firstOrNull()

            else -> null
        }
    }

    private fun guestRanges(element: PsiElement): List<com.intellij.openapi.util.TextRange> {
        return when (PsiUtilCore.getElementType(element)) {
            in headingTokenTypes -> element.containingFile.getVitePressHeadingInterpolationRanges()
            MarkdownElementTypes.LINK_TEXT -> element.containingFile.getVitePressLinkInterpolationRanges()
            else -> emptyList()
        }
    }
}

private val headingTokenTypes = setOf(MarkdownTokenTypes.ATX_CONTENT, MarkdownTokenTypes.SETEXT_CONTENT)
private val supportedHostTokenTypes = headingTokenTypes + MarkdownElementTypes.LINK_TEXT

private fun parentHeadingType(element: PsiElement) = PsiUtilCore.getElementType(element.parent)
