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

import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.IStubFileElementType
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.lexer.MarkdownToplevelLexer
import org.intellij.plugins.markdown.lang.parser.MarkdownParserAdapter
import org.intellij.plugins.markdown.lang.parser.MarkdownParserDefinition
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

public class VitePressParserDefinition : MarkdownParserDefinition() {

    override fun createParser(project: Project?): PsiParser {
        return MarkdownParserAdapter(VitePressFlavourDescriptor)
    }

    override fun createLexer(project: Project?): Lexer {
        return MarkdownToplevelLexer(VitePressFlavourDescriptor)
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile {
        return MarkdownFile(viewProvider, VitePressFlavourDescriptor)
    }

    override fun getFileNodeType(): IFileElementType {
        return VitePressTokenTypes.FILE_ELEMENT_TYPE
    }


}

public interface VitePressTokenTypes : MarkdownTokenTypes {
    public companion object {
        @JvmField
        public val FILE_ELEMENT_TYPE: IFileElementType = IStubFileElementType<PsiFileStub<PsiFile>>(
            "VITE_PRESS_FILE",
            VitePressLanguage,
        )
    }
}
