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

import com.intellij.codeInspection.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.refactoring.RefactoringActionHandlerFactory
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLValue

internal class SkillNameInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file !is YAMLFile) return
                val physicalFile = file.skillMarkdownFile ?: return
                val kv = file.topLevelKeyValue(SKILL_NAME_KEY) ?: return
                val rawName = kv.valueText
                val dirName = physicalFile.parent?.name ?: return
                val range = TextRange(0, kv.textLength)
                val nameValid = rawName.isValidSkillName()
                val dirValid = dirName.isValidSkillName()
                val match = rawName == dirName

                when {
                    nameValid && dirValid && !match ->
                        holder.registerProblem(
                            kv, SkillMdBundle.message("consistency.mismatch", dirName),
                            ProblemHighlightType.WARNING, range,
                            FixNameQuickFix(dirName), RenameDirQuickFix(rawName),
                        )

                    !nameValid && !dirValid && match ->
                        reportFormat(holder, kv, range, rawName)

                    !nameValid && !dirValid -> {
                        reportFormat(holder, kv, range, rawName)
                        holder.registerProblem(
                            kv, SkillMdBundle.message("consistency.mismatch", dirName),
                            ProblemHighlightType.WARNING, range,
                            FixNameQuickFix(dirName), RenameDirQuickFix(rawName),
                        )
                    }

                    !nameValid -> {
                        val fixes = mutableListOf<LocalQuickFix>()
                        if (rawName.canBeFixedToSkillName()) fixes.add(FixNameQuickFix(rawName.toSkillNameCandidate()))
                        fixes.add(RenameSkillQuickFix(SmartPointerManager.createPointer(kv.value ?: return)))
                        holder.registerProblem(
                            kv, SkillMdBundle.message("consistency.mismatch", dirName),
                            ProblemHighlightType.WARNING, range, *fixes.toTypedArray(),
                        )
                    }

                    !dirValid -> {
                        val fixes = mutableListOf<LocalQuickFix>()
                        if (dirName.canBeFixedToSkillName()) fixes.add(RenameDirQuickFix(dirName.toSkillNameCandidate()))
                        fixes.add(RenameSkillQuickFix(SmartPointerManager.createPointer(kv.value ?: return)))
                        holder.registerProblem(
                            kv, SkillMdBundle.message("consistency.mismatch", dirName),
                            ProblemHighlightType.WARNING, range, *fixes.toTypedArray(),
                        )
                    }
                }
            }
        }
    }

    private fun reportFormat(holder: ProblemsHolder, kv: YAMLKeyValue, range: TextRange, rawName: String) {
        val fixes = mutableListOf<LocalQuickFix>()
        if (rawName.canBeFixedToSkillName()) fixes.add(FixNameQuickFix(rawName.toSkillNameCandidate()))
        fixes.add(RenameSkillQuickFix(SmartPointerManager.createPointer(kv.value ?: return)))
        holder.registerProblem(
            kv, SkillMdBundle.message("format.name.invalid", rawName),
            ProblemHighlightType.WARNING, range, *fixes.toTypedArray(),
        )
    }
}

internal class FixNameQuickFix(private val newValue: String) : LocalQuickFix {
    override fun getName(): String = SkillMdBundle.message("quickfix.fix.name", newValue)
    override fun getFamilyName(): String = SkillMdBundle.message("quickfix.family.name")
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val kv = descriptor.psiElement as? YAMLKeyValue ?: return
        kv.replace(YAMLElementGenerator.getInstance(project).createYamlKeyValue(kv.keyText, newValue))
    }
}

internal class RenameDirQuickFix(private val newName: String) : LocalQuickFix {
    override fun getName(): String = SkillMdBundle.message("quickfix.rename.dir", newName)
    override fun getFamilyName(): String = SkillMdBundle.message("quickfix.family.dir")
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val kv = descriptor.psiElement as? YAMLKeyValue ?: return
        val yamlFile = kv.containingFile as? YAMLFile ?: return
        yamlFile.skillDirectory?.virtualFile?.rename(this, newName)
    }
}

/** Renames the YAML name value element. Directory follows via DirToYamlRef reverse reference. */
internal class RenameSkillQuickFix(
    private val scalarPtr: SmartPsiElementPointer<YAMLValue>,
) : LocalQuickFix {
    override fun getName(): String = SkillMdBundle.message("quickfix.rename.skill")
    override fun getFamilyName(): String = SkillMdBundle.message("quickfix.family.rename")
    override fun startInWriteAction(): Boolean = false
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val scalar = scalarPtr.element ?: return
        ApplicationManager.getApplication().invokeLater {
            RefactoringActionHandlerFactory.getInstance().createRenameHandler()
                .invoke(project, arrayOf(scalar), null)
        }
    }
}
