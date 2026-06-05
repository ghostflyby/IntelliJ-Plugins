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

internal class SkillDescriptionInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val ctx = resolveSkillContext(file) ?: return
                val injectionManager = InjectedLanguageManager.getInstance(file.project)
                val header = ctx.hostHeader
                val hr = header.textRange
                val kv = ctx.keyValues["description"]

                if (kv == null) {
                    holder.registerProblem(header, hr, SkillMdBundle.message("description.error.missing"))
                    return
                }

                val scalar = kv.value as? YAMLScalar
                val rawDesc = scalar?.textValue ?: ""

                if (rawDesc.isEmpty()) {
                    holder.registerProblem(header, hr, SkillMdBundle.message("description.error.empty"))
                    return
                }

                if (rawDesc.length > DESCRIPTION_MAX_LENGTH) {
                    val vr = kv.value?.let { injectionManager.injectedToHost(it, it.textRange) } ?: hr
                    holder.registerProblem(header, vr, SkillMdBundle.message("description.error.too.long", rawDesc.length))
                }
            }
        }
    }
}
