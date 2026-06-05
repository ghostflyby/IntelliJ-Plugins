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
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Checks that the YAML frontmatter is structurally valid:
 * a top-level mapping where all values are scalars (except metadata).
 */
internal class SkillFrontmatterStructureInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val ctx = resolveSkillContext(file) ?: return
                val injectionManager = InjectedLanguageManager.getInstance(file.project)
                val header = ctx.hostHeader
                val hostRange = header.textRange

                if (ctx.topMapping == null) {
                    holder.registerProblem(header, hostRange, SkillMdBundle.message("structure.error.not.mapping"))
                    return
                }

                for (kv in ctx.topMapping.keyValues) {
                    val key = kv.keyText
                    val value = kv.value
                    if (key == "metadata") continue
                    if (value != null && value !is YAMLScalar) {
                        val keyEl = kv.key ?: continue
                        val keyRange = injectionManager.injectedToHost(keyEl, keyEl.textRange)
                        holder.registerProblem(header, keyRange, SkillMdBundle.message("structure.error.field.not.string", key, value.javaClass.simpleName))
                    }
                }
            }
        }
    }
}
