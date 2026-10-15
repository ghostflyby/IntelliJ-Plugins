/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import dev.ghostflyby.mcp.filecontent.getOrCreateDocument

/**
 * Collect [RestProblemItem]s for a single file using the configured inspection profile.
 */
internal suspend fun collectRestProblemsForFile(
    project: Project,
    file: VirtualFile,
    isOnTheFly: Boolean = false,
): List<RestProblemItem> {
    val psiFile = readAction { PsiManager.getInstance(project).findFile(file) } ?: return emptyList()
    val document = readAction {
        PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: getOrCreateDocument(file)
    } ?: return emptyList()

    // Inspection results from profile tools
    val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
    val tools = profile.allTools.filterIsInstance<LocalInspectionToolWrapper>()
    val inspectionResults = if (tools.isNotEmpty()) {
        coroutineToIndicator { indicator ->
            val resultMap = InspectionEngine.inspectEx(
                tools, psiFile, psiFile.textRange, psiFile.textRange,
                isOnTheFly, false, true,
                indicator,
            ) { _, _ -> true }
            resultMap.values.flatten().mapNotNull { desc ->
                toRestProblemItem(project, file, desc)
            }
        }
    } else emptyList()

    // Parser-level syntax errors
    val psiErrors: List<RestProblemItem> = readAction {
        PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java).mapNotNull { error ->
            val offset = error.textRange.startOffset.coerceIn(0, document.textLength)
            val line = document.getLineNumber(offset)
            val base = project.basePath
            val display = if (base != null && file.path.startsWith(base)) {
                file.path.removePrefix("$base/")
            } else {
                file.url
            }
            RestProblemItem(
                severity = "ERROR",
                fileUrl = file.url,
                encodedFileUrl = encodeRoutePathSegment(file.url),
                filePath = display,
                line = line + 1,
                column = (offset - document.getLineStartOffset(line)).coerceAtLeast(0) + 1,
                endLine = line + 1,
                endColumn = 0,
                inspectionShortName = "SyntaxError",
                inspectionDisplayName = "Syntax error",
                groupPath = listOf("Syntax"),
                message = error.errorDescription,
                lineText = document.getText(
                    com.intellij.openapi.util.TextRange(
                        document.getLineStartOffset(line),
                        document.getLineEndOffset(line).coerceAtMost(document.textLength),
                    ),
                ),
                fixes = emptyList(),
            )
        }
    }
    return (inspectionResults + psiErrors).distinctBy { "${it.fileUrl}:${it.line}:${it.message}" }
}

/**
 * Convert a [ProblemDescriptor] to a [RestProblemItem].
 */
internal fun toRestProblemItem(
    project: Project,
    file: VirtualFile,
    desc: ProblemDescriptor,
): RestProblemItem {
    val el = desc.psiElement ?: return toFallbackItem(project, file, desc)
    val basePath = project.basePath
    val display =
        if (basePath != null && file.path.startsWith(basePath)) file.path.removePrefix("$basePath/") else file.path
    return RestProblemItem(
        severity = when (desc.highlightType) {
            ProblemHighlightType.ERROR, ProblemHighlightType.GENERIC_ERROR -> "ERROR"
            ProblemHighlightType.WARNING, ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> "WARNING"
            ProblemHighlightType.WEAK_WARNING -> "WEAK_WARNING"
            ProblemHighlightType.INFORMATION -> "INFO"
            else -> "WARNING"
        },
        fileUrl = file.url,
        encodedFileUrl = encodeRoutePathSegment(file.url),
        filePath = display,
        line = el.textRange.startOffset,
        column = 0,
        inspectionShortName = desc.javaClass.simpleName,
        inspectionDisplayName = desc.descriptionTemplate.take(80),
        groupPath = listOf("Inspection"),
        message = desc.descriptionTemplate,
        fixes = emptyList(),
    )
}

private fun toFallbackItem(project: Project, file: VirtualFile, desc: ProblemDescriptor): RestProblemItem {
    val basePath = project.basePath
    val display =
        if (basePath != null && file.path.startsWith(basePath)) file.path.removePrefix("$basePath/") else file.path
    return RestProblemItem(
        severity = "WARNING",
        fileUrl = file.url,
        encodedFileUrl = encodeRoutePathSegment(file.url),
        filePath = display,
        line = 0,
        column = 0,
        inspectionShortName = desc.javaClass.simpleName,
        inspectionDisplayName = desc.descriptionTemplate.take(80),
        groupPath = listOf("Inspection"),
        message = desc.descriptionTemplate,
        fixes = emptyList(),
    )
}
