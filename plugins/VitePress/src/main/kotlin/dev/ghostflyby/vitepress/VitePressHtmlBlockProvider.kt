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

import org.intellij.lang.annotations.Language
import org.intellij.markdown.MarkdownParsingException
import org.intellij.markdown.parser.LookaheadText
import org.intellij.markdown.parser.MarkerProcessor
import org.intellij.markdown.parser.ProductionHolder
import org.intellij.markdown.parser.constraints.MarkdownConstraints
import org.intellij.markdown.parser.markerblocks.MarkerBlock
import org.intellij.markdown.parser.markerblocks.MarkerBlockProvider
import org.intellij.markdown.parser.markerblocks.impl.HtmlBlockMarkerBlock

/**
 * VitePress / Vue SFC template friendly variant of HtmlBlockProvider:
 * - Accepts ANY tag name for CommonMark HTML block type 6 (no whitelist)
 * - Lax: treats `<tag` and `<tag ...` (even without `>`) as HTML block starters to favor Vue services
 */
public class VitePressHtmlBlockProvider : MarkerBlockProvider<MarkerProcessor.StateInfo> {
    override fun createMarkerBlocks(
        pos: LookaheadText.Position,
        productionHolder: ProductionHolder,
        stateInfo: MarkerProcessor.StateInfo,
    ): List<MarkerBlock> {
        val matchingGroup = matches(pos, stateInfo.currentConstraints)
        if (matchingGroup != -1) {
            return listOf(
                HtmlBlockMarkerBlock(
                    stateInfo.currentConstraints,
                    productionHolder,
                    OPEN_CLOSE_REGEXES[matchingGroup].second,
                    pos,
                ),
            )
        }
        return emptyList()
    }

    override fun interruptsParagraph(pos: LookaheadText.Position, constraints: MarkdownConstraints): Boolean {
        return matches(pos, constraints) in OPEN_CLOSE_REGEXES.indices
    }

    private fun matches(pos: LookaheadText.Position, constraints: MarkdownConstraints): Int {
        if (!MarkerBlockProvider.isStartOfLineWithConstraints(pos, constraints)) return -1

        val text = pos.currentLineFromPosition
        val offset = MarkerBlockProvider.passSmallIndent(text)
        if (offset >= text.length || text[offset] != '<') return -1

        val matchResult = FIND_START_REGEX.find(text.substring(offset)) ?: return -1
        if (matchResult.groups.size != OPEN_CLOSE_REGEXES.size + 2) {
            throw MarkdownParsingException("There are some excess capturing groups probably!")
        }

        for (i in OPEN_CLOSE_REGEXES.indices) {
            if (matchResult.groups[i + 2] != null) return i
        }

        throw MarkdownParsingException("Match found but all groups are empty!")
    }

    public companion object {
        /**
         * Single-line tag (open or close) with a required `>`; ends immediately.
         * This avoids forcing a blank line to terminate the block for `<tag>`.
         */
        @Language("RegExp")
        private const val SINGLE_LINE_TAG = "</?[^\\s>/][^\\n>]*>\\s*$"

        /**
         * Allow very permissive HTML-ish tag starts to get Vue template services early.
         * Examples that should match: `<tag`, `<tag whatever`, `<tag/>`, `</tag`, `<tag @aria-busy=\"true\">`
         * Also allows non-standard tag starts like `<_x` or `<x:y` to avoid false negatives.
         */
        @Language("RegExp")
        private const val LAX_TAG_START = "</?[^\\s>][^\\n>]*>?"


        /**
         * CommonMark HTML blocks:
         * 0..4: same as upstream
         * 5: single-line tag (ends immediately)
         * 6: lax tag start (any tag name + any trailing content, optional '>') -> ends on blank line (null)
         */
        private val OPEN_CLOSE_REGEXES: List<Pair<Regex, Regex?>> = listOf(
            // 0
            Regex("<(?:script|pre|style)(?: |>|$)", RegexOption.IGNORE_CASE) to
                    Regex("</(?:script|style|pre)>", RegexOption.IGNORE_CASE),

            // 1
            Regex("<!--") to Regex("-->"),

            // 2
            Regex("<\\?") to Regex("\\?>"),

            // 3
            Regex("<![A-Z]") to Regex(">"),

            // 4
            Regex("<!\\[CDATA\\[") to Regex("]]>"),

            // 5 (single-line tag with immediate termination)
            Regex(SINGLE_LINE_TAG, RegexOption.IGNORE_CASE) to Regex(">"),

            // 6 (CommonMark type 6 but WITHOUT block-tag whitelist, lax attrs/closing)
            Regex(LAX_TAG_START, RegexOption.IGNORE_CASE) to null,
        )

        private val FIND_START_REGEX = Regex(
            "^(${OPEN_CLOSE_REGEXES.joinToString(separator = "|") { "(${it.first.pattern})" }})",
        )
    }
}