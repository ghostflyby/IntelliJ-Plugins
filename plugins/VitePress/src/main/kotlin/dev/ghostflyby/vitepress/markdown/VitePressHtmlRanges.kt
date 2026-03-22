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

package dev.ghostflyby.vitepress.markdown

import com.intellij.openapi.util.TextRange

/**
 * Finds a permissive HTML fragment range using the same rules as the VitePress lexer and block provider.
 */
internal fun findVitePressHtmlRange(
    sourceCode: CharSequence,
    startOffset: Int,
    endOffset: Int,
): TextRange? {
    if (startOffset + 1 >= endOffset) return null

    val lineEndExclusive = sourceCode.indexOf('\n', startOffset).let { newlineIndex ->
        if (newlineIndex == -1 || newlineIndex > endOffset) endOffset else newlineIndex
    }
    val lineSlice = sourceCode.subSequence(startOffset, lineEndExclusive).toString()

    val inlineSelfClosing = VitePressHtmlPatterns.INLINE_SELF_CLOSING_REGEX.find(lineSlice)
        ?.takeIf { it.range.first == 0 }
    if (inlineSelfClosing != null) {
        return TextRange(startOffset, startOffset + inlineSelfClosing.range.last + 1)
    }

    val match = VitePressHtmlPatterns.FIND_START_REGEX.find(lineSlice) ?: return null
    val matchedGroupIndex = match.groups.drop(2).indexOfFirst { it != null }
    if (matchedGroupIndex == -1) return null

    val afterStart = if (matchedGroupIndex == VitePressHtmlPatterns.OPEN_TAG_BLOCK_GROUP_INDEX) {
        val gtIndex = lineSlice.indexOf('>')
        if (gtIndex >= 0) startOffset + gtIndex + 1 else startOffset + match.range.last + 1
    } else {
        startOffset + match.range.last + 1
    }

    if (matchedGroupIndex == VitePressHtmlPatterns.IMMEDIATE_TAG_GROUP_INDEX) {
        return TextRange(startOffset, lineEndExclusive)
    }
    if (matchedGroupIndex == VitePressHtmlPatterns.ENTITY_GROUP_INDEX) {
        return TextRange(startOffset, afterStart)
    }

    val closeRegex =
        VitePressHtmlPatterns.OPEN_CLOSE_REGEXES[matchedGroupIndex].second ?: return TextRange(startOffset, afterStart)
    val searchSlice = sourceCode.subSequence(afterStart, endOffset).toString()
    val closeMatch = closeRegex.find(searchSlice)
    val rangeEnd = if (closeMatch != null) afterStart + closeMatch.range.last + 1 else endOffset
    return TextRange(startOffset, rangeEnd)
}
