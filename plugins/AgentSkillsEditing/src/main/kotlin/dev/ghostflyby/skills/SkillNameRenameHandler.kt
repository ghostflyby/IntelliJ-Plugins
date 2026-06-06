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

package dev.ghostflyby.skills

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.refactoring.rename.RenameHandler
import org.jetbrains.yaml.psi.YAMLScalar

internal class SkillNameRenameHandler : RenameHandler {

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean =
        findRenameContext(dataContext) != null

    override fun isRenaming(dataContext: DataContext): Boolean =
        isAvailableOnDataContext(dataContext)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null) return
        val scalar = dataContext?.let(::findRenameContext) ?: return
        performSkillNameInlineRename(scalar, editor, project)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = Unit

    private fun findRenameContext(dataContext: DataContext): YAMLScalar? {
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return null
        val injectionManager = InjectedLanguageManager.getInstance(file.project)
        val hostFile = injectionManager.getTopLevelFile(file)

        val hostEditor = CommonDataKeys.HOST_EDITOR.getData(dataContext)
            ?: CommonDataKeys.EDITOR.getData(dataContext)
            ?: return null

        return hostFile.skillNameScalarAt(hostEditor.caretModel.offset)
    }
}
