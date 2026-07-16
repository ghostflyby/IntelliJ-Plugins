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

    override fun isTriggeredBy(daemonSteps: List<String>): Boolean =
        daemonSteps.any { step -> step in wildcardImportSteps }

    override suspend fun preprocess(
        request: SpotlessFormattingPreprocessRequest,
    ): SpotlessFormattingPreprocessResult? {
        val project = request.psiFile.project
        if (DumbService.isDumb(project)) {
            return null
        }
        val skippedSteps = request.daemonSteps.filterTo(linkedSetOf()) { step -> step in wildcardImportSteps }
        if (skippedSteps.isEmpty()) {
            return null
        }
        return withContext(Dispatchers.EDT) {
            if (DumbService.isDumb(project) || !request.psiFile.isValid) {
                return@withContext null
            }
            OptimizeImportsProcessor(project, request.psiFile).run()
            val document = FileDocumentManager.getInstance().getDocument(request.psiFile.virtualFile)
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
