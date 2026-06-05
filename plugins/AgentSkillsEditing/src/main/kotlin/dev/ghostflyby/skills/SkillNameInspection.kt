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
import com.intellij.lang.injection.InjectedLanguageManager.getInstance
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import org.intellij.plugins.markdown.lang.MarkdownElementType
import org.intellij.plugins.markdown.lang.parser.blocks.frontmatter.FrontMatterHeaderMarkerProvider
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

/**
 * Validates the SKILL.md `name` field beyond what JSON Schema can express:
 *
 * - name regex with user-friendly error message + QuickFix
 * - name must match parent directory name + QuickFix
 *
 * Structural checks (required, maxLength, pattern) are handled by JSON Schema.
 */
internal class SkillNameInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val yamlFile = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val injectionManager = getInstance(yamlFile.project)
        val host = injectionManager.getInjectionHost(yamlFile) ?: return PsiElementVisitor.EMPTY_VISITOR
        if (host.elementType != MarkdownElementType.platformType(FrontMatterHeaderMarkerProvider.FRONT_MATTER_HEADER)) return PsiElementVisitor.EMPTY_VISITOR
        val physicalFile = host.containingFile
        if (physicalFile.name != "SKILL.md") return PsiElementVisitor.EMPTY_VISITOR

        val parentDir = physicalFile.virtualFile?.parent?.name

        return object : YamlPsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file !== yamlFile) return
                val mapping = yamlFile.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return
                val keyValue = mapping.getKeyValueByKey("name") ?: return
                val rawName = keyValue.valueText
                val range = TextRange(0, keyValue.textLength)

                if (!NAME_REGEX.matches(rawName)) {
                    holder.registerProblem(
                        keyValue,
                        SkillMdBundle.message("name.error.invalid.chars", rawName),
                        ProblemHighlightType.WARNING,
                        range,
                        RenameFieldQuickFix(rawName.toValidSkillName()),
                    )
                }

                if (parentDir != null && rawName != parentDir) {
                    holder.registerProblem(
                        keyValue,
                        SkillMdBundle.message("name.error.mismatch", rawName, parentDir),
                        ProblemHighlightType.WARNING,
                        range,
                        RenameFieldQuickFix(parentDir),
                    )
                }
            }
        }
    }
}

/**
 * Renames a YAML key's value using a PSI pointer to the exact element.
 * Applies the change as a document-level write action on the host file.
 */
internal class RenameFieldQuickFix(
    private val newValue: String,
) : LocalQuickFix {

    override fun getName(): String = SkillMdBundle.message("quickfix.rename.field", newValue)
    override fun getFamilyName(): String = SkillMdBundle.message("quickfix.family.rename.field")

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val keyValue = descriptor.psiElement as? YAMLKeyValue ?: return
        val y = YAMLElementGenerator.getInstance(project)
        val new = y.createYamlKeyValue(keyValue.keyText, newValue)
        keyValue.replace(new)
    }
}

private val NAME_REGEX = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?$""")

private fun String.toValidSkillName(): String =
    lowercase()
        .replace(Regex("[^a-z0-9-]+"), "-")
        .replace(Regex("-{2,}"), "-")
        .trim('-')
