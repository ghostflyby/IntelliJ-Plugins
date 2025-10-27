/*
 * Copyright (c) 2025 ghostflyby
 * SPDX-FileCopyrightText: 2025 ghostflyby
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

package dev.ghostflyby.selectionlivetemplate

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateSubstitutor
import com.intellij.codeInsight.template.impl.TemplateImpl
import com.intellij.codeInsight.template.impl.TemplateSubstitutionContext


internal class ExpandSelectionTemplateSubstitutor : TemplateSubstitutor {
    override fun substituteTemplate(
        substitutionContext: TemplateSubstitutionContext,
        template: TemplateImpl,
    ): TemplateImpl? {
        val previous = substitutionContext.document.replacedSelection
        substitutionContext.document.replacedSelection = null


        if (previous.isNullOrEmpty())
            return null

        val string = (template as Template).string

        if (!string.contains(SELECTION)) {
            return null
        }
        val newString = string
            .replace(
                SELECTION,
                previous.replace("$", "$$")
            )

        return TemplateImpl(template.key, newString, template.groupName).apply {
            template.variables.forEach {
                addVariable(it)
            }
        }
    }

    companion object {
        private const val SELECTION = "$${Template.SELECTION}$"
    }
}
