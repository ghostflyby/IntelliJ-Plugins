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

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Renames a YAML field value to [newValue].
 * Only the first matching field named [fieldName] is modified.
 */
internal class RenameFieldQuickFix(
    private val fieldName: String,
    private val newValue: String,
) : LocalQuickFix {

    override fun getName(): String = SkillMdBundle.message("quickfix.rename.field", fieldName, newValue)
    override fun getFamilyName(): String = "Rename field"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val psiFile: PsiFile = descriptor.psiElement.containingFile ?: return
        val document = psiFile.viewProvider.document ?: return
        val text = psiFile.text
        val regex = Regex("""^$fieldName\s*:\s*.*$""", RegexOption.MULTILINE)
        val match = regex.find(text) ?: return
        val newText = text.replaceRange(match.range, "$fieldName: $newValue")
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(newText)
        }
    }
}
