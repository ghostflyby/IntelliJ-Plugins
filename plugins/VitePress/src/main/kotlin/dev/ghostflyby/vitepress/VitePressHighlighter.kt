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

import com.intellij.lexer.LayeredLexer
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.util.LayerDescriptor
import com.intellij.openapi.editor.ex.util.LayeredLexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.EditorHighlighterProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.vitepress.markdown.InlineHtmlAwareToplevelLexer
import dev.ghostflyby.vitepress.markdown.VitePressFlavourDescriptor
import org.intellij.plugins.markdown.highlighting.MarkdownSyntaxHighlighter
import org.intellij.plugins.markdown.lang.MarkdownTokenTypeSets
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.lexer.MarkdownMergingLexer
import org.jetbrains.vuejs.lang.html.VueLanguage


private object VPMarkdownHighlighter : MarkdownSyntaxHighlighter() {
    override fun getHighlightingLexer(): Lexer {
        return VitePressSyntaxHighlighterLexer()
    }
}

internal class VitePressSyntaxHighlighterLexer :
    LayeredLexer(InlineHtmlAwareToplevelLexer(VitePressFlavourDescriptor)) {
    init {
        registerSelfStoppingLayer(
            MarkdownMergingLexer(), MarkdownTokenTypeSets.INLINE_HOLDING_ELEMENT_TYPES.getTypes(),
            emptyArray(),
        )
    }
}

private class VitePressHighlighter(templateHighlighter: SyntaxHighlighter, scheme: EditorColorsScheme) :
    LayeredLexerEditorHighlighter(VPMarkdownHighlighter, scheme) {
    init {
        this.registerLayer(MarkdownTokenTypes.HTML_BLOCK_CONTENT, LayerDescriptor(templateHighlighter, "\n"))
        this.registerLayer(MarkdownTokenTypes.HTML_TAG, LayerDescriptor(templateHighlighter, "\n"))
    }
}

internal class VitePressHighlighterFactory : EditorHighlighterProvider {

    override fun getEditorHighlighter(
        project: Project?,
        fileType: FileType,
        virtualFile: VirtualFile?,
        colors: EditorColorsScheme,
    ): EditorHighlighter {


        val vue = SyntaxHighlighterFactory.getSyntaxHighlighter(VueLanguage, project, virtualFile)
        return VitePressHighlighter(
            vue,
            colors,
        )
    }
}
