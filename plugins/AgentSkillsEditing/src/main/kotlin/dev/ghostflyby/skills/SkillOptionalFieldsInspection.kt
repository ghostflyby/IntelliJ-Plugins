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
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

internal class SkillOptionalFieldsInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val ctx = resolveSkillContext(file) ?: return
                val injectionManager = InjectedLanguageManager.getInstance(file.project)
                val header = ctx.hostHeader
                val hr = header.textRange

                ctx.keyValues["compatibility"]?.let { kv ->
                    val scalar = kv.value as? YAMLScalar
                    val text = scalar?.textValue ?: return@let
                    if (text.length > COMPATIBILITY_MAX_LENGTH) {
                        val vr = kv.value?.let { injectionManager.injectedToHost(it, it.textRange) } ?: hr
                        holder.registerProblem(header, vr, SkillMdBundle.message("optional.error.compatibility.too.long", text.length))
                    }
                }

                ctx.keyValues["metadata"]?.let { kv ->
                    val value = kv.value
                    if (value != null && value !is YAMLMapping) {
                        val keyEl = kv.key ?: return@let
                        val kr = injectionManager.injectedToHost(keyEl, keyEl.textRange)
                        holder.registerProblem(header, kr, SkillMdBundle.message("optional.error.metadata.not.object", value.javaClass.simpleName))
                    }
                }

                ctx.keyValues["allowed-tools"]?.let { kv ->
                    val value = kv.value
                    if (value != null && value !is YAMLScalar) {
                        val keyEl = kv.key ?: return@let
                        val kr = injectionManager.injectedToHost(keyEl, keyEl.textRange)
                        holder.registerProblem(header, kr, SkillMdBundle.message("optional.error.allowed.tools.not.string", value.javaClass.simpleName))
                    }
                }
            }
        }
    }
}
