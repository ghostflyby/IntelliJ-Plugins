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

import org.intellij.markdown.flavours.gfm.GFMConstraints
import org.intellij.markdown.flavours.gfm.table.GitHubTableMarkerProvider
import org.intellij.markdown.parser.LookaheadText
import org.intellij.markdown.parser.MarkerProcessor
import org.intellij.markdown.parser.MarkerProcessorFactory
import org.intellij.markdown.parser.ProductionHolder
import org.intellij.markdown.parser.constraints.MarkdownConstraints
import org.intellij.markdown.parser.markerblocks.MarkerBlock
import org.intellij.markdown.parser.markerblocks.MarkerBlockProvider
import org.intellij.markdown.parser.markerblocks.providers.HtmlBlockProvider
import org.intellij.plugins.markdown.lang.parser.MarkdownDefaultFlavour
import org.intellij.plugins.markdown.lang.parser.MarkdownDefaultMarkerProcessor

public open class VitePressFlavourDescriptor : MarkdownDefaultFlavour() {
    override val markerProcessorFactory: MarkerProcessorFactory
        get() = VitePressMarkerProcessor.Factory

    public class VitePressMarkerProcessor(
        productionHolder: ProductionHolder,
        constraintsBase: MarkdownConstraints,
    ) : MarkdownDefaultMarkerProcessor(
        productionHolder, constraintsBase,
    ) {

        // Offset of a line whose paragraph should be suppressed (used for closing fences).
        private var skipParagraphOffset: Int = -1

        override fun getMarkerBlockProviders(): List<MarkerBlockProvider<StateInfo>> {
            return listOf(VitePressCustomFenceProvider { skipParagraphOffset = it }) +
                    super.getMarkerBlockProviders().map {
                        if (it is HtmlBlockProvider) VitePressHtmlBlockProvider()
                        else it
                    } + GitHubTableMarkerProvider()
        }

        override fun createNewMarkerBlocks(
            pos: LookaheadText.Position,
            productionHolder: ProductionHolder,
        ): List<MarkerBlock> {
            if (skipParagraphOffset == pos.offset) {
                skipParagraphOffset = -1
                return emptyList()
            }
            return super.createNewMarkerBlocks(pos, productionHolder)
        }

        public object Factory : MarkerProcessorFactory {
            override fun createMarkerProcessor(productionHolder: ProductionHolder): MarkerProcessor<*> =
                VitePressMarkerProcessor(productionHolder, GFMConstraints.BASE)
        }

    }

    public companion object : VitePressFlavourDescriptor()

}
