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

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.templateLanguages.TemplateLanguageFileViewProvider
import org.intellij.plugins.markdown.lang.MarkdownElementTypes.MARKDOWN_TEMPLATE_DATA
import org.jetbrains.vuejs.lang.html.VueLanguage

public class VitePressFileViewProvider(manager: PsiManager, virtualFile: VirtualFile, eventSystemEnabled: Boolean) :
    MultiplePsiFilesPerDocumentFileViewProvider(
        manager,
        virtualFile, eventSystemEnabled,
    ), TemplateLanguageFileViewProvider {
    override fun getBaseLanguage(): VitePressLanguage {
        return VitePressLanguage
    }

    override fun getTemplateDataLanguage(): VueLanguage {
        return VueLanguage
    }


    override fun cloneInner(fileCopy: VirtualFile): VitePressFileViewProvider {
        return VitePressFileViewProvider(manager, fileCopy, false)
    }

    private val langs = setOf(VitePressLanguage, VueLanguage)

    override fun getLanguages(): Set<Language> = langs

    override fun createFile(language: Language): PsiFile? {
        if (language == VitePressLanguage) {
            return LanguageParserDefinitions.INSTANCE.forLanguage(language).createFile(this)
        }
        if (language != templateDataLanguage) return null
        val file = LanguageParserDefinitions.INSTANCE.forLanguage(language).createFile(this)
        if (file is PsiFileImpl) {
            file.contentElementType = MARKDOWN_TEMPLATE_DATA
        }
        return file
    }

    public class Factory : FileViewProviderFactory {
        override fun createFileViewProvider(
            file: VirtualFile,
            language: Language,
            manager: PsiManager,
            eventSystemEnabled: Boolean,
        ): FileViewProvider {
            return VitePressFileViewProvider(manager, file, eventSystemEnabled)
        }
    }


}

