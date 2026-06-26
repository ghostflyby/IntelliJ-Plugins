/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.skills

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import org.jetbrains.yaml.psi.YAMLScalar

internal class SkillDirRenameSearchExecutor : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>() {

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val element = queryParameters.elementToSearch
        val dir = element as? PsiDirectory ?: return
        val scalar = dir.skillMarkdownFile?.skillNameScalar() ?: return
        val reference = SkillDirectoryNameReference(scalar, dir)
        if (reference.isReferenceTo(dir)) {
            consumer.process(reference)
        }
    }
}

internal class SkillDirectoryNameReference(
    element: YAMLScalar,
    private val directory: PsiDirectory,
) : PsiReferenceBase<YAMLScalar>(element, false) {
    override fun getRangeInElement() = element.valueTextRangeInElement()

    override fun resolve(): PsiElement = directory

    override fun handleElementRename(newElementName: String): PsiElement? {
        val scalar = directory.skillMarkdownFile?.skillNameScalar() ?: return null
        return ElementManipulators.handleContentChange(scalar, scalar.valueTextRangeInElement(), newElementName)
    }
}
