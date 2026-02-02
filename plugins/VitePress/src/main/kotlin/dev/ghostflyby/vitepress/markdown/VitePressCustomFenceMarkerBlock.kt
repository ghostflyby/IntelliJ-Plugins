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
import org.intellij.markdown.lexer.Compat
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
    private val markSkipParagraph: (Int) -> Unit,
) : MarkerBlockImpl(myConstraints, productionHolder.mark()) {
    private var allowSubBlocks: Boolean = true

    override fun allowsSubBlocks(): Boolean = allowSubBlocks

    override fun isInterestingOffset(pos: LookaheadText.Position): Boolean = true

    private val endLineRegex = Regex("^ {0,3}:::+ *$")

    private var realInterestingOffset = -1

    override fun calcNextInterestingOffset(pos: LookaheadText.Position): Int {
        return pos.nextLineOrEofOffset
    }

    override fun getDefaultAction(): MarkerBlock.ClosingAction {
        return MarkerBlock.ClosingAction.DONE
    }

    override fun doProcessToken(
        pos: LookaheadText.Position,
        currentConstraints: MarkdownConstraints,
    ): MarkerBlock.ProcessingResult {
        if (pos.offset < realInterestingOffset) {
            return MarkerBlock.ProcessingResult.CANCEL
        }

        // Reset for the current line; may be turned off when we see the closing delimiter.
        allowSubBlocks = true

        if (pos.offsetInCurrentLine != -1) {
            return MarkerBlock.ProcessingResult.CANCEL
        }

        Compat.assert(pos.offsetInCurrentLine == -1)

        val nextLineConstraints = constraints.applyToNextLineAndAddModifiers(pos)
        if (!nextLineConstraints.extendsPrev(constraints)) {
            return MarkerBlock.ProcessingResult.DEFAULT
        }

        val nextLineOffset = pos.nextLineOrEofOffset
        realInterestingOffset = nextLineOffset

        val currentLine = nextLineConstraints.eatItselfFromString(pos.currentLine)
        if (endsThisFence(currentLine)) {
            allowSubBlocks = false
            val nextOffset = pos.nextPosition(1)?.offset ?: (pos.offset + 1)
            markSkipParagraph(nextOffset)
            productionHolder.updatePosition(pos.nextLineOrEofOffset)
            productionHolder.addProduction(
                listOf(
                    SequentialParser.Node(
                        pos.offset + 1..pos.nextLineOrEofOffset,
                        VitePressMarkdownTokenTypes.CUSTOM_FENCE_END,
                    ),
                ),
            )
            realInterestingOffset = pos.nextLineOrEofOffset
            scheduleProcessingResult(pos.nextLineOrEofOffset, MarkerBlock.ProcessingResult.DEFAULT)
            return MarkerBlock.ProcessingResult.DEFAULT
        } else {
            allowSubBlocks = true
        }

        return MarkerBlock.ProcessingResult.CANCEL
    }

    private fun endsThisFence(line: CharSequence): Boolean {
        return endLineRegex.matches(line)
    }

    override fun getDefaultNodeType(): IElementType {
        return VitePressMarkdownElementTypes.CUSTOM_FENCE
    }
}
