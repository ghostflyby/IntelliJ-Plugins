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

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.commonmark.CommonMarkMarkerProcessor
import org.intellij.markdown.flavours.gfm.GFMConstraints
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.table.GitHubTableMarkerProvider
import org.intellij.markdown.parser.LookaheadText
import org.intellij.markdown.parser.MarkerProcessor
import org.intellij.markdown.parser.MarkerProcessorFactory
import org.intellij.markdown.parser.ProductionHolder
import org.intellij.markdown.parser.constraints.CommonMarkdownConstraints
import org.intellij.markdown.parser.constraints.MarkdownConstraints
import org.intellij.markdown.parser.constraints.getCharsEaten
import org.intellij.markdown.parser.markerblocks.MarkerBlockProvider
import org.intellij.markdown.parser.markerblocks.providers.HtmlBlockProvider
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import kotlin.math.min

public open class VitePressFlavourDescriptor : GFMFlavourDescriptor() {
    override val markerProcessorFactory: MarkerProcessorFactory
        get() = object : MarkerProcessorFactory {
            override fun createMarkerProcessor(productionHolder: ProductionHolder): MarkerProcessor<*> =
                VitePressMarkerProcessor(productionHolder, GFMConstraints.BASE)
        }

    private class VitePressMarkerProcessor(
        productionHolder: ProductionHolder,
        constraintsBase: CommonMarkdownConstraints,
    ) : CommonMarkMarkerProcessor(
        productionHolder, constraintsBase,
    ) {
        override fun getMarkerBlockProviders(): List<MarkerBlockProvider<StateInfo>> =
            super.getMarkerBlockProviders().map {
                if (it is HtmlBlockProvider) VitePressHtmlBlockProvider() else it
            } + GitHubTableMarkerProvider()

        override fun populateConstraintsTokens(
            pos: LookaheadText.Position,
            constraints: MarkdownConstraints,
            productionHolder: ProductionHolder,
        ) {
            if (constraints !is GFMConstraints || !constraints.hasCheckbox()) {
                super.populateConstraintsTokens(pos, constraints, productionHolder)
                return
            }

            val line = pos.currentLine
            var offset = pos.offsetInCurrentLine
            while (offset < line.length && line[offset] != '[') {
                offset++
            }
            if (offset == line.length) {
                super.populateConstraintsTokens(pos, constraints, productionHolder)
                return
            }

            val type = when (constraints.types.lastOrNull()) {
                '>' ->
                    MarkdownTokenTypes.BLOCK_QUOTE

                '.', ')' ->
                    MarkdownTokenTypes.LIST_NUMBER

                else ->
                    MarkdownTokenTypes.LIST_BULLET
            }
            val middleOffset = pos.offset - pos.offsetInCurrentLine + offset
            val endOffset = min(
                pos.offset - pos.offsetInCurrentLine + constraints.getCharsEaten(pos.currentLine),
                pos.nextLineOrEofOffset,
            )

            productionHolder.addProduction(
                listOf(
                    SequentialParser.Node(pos.offset..middleOffset, type),
                    SequentialParser.Node(middleOffset..endOffset, GFMTokenTypes.CHECK_BOX),
                ),
            )
        }
    }

    public companion object : VitePressFlavourDescriptor()

}