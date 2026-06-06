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

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

internal class SkillDirRenameSearchExecutor : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>() {

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val element = queryParameters.elementToSearch
        val dir = element as? PsiDirectory ?: return
        val skillFile = dir.skillMarkdownFile ?: return
        val scalar = skillFile.skillNameScalar() ?: return
        for (ref in scalar.references) {
            if (ref.isReferenceTo(dir) && !consumer.process(ref)) break
        }
    }
}
