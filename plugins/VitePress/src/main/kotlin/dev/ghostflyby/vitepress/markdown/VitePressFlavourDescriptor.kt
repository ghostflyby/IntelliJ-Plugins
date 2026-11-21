/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
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
            return buildList {
                addAll(listOf(VitePressCustomFenceProvider { this@VitePressMarkerProcessor.skipParagraphOffset = it }))
                super.getMarkerBlockProviders().mapTo(this) {
                    if (it is HtmlBlockProvider) VitePressHtmlBlockProvider()
                    else it
                }
                add(GitHubTableMarkerProvider())
            }
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
