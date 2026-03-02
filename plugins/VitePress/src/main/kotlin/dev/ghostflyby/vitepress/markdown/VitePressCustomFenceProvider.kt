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
import org.intellij.markdown.parser.sequentialparsers.SequentialParser

public class VitePressCustomFenceProvider(
    private val markSkipParagraph: (Int) -> Unit,
) : MarkerBlockProvider<MarkerProcessor.StateInfo> {
    override fun createMarkerBlocks(
        pos: LookaheadText.Position,
        productionHolder: ProductionHolder,
        stateInfo: MarkerProcessor.StateInfo,
    ): List<MarkerBlock> {
        val infoString = obtainFenceOpeningInfo(pos, stateInfo.currentConstraints) ?: return emptyList()
        createNodesForFenceStart(pos, infoString, productionHolder)
        return listOf(
            VitePressCustomFenceMarkerBlock(
                stateInfo.currentConstraints,
                productionHolder,
                markSkipParagraph,
            ),
        )
    }

    override fun interruptsParagraph(pos: LookaheadText.Position, constraints: MarkdownConstraints): Boolean {
        // Any ::: line should break paragraphs/lists so closing delimiters aren't swallowed.
        return REGEX.find(pos.currentLineFromPosition) != null
    }

    private fun createNodesForFenceStart(
        pos: LookaheadText.Position,
        info: String,
        productionHolder: ProductionHolder,
    ) {
        // Remember where this fence starts so the MarkerBlock can wrap start..end.
        productionHolder.updatePosition(pos.offset)
        val infoStartPosition = pos.nextLineOrEofOffset - info.length
        productionHolder.addProduction(
            listOf(
                SequentialParser.Node(
                    pos.offset..infoStartPosition,
                    VitePressMarkdownTokenTypes.CUSTOM_FENCE_START,
                ),
            ),
        )
        if (info.isNotEmpty()) {
            productionHolder.addProduction(
                listOf(
                    SequentialParser.Node(
                        infoStartPosition..pos.nextLineOrEofOffset,
                        VitePressMarkdownTokenTypes.CUSTOM_FENCE_INFO,
                    ),
                ),
            )
        }
    }

    /**
     * Can be used for customizing conditions for the fence opening.
     *
     * This API is a subject to change in the future.
     */
    private fun obtainFenceOpeningInfo(pos: LookaheadText.Position, constraints: MarkdownConstraints): String? {
        if (!MarkerBlockProvider.isStartOfLineWithConstraints(pos, constraints)) {
            return null
        }
        val matchResult = REGEX.find(pos.currentLineFromPosition) ?: return null
        val infoString = matchResult.groups[1]?.value ?: ""
        // Treat bare delimiters (like closing lines) as non-openers to avoid nested fences.
        if (infoString.isBlank()) return null
        return infoString
    }

    private companion object {
        private val REGEX: Regex = Regex("^ {0,3}:::([^`]*)$")
    }
}
