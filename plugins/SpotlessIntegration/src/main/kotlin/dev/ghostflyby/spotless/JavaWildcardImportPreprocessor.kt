/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class JavaWildcardImportPreprocessor : SpotlessFormattingPreprocessor {
    override fun isApplicableTo(psiFile: PsiFile): Boolean =
        psiFile.fileType == JavaFileType.INSTANCE

    override suspend fun preprocess(
        context: SpotlessFormattingPreprocessContext,
    ): SpotlessFormattingPreprocessResult? {
        val skippedSteps = context.daemonSteps.filterTo(linkedSetOf()) { step -> step in wildcardImportSteps }
        if (skippedSteps.isEmpty()) {
            return null
        }
        val project = context.psiFile.project
        if (DumbService.isDumb(project)) {
            return null
        }
        return withContext(Dispatchers.EDT) {
            if (DumbService.isDumb(project) || !context.psiFile.isValid) {
                return@withContext null
            }
            OptimizeImportsProcessor(project, context.psiFile).run()
            val document = FileDocumentManager.getInstance().getDocument(context.psiFile.virtualFile)
                ?: return@withContext null
            PsiDocumentManager.getInstance(project).apply {
                doPostponedOperationsAndUnblockDocument(document)
                commitDocument(document)
            }
            SpotlessFormattingPreprocessResult(
                content = document.immutableCharSequence,
                skippedSteps = skippedSteps,
            )
        }
    }

}

internal val wildcardImportSteps = setOf("expandWildcardImports", "forbidWildcardImports")
