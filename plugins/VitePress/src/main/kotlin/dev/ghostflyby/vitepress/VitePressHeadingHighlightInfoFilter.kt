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

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.ghostflyby.vitepress.preview.isVitePressFileType
import org.intellij.plugins.markdown.highlighting.MarkdownHighlighterColors

internal class VitePressHeadingHighlightInfoFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, psiFile: PsiFile?): Boolean {
        if (psiFile?.virtualFile?.isVitePressFileType() != true) {
            return true
        }
        if (highlightInfo.severity != HighlightSeverity.INFORMATION) {
            return true
        }
        val highlightRange = TextRange(highlightInfo.startOffset, highlightInfo.endOffset)
        return when (highlightInfo.forcedTextAttributesKey) {
            in headingTextAttributes -> {
                psiFile.getVitePressHeadingGuestRanges()
                    .none { guestRange -> guestRange.intersectsStrict(highlightRange) }
            }

            in linkTextAttributes -> {
                psiFile.getVitePressLinkGuestRanges().none { guestRange -> guestRange.intersectsStrict(highlightRange) }
            }

            else -> true
        }
    }
}

private val headingTextAttributes: Set<TextAttributesKey> =
    setOf(
        MarkdownHighlighterColors.HEADER_LEVEL_1,
        MarkdownHighlighterColors.HEADER_LEVEL_2,
        MarkdownHighlighterColors.HEADER_LEVEL_3,
        MarkdownHighlighterColors.HEADER_LEVEL_4,
        MarkdownHighlighterColors.HEADER_LEVEL_5,
        MarkdownHighlighterColors.HEADER_LEVEL_6,
    )

private val linkTextAttributes: Set<TextAttributesKey> =
    setOf(
        MarkdownHighlighterColors.LINK_TEXT,
        MarkdownHighlighterColors.EXPLICIT_LINK,
        MarkdownHighlighterColors.REFERENCE_LINK,
        MarkdownHighlighterColors.IMAGE,
    )
