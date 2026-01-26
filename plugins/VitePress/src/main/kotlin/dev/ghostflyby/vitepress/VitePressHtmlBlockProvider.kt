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
 * - Accepts Vue directive / shorthand attrs: `:foo`, `@click`, `#slot`, `v-bind:[]`, `:[]`, etc.
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
        return matches(pos, constraints) in 0..6
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
        // Tag name: keep as in upstream (enough for Vue components: MyComp / my-comp)
        private const val TAG_NAME = "[A-Za-z][A-Za-z0-9-]*"

        /**
         * Vue-friendly attribute name:
         * - allow `:foo`, `@click`, `#slot`
         * - allow bracketed argument forms: `v-bind:[]`, `:[]`
         * - allow normal HTML names: `foo`, `data-x`, `aria-label`, `xlink:href`, etc.
         *
         */
        @Suppress("RegExpUnnecessaryNonCapturingGroup")
        @Language("RegExp")
        private const val ATTR_NAME =
            "(?:[A-Za-z_][A-Za-z0-9_.:-]*" +                           // normal
                    "|[:@#][A-Za-z_][A-Za-z0-9_.:-]*" +                // :foo @click #slot
                    "|[A-Za-z_][A-Za-z0-9_.:-]*:\\[[^]\\s\"'=<>`]+]" + // v-bind:[arg]
                    "|[:@#]\\[[^]\\s\"'=<>`]+]" +                      // :[arg] @[event]? #[]? (accept)
                    "|[:@#]" +                                         // : @ # (accept bare, be permissive for proper intellisense)
                    ")"

        // Attribute value: keep upstream style (already handles '...' / "..." / bare)
        @Language("RegExp")
        private const val ATTR_VALUE = "\\s*=\\s*(?:[^ \"'=<>`]+|'[^']*'|\"[^\"]*\")"

        @Language("RegExp")
        private const val ATTRIBUTE = "\\s+${ATTR_NAME}(?:${ATTR_VALUE})?"

        // Allow any amount of attributes; keep it line-based (same as upstream intent)
        @Language("RegExp")
        private const val OPEN_TAG = "<${TAG_NAME}(?:${ATTRIBUTE})*\\s*/?>"

        /** Closing tag allowance is not in public spec version yet (upstream comment) */
        @Language("RegExp")
        private const val CLOSE_TAG = "</${TAG_NAME}\\s*>"

        /**
         * CommonMark HTML blocks:
         * 0..4: same as upstream
         * 5: modified type
         * 6: ANY tag name (no whitelist) -> ends on blank line (null)
         * 6: upstream type
         * 7 (open/close tag with attrs) -> ends on blank line (null)
         *
         * For VitePress, the key is (5): without it, many Vue component blocks won't start.
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

            // 5 (CommonMark type 6 but WITHOUT block-tag whitelist, Vue-friendly attrs)
            @Suppress("RegExpUnnecessaryNonCapturingGroup")
            Regex("</?(?:$TAG_NAME)(?:$ATTRIBUTE)*\\s*/?>", RegexOption.IGNORE_CASE) to null,

            // 6 (CommonMark type 7, Vue-friendly attrs)
            Regex("(?:$OPEN_TAG|$CLOSE_TAG)(?: |$)") to null,
        )

        private val FIND_START_REGEX = Regex(
            "^(${OPEN_CLOSE_REGEXES.joinToString(separator = "|") { "(${it.first.pattern})" }})",
        )
    }
}