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

import org.intellij.markdown.parser.LookaheadText
import org.intellij.markdown.parser.MarkerProcessor
import org.intellij.markdown.parser.ProductionHolder
import org.intellij.markdown.parser.constraints.MarkdownConstraints
import org.intellij.markdown.parser.markerblocks.MarkerBlock
import org.intellij.markdown.parser.markerblocks.MarkerBlockProvider

internal class VitePressCustomFenceProvider : MarkerBlockProvider<MarkerProcessor.StateInfo> {
    override fun createMarkerBlocks(
        pos: LookaheadText.Position,
        productionHolder: ProductionHolder,
        stateInfo: MarkerProcessor.StateInfo,
    ): List<MarkerBlock> {
        val openingInfo = obtainFenceOpeningInfo(pos, stateInfo.currentConstraints) ?: return emptyList()
        return listOf(
            VitePressCustomFenceMarkerBlock(
                stateInfo.currentConstraints,
                productionHolder,
                openingInfo.delimiter,
                pos,
            ),
        )
    }

    override fun interruptsParagraph(pos: LookaheadText.Position, constraints: MarkdownConstraints): Boolean {
        if (!MarkerBlockProvider.isStartOfLineWithConstraints(pos, constraints)) return false
        val line = pos.currentLineFromPosition
        return OPENING_REGEX.matches(line) || CLOSING_REGEX.matches(line)
    }

    private fun obtainFenceOpeningInfo(
        pos: LookaheadText.Position,
        constraints: MarkdownConstraints,
    ): OpeningInfo? {
        if (!MarkerBlockProvider.isStartOfLineWithConstraints(pos, constraints)) return null
        val matchResult = OPENING_REGEX.find(pos.currentLineFromPosition) ?: return null
        val delimiterGroup = matchResult.groups[1] ?: return null
        val infoGroup = matchResult.groups[2]
        val delimiter = delimiterGroup.value
        val info = infoGroup?.value ?: ""
        val type = info.trimStart().split(WHITESPACE_REGEX, limit = 2).firstOrNull().orEmpty()
        if (type.isEmpty() || type !in SUPPORTED_TYPES) return null

        return OpeningInfo(delimiter, info)
    }

    private data class OpeningInfo(
        val delimiter: String,
        val info: String,
    )

    internal companion object {
        private val SUPPORTED_TYPES = setOf("info", "tip", "warning", "danger", "details", "raw")
        private val WHITESPACE_REGEX = Regex("\\s+")
        internal val OPENING_REGEX = Regex("^ {0,3}(:::+)\\s*(.*)?$")
        internal val CLOSING_REGEX = Regex("^ {0,3}(:::+)\\s*$")
    }
}
