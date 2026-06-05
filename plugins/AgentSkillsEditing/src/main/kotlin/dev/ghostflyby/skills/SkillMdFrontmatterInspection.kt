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

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import org.intellij.plugins.markdown.lang.MarkdownElementType
import org.intellij.plugins.markdown.lang.parser.blocks.frontmatter.FrontMatterHeaderMarkerProvider
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

/**
 * Checks that SKILL.md has a YAML frontmatter block (--- delimiters).
 * Schema can't express "must have delimiters", so this is a pure inspection.
 */
internal class SkillMdFrontmatterInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file.name != "SKILL.md" || file !is MarkdownFile) return
                if (file.firstChild.elementType != MarkdownElementType.platformType(FrontMatterHeaderMarkerProvider.FRONT_MATTER_HEADER)) {
                    holder.registerProblem(
                        file, file.textRange,
                        SkillMdBundle.message("frontmatter.missing"),
                        AddFrontmatterQuickFix(),
                    )
                }
            }
        }
    }
}

internal class AddFrontmatterQuickFix : LocalQuickFix {
    override fun getName(): String = SkillMdBundle.message("quickfix.add.frontmatter")
    override fun getFamilyName(): String = SkillMdBundle.message("quickfix.family.add.frontmatter")
    override fun applyFix(
        project: Project,
        descriptor: ProblemDescriptor,
    ) {
        val psiFile = descriptor.psiElement.containingFile ?: return
        val document = psiFile.viewProvider.document ?: return
        val frontmatter = """
                |---
                |
                |---
                |
                |""".trimMargin()
        document.insertString(0, frontmatter)
    }
}
