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

import org.intellij.markdown.IElementType
import org.intellij.markdown.parser.LookaheadText
import org.intellij.markdown.parser.ProductionHolder
import org.intellij.markdown.parser.constraints.MarkdownConstraints
import org.intellij.markdown.parser.constraints.applyToNextLineAndAddModifiers
import org.intellij.markdown.parser.constraints.eatItselfFromString
import org.intellij.markdown.parser.constraints.extendsPrev
import org.intellij.markdown.parser.markerblocks.MarkerBlock
import org.intellij.markdown.parser.markerblocks.MarkerBlockImpl
import org.intellij.markdown.parser.sequentialparsers.SequentialParser

internal class VitePressCustomFenceMarkerBlock(
    myConstraints: MarkdownConstraints,
    private val productionHolder: ProductionHolder,
    private val fenceStart: String,
    startPosition: LookaheadText.Position,
) : MarkerBlockImpl(myConstraints, productionHolder.mark()) {
    private val endLineRegex = Regex("^ {0,3}${Regex.escape(fenceStart)}+ *$")
    private var realInterestingOffset: Int = -1

    init {
        addFenceStart(startPosition)
    }

    override fun allowsSubBlocks(): Boolean = true

    override fun isInterestingOffset(pos: LookaheadText.Position): Boolean = true

    override fun calcNextInterestingOffset(pos: LookaheadText.Position): Int = pos.nextLineOrEofOffset

    override fun getDefaultAction(): MarkerBlock.ClosingAction = MarkerBlock.ClosingAction.DONE

    override fun getDefaultNodeType(): IElementType = VitePressMarkdownElementTypes.CUSTOM_FENCE

    override fun doProcessToken(
        pos: LookaheadText.Position,
        currentConstraints: MarkdownConstraints,
    ): MarkerBlock.ProcessingResult {
        if (pos.offset < realInterestingOffset) {
            return MarkerBlock.ProcessingResult.CANCEL
        }

        if (pos.offsetInCurrentLine != -1) {
            return MarkerBlock.ProcessingResult.CANCEL
        }

        val nextLineConstraints = constraints.applyToNextLineAndAddModifiers(pos)
        if (!nextLineConstraints.extendsPrev(constraints)) {
            return MarkerBlock.ProcessingResult.DEFAULT
        }

        val nextOffset = pos.nextLineOrEofOffset
        realInterestingOffset = nextOffset

        val trimmedCurrent = nextLineConstraints.eatItselfFromString(pos.currentLine)
        if (endsThisFence(trimmedCurrent)) {
            addFenceEnd(pos, pos.nextLineOrEofOffset)
            scheduleProcessingResult(nextOffset, MarkerBlock.ProcessingResult.DEFAULT)
            return MarkerBlock.ProcessingResult.CANCEL
        }

        return MarkerBlock.ProcessingResult.PASS
    }

    private fun endsThisFence(line: CharSequence): Boolean {
        if (line.isEmpty()) return false
        return endLineRegex.matches(line)
    }

    private fun addFenceStart(startPosition: LookaheadText.Position) {
        val lineLength = startPosition.currentLineFromPosition.length
        if (lineLength <= 0) return
        val lineEnd = startPosition.offset + lineLength
        productionHolder.addProduction(
            listOf(
                SequentialParser.Node(
                    startPosition.offset..lineEnd,
                    VitePressMarkdownTokenTypes.CUSTOM_FENCE_START,
                ),
            ),
        )
    }

    private fun addFenceEnd(pos: LookaheadText.Position, lineEnd: Int) {
        val lineStart = pos.offset + 1
        productionHolder.addProduction(
            listOf(
                SequentialParser.Node(
                    lineStart..lineEnd,
                    VitePressMarkdownTokenTypes.CUSTOM_FENCE_END,
                ),
            ),
        )
    }
}
