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

package dev.ghostflyby.mcp.quality

import com.intellij.codeInsight.actions.AbstractLayoutCodeProcessor
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.codeInspection.*
import com.intellij.codeInspection.ex.GlobalInspectionContextBase
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.common.*
import dev.ghostflyby.mcp.scope.*
import kotlinx.coroutines.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Predicate
import kotlin.time.Duration.Companion.milliseconds

@Suppress("FunctionName")
internal class CodeQualityMcpTools : McpToolset {

    @McpTool
    @McpDescription(
        "Analyze a text file by VFS URL and return IDE highlight problems " +
            "(errors only or errors+warnings).",
    )
    suspend fun quality_get_file_problems(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        fileUrl: String,
        @McpDescription("Whether to include only errors or include both errors and warnings.")
        errorsOnly: Boolean = true,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
    ): QualityFileProblemsResultDto {
        validateTimeout(timeoutMillis)
        reportActivity(Bundle.message("tool.activity.quality.file.problems", fileUrl, errorsOnly, timeoutMillis))
        val project = currentCoroutineContext().project
        val file = resolveFileByUrl(fileUrl)
        val filePath = relativizePath(project.basePath, file.path)
        var timedOut = false
        val problems = withTimeoutOrNull(timeoutMillis.milliseconds) {
            withBackgroundProgress(
                project,
                Bundle.message("progress.title.quality.file.problems", file.name),
                cancellable = true,
            ) {
                analyzeProblemsInFile(project, file, errorsOnly)
            }
        } ?: run {
            timedOut = true
            emptyList()
        }
        return QualityFileProblemsResultDto(
            fileUrl = file.url,
            filePath = filePath,
            problems = problems,
            timedOut = timedOut,
        )
    }

    @McpTool
    @McpDescription(
        "Analyze files inside a resolved scope descriptor and return IDE highlight problems. " +
            "Scope traversal currently targets project-content files and filters by scope.contains(file).",
    )
    suspend fun quality_get_scope_problems(
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Whether to include only errors or include both errors and warnings.")
        errorsOnly: Boolean = true,
        @McpDescription("Maximum number of files to analyze from the matched scope.")
        maxFileCount: Int = 200,
        @McpDescription("Maximum number of problems to return across all files.")
        maxProblemCount: Int = 5000,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
        @McpDescription("Whether to continue when a single file analysis fails.")
        continueOnError: Boolean = true,
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
        allowUiInteractiveScopes: Boolean = false,
    ): QualityScopeProblemsResultDto {
        if (maxFileCount < 1) mcpFail("maxFileCount must be >= 1.")
        if (maxProblemCount < 1) mcpFail("maxProblemCount must be >= 1.")
        validateTimeout(timeoutMillis)
        reportActivity(
            Bundle.message(
                "tool.activity.quality.scope.problems.start",
                maxFileCount,
                maxProblemCount,
                errorsOnly,
                continueOnError,
                timeoutMillis,
            ),
        )
        val project = currentCoroutineContext().project
        val resolvedScope = ScopeResolverService.getInstance(project).resolveDescriptor(
            project = project,
            descriptor = scope,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        val scopeFiles = collectProjectContentFilesInScope(project, resolvedScope.scope, maxFileCount)
        val diagnostics = mutableListOf<String>()
        if (scopeFiles.truncatedByFileLimit) {
            diagnostics += "Matched files exceed maxFileCount=$maxFileCount; analysis was truncated."
        }
        if (resolvedScope.scopeShape == ScopeShape.GLOBAL) {
            diagnostics += "Scope traversal analyzes project content files; external library files are skipped."
        }

        val scannedProgress = AtomicInteger(0)
        val finished = AtomicBoolean(false)
        val items = mutableListOf<QualityFileProblemsItemDto>()
        var analyzedFileCount = 0
        var totalProblemCount = 0
        var filesWithProblems = 0
        var timedOut = false
        var probablyHasMoreProblems = scopeFiles.truncatedByFileLimit
        var probablyHasMoreMatchingFiles = scopeFiles.truncatedByFileLimit

        coroutineScope {
            val progressJob = launch {
                while (isActive && !finished.get()) {
                    delay(1000)
                    reportActivity(
                        Bundle.message(
                            "tool.activity.quality.scope.problems.progress",
                            scannedProgress.get(),
                            analyzedFileCount,
                            totalProblemCount,
                        ),
                    )
                }
            }
            try {
                timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
                    withBackgroundProgress(
                        project,
                        Bundle.message("progress.title.quality.scope.problems", resolvedScope.displayName),
                        cancellable = true,
                    ) {
                        for (file in scopeFiles.files) {
                            ProgressManager.checkCanceled()
                            scannedProgress.incrementAndGet()
                            if (totalProblemCount >= maxProblemCount) {
                                probablyHasMoreProblems = true
                                break
                            }
                            val output = batchTry(continueOnError) {
                                analyzeProblemsInFile(project, file, errorsOnly)
                            }
                            val filePath = relativizePath(project.basePath, file.path)
                            if (output.error == null) {
                                analyzedFileCount++
                                val remaining = maxProblemCount - totalProblemCount
                                val problems = output.value.orEmpty()
                                val selected = if (problems.size > remaining) {
                                    probablyHasMoreProblems = true
                                    problems.take(remaining)
                                } else {
                                    problems
                                }
                                totalProblemCount += selected.size
                                if (selected.isNotEmpty()) {
                                    filesWithProblems++
                                }
                                items += QualityFileProblemsItemDto(
                                    fileUrl = file.url,
                                    filePath = filePath,
                                    problems = selected,
                                )
                            } else {
                                items += QualityFileProblemsItemDto(
                                    fileUrl = file.url,
                                    filePath = filePath,
                                    problems = emptyList(),
                                    error = output.error,
                                )
                            }
                        }
                    }
                    false
                } ?: true
            } finally {
                finished.set(true)
                progressJob.cancel()
            }
        }

        if (timedOut) {
            diagnostics += "Scope problem analysis timed out."
        }
        if (scannedProgress.get() < scopeFiles.files.size) {
            probablyHasMoreMatchingFiles = true
        }

        return QualityScopeProblemsResultDto(
            scopeDisplayName = resolvedScope.displayName,
            scopeShape = resolvedScope.scopeShape,
            errorsOnly = errorsOnly,
            scannedFileCount = scopeFiles.scannedFileCount,
            analyzedFileCount = analyzedFileCount,
            filesWithProblems = filesWithProblems,
            problemCount = totalProblemCount,
            items = items,
            probablyHasMoreMatchingFiles = probablyHasMoreMatchingFiles,
            probablyHasMoreProblems = probablyHasMoreProblems,
            timedOut = timedOut,
            canceled = false,
            diagnostics = (scope.diagnostics + resolvedScope.diagnostics + diagnostics).distinct(),
        )
    }

    @McpTool
    @McpDescription(
        "First-call friendly scope problem analysis shortcut with preset scope and default non-interactive resolution." +
            AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX,
    )
    suspend fun quality_get_scope_problems_quick(
        @McpDescription("Preset scope for quick analysis.")
        scopePreset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
        @McpDescription("Whether to include only errors or include both errors and warnings.")
        errorsOnly: Boolean = true,
        @McpDescription("Maximum number of files to analyze from the matched scope.")
        maxFileCount: Int = 200,
        @McpDescription("Maximum number of problems to return across all files.")
        maxProblemCount: Int = 5000,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
        @McpDescription("Whether to continue when a single file analysis fails.")
        continueOnError: Boolean = true,
    ): QualityScopeProblemsResultDto {
        reportActivity(
            Bundle.message(
                "tool.activity.quality.scope.problems.quick",
                scopePreset.name,
                maxFileCount,
                maxProblemCount,
                timeoutMillis,
            ),
        )
        val project = currentCoroutineContext().project
        val descriptor = buildPresetScopeDescriptor(
            project = project,
            preset = scopePreset,
            allowUiInteractiveScopes = false,
        )
        return quality_get_scope_problems(
            scope = descriptor,
            errorsOnly = errorsOnly,
            maxFileCount = maxFileCount,
            maxProblemCount = maxProblemCount,
            timeoutMillis = timeoutMillis,
            continueOnError = continueOnError,
            allowUiInteractiveScopes = false,
        )
    }

    @McpTool
    @McpDescription("Run IDE reformat action for a single file by VFS URL.")
    suspend fun quality_reformat_file(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        fileUrl: String,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
    ): QualityFileOperationResultDto {
        validateTimeout(timeoutMillis)
        reportActivity(Bundle.message("tool.activity.quality.file.reformat", fileUrl))
        val project = currentCoroutineContext().project
        val (file, psiFile) = resolveWritablePsiFile(fileUrl)
        val timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
            runCodeProcessor(ReformatCodeProcessor(psiFile, false))
            false
        } ?: true
        return QualityFileOperationResultDto(
            operation = QualityOperationKind.REFORMAT,
            fileUrl = file.url,
            filePath = relativizePath(project.basePath, file.path),
            success = !timedOut,
            timedOut = timedOut,
        )
    }

    @McpTool
    @McpDescription("Run IDE optimize-imports action for a single file by VFS URL.")
    suspend fun quality_optimize_imports_file(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        fileUrl: String,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
    ): QualityFileOperationResultDto {
        validateTimeout(timeoutMillis)
        reportActivity(Bundle.message("tool.activity.quality.file.optimize.imports", fileUrl))
        val project = currentCoroutineContext().project
        val (file, psiFile) = resolveWritablePsiFile(fileUrl)
        val timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
            runCodeProcessor(OptimizeImportsProcessor(project, psiFile))
            false
        } ?: true
        return QualityFileOperationResultDto(
            operation = QualityOperationKind.OPTIMIZE_IMPORTS,
            fileUrl = file.url,
            filePath = relativizePath(project.basePath, file.path),
            success = !timedOut,
            timedOut = timedOut,
        )
    }

    @McpTool
    @McpDescription(
        "Run IDE reformat action for project-content files matched by a scope descriptor. " +
            "Files are processed one-by-one to keep per-file result visibility.",
    )
    suspend fun quality_reformat_scope_files(
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Maximum number of files to process from matched scope.")
        maxFileCount: Int = 200,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 180000,
        @McpDescription("Whether to continue when a single file processing fails.")
        continueOnError: Boolean = true,
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
        allowUiInteractiveScopes: Boolean = false,
    ): QualityScopeOperationResultDto {
        return processScopeFilesWithOperation(
            scope = scope,
            operation = QualityOperationKind.REFORMAT,
            maxFileCount = maxFileCount,
            timeoutMillis = timeoutMillis,
            continueOnError = continueOnError,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
    }

    @McpTool
    @McpDescription(
        "Run IDE optimize-imports action for project-content files matched by a scope descriptor. " +
            "Files are processed one-by-one to keep per-file result visibility.",
    )
    suspend fun quality_optimize_imports_scope_files(
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Maximum number of files to process from matched scope.")
        maxFileCount: Int = 200,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 180000,
        @McpDescription("Whether to continue when a single file processing fails.")
        continueOnError: Boolean = true,
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
        allowUiInteractiveScopes: Boolean = false,
    ): QualityScopeOperationResultDto {
        return processScopeFilesWithOperation(
            scope = scope,
            operation = QualityOperationKind.OPTIMIZE_IMPORTS,
            maxFileCount = maxFileCount,
            timeoutMillis = timeoutMillis,
            continueOnError = continueOnError,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
    }

    @McpTool
    @McpDescription(
        "Analyze files inside a resolved scope descriptor and return problems at or above the given minimum severity, " +
            "with server-side severity aggregation.",
    )
    suspend fun quality_get_scope_problems_by_severity(
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Minimum severity threshold for returned problems.")
        minSeverity: QualitySeverityThreshold = QualitySeverityThreshold.WARNING,
        @McpDescription("Maximum number of files to analyze from the matched scope.")
        maxFileCount: Int = 200,
        @McpDescription("Maximum number of problems to collect before severity filtering.")
        maxProblemCount: Int = 5000,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
        @McpDescription("Whether to continue when a single file analysis fails.")
        continueOnError: Boolean = true,
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
        allowUiInteractiveScopes: Boolean = false,
        @McpDescription("Whether to keep files that have no problems after severity filtering.")
        includeFilesWithoutMatchingProblems: Boolean = false,
    ): QualityScopeProblemsBySeverityResultDto {
        reportActivity(
            Bundle.message(
                "tool.activity.quality.scope.problems.by.severity",
                minSeverity.name,
                maxFileCount,
                maxProblemCount,
                timeoutMillis,
            ),
        )
        val base = quality_get_scope_problems(
            scope = scope,
            errorsOnly = minSeverity == QualitySeverityThreshold.ERROR,
            maxFileCount = maxFileCount,
            maxProblemCount = maxProblemCount,
            timeoutMillis = timeoutMillis,
            continueOnError = continueOnError,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        val thresholdRank = severityRank(minSeverity)
        val severityCounts = LinkedHashMap<String, Int>()
        val items = mutableListOf<QualityFileProblemsItemDto>()
        var filesWithProblems = 0
        var problemCount = 0

        base.items.forEach { item ->
            val filteredProblems = item.problems.filter { severityRank(it.severity) >= thresholdRank }
            if (filteredProblems.isNotEmpty()) {
                filesWithProblems++
                problemCount += filteredProblems.size
                filteredProblems.forEach { problem ->
                    severityCounts.merge(problem.severity, 1) { a, b -> a + b }
                }
            }
            if (includeFilesWithoutMatchingProblems || item.error != null || filteredProblems.isNotEmpty()) {
                items += item.copy(problems = filteredProblems)
            }
        }
        val severityCountItems = severityCounts.entries
            .sortedByDescending { severityRank(it.key) }
            .map { QualitySeverityCountDto(severity = it.key, count = it.value) }

        return QualityScopeProblemsBySeverityResultDto(
            scopeDisplayName = base.scopeDisplayName,
            scopeShape = base.scopeShape,
            minSeverity = minSeverity,
            scannedFileCount = base.scannedFileCount,
            analyzedFileCount = base.analyzedFileCount,
            filesWithProblems = filesWithProblems,
            problemCount = problemCount,
            severityCounts = severityCountItems,
            items = items,
            probablyHasMoreMatchingFiles = base.probablyHasMoreMatchingFiles,
            probablyHasMoreProblems = base.probablyHasMoreProblems,
            timedOut = base.timedOut,
            canceled = base.canceled,
            diagnostics = (base.diagnostics + "Problems were filtered with minSeverity=${minSeverity.name}.").distinct(),
        )
    }

    @McpTool
    @McpDescription(
        "First-call friendly severity-filtered scope problem analysis shortcut with preset scope." +
            AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX,
    )
    suspend fun quality_get_scope_problems_by_severity_quick(
        @McpDescription("Preset scope for quick analysis.")
        scopePreset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
        @McpDescription("Minimum severity threshold for returned problems.")
        minSeverity: QualitySeverityThreshold = QualitySeverityThreshold.WARNING,
        @McpDescription("Maximum number of files to analyze from the matched scope.")
        maxFileCount: Int = 200,
        @McpDescription("Maximum number of problems to collect before severity filtering.")
        maxProblemCount: Int = 5000,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
        @McpDescription("Whether to continue when a single file analysis fails.")
        continueOnError: Boolean = true,
        @McpDescription("Whether to keep files that have no problems after severity filtering.")
        includeFilesWithoutMatchingProblems: Boolean = false,
    ): QualityScopeProblemsBySeverityResultDto {
        reportActivity(
            Bundle.message(
                "tool.activity.quality.scope.problems.by.severity.quick",
                scopePreset.name,
                minSeverity.name,
                maxFileCount,
                maxProblemCount,
                timeoutMillis,
            ),
        )
        val project = currentCoroutineContext().project
        val descriptor = buildPresetScopeDescriptor(
            project = project,
            preset = scopePreset,
            allowUiInteractiveScopes = false,
        )
        return quality_get_scope_problems_by_severity(
            scope = descriptor,
            minSeverity = minSeverity,
            maxFileCount = maxFileCount,
            maxProblemCount = maxProblemCount,
            timeoutMillis = timeoutMillis,
            continueOnError = continueOnError,
            allowUiInteractiveScopes = false,
            includeFilesWithoutMatchingProblems = includeFilesWithoutMatchingProblems,
        )
    }

    @McpTool
    @McpDescription(
        "Run quick file fix pipeline (optimize imports + reformat) by VFS URL." +
            AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX,
    )
    suspend fun quality_fix_file_quick(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        fileUrl: String,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
    ): QualityQuickFixFileResultDto {
        validateTimeout(timeoutMillis)
        reportActivity(Bundle.message("tool.activity.quality.file.quick.fix", fileUrl))
        val project = currentCoroutineContext().project
        val (file, _) = resolveWritablePsiFile(fileUrl)
        var optimizeImportsApplied = false
        var reformatApplied = false
        val timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
            withBackgroundProgress(
                project,
                Bundle.message("progress.title.quality.file.quick.fix", file.name),
                cancellable = true,
            ) {
                processSingleFileOperation(project, file, QualityOperationKind.OPTIMIZE_IMPORTS)
                optimizeImportsApplied = true
                processSingleFileOperation(project, file, QualityOperationKind.REFORMAT)
                reformatApplied = true
            }
            false
        } ?: true

        return QualityQuickFixFileResultDto(
            fileUrl = file.url,
            filePath = relativizePath(project.basePath, file.path),
            optimizeImportsApplied = optimizeImportsApplied,
            reformatApplied = reformatApplied,
            success = !timedOut && optimizeImportsApplied && reformatApplied,
            timedOut = timedOut,
        )
    }

    @McpTool
    @McpDescription(
        "Run quick scope fix pipeline (optimize imports + reformat) for project-content files matched by a scope descriptor." +
            AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX,
    )
    suspend fun quality_fix_scope_quick(
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Maximum number of files to process from matched scope.")
        maxFileCount: Int = 200,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 180000,
        @McpDescription("Whether to continue when a single file processing fails.")
        continueOnError: Boolean = true,
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
        allowUiInteractiveScopes: Boolean = false,
    ): QualityScopeQuickFixResultDto {
        if (maxFileCount < 1) mcpFail("maxFileCount must be >= 1.")
        validateTimeout(timeoutMillis)
        reportActivity(
            Bundle.message(
                "tool.activity.quality.scope.quick.fix.start",
                maxFileCount,
                continueOnError,
                timeoutMillis,
            ),
        )
        val project = currentCoroutineContext().project
        val resolvedScope = ScopeResolverService.getInstance(project).resolveDescriptor(
            project = project,
            descriptor = scope,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        val scopeFiles = collectProjectContentFilesInScope(project, resolvedScope.scope, maxFileCount)
        val diagnostics = mutableListOf<String>()
        if (scopeFiles.truncatedByFileLimit) {
            diagnostics += "Matched files exceed maxFileCount=$maxFileCount; quick fix was truncated."
        }
        if (resolvedScope.scopeShape == ScopeShape.GLOBAL) {
            diagnostics += "Scope traversal processes project content files; external library files are skipped."
        }

        val processedProgress = AtomicInteger(0)
        val finished = AtomicBoolean(false)
        val items = mutableListOf<QualityScopeQuickFixItemDto>()
        var processedFileCount = 0
        var successCount = 0
        var failureCount = 0
        var timedOut = false
        var probablyHasMoreMatchingFiles = scopeFiles.truncatedByFileLimit

        coroutineScope {
            val progressJob = launch {
                while (isActive && !finished.get()) {
                    delay(1000)
                    reportActivity(
                        Bundle.message(
                            "tool.activity.quality.scope.quick.fix.progress",
                            processedProgress.get(),
                            successCount,
                            failureCount,
                        ),
                    )
                }
            }
            try {
                timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
                    withBackgroundProgress(
                        project,
                        Bundle.message("progress.title.quality.scope.quick.fix", resolvedScope.displayName),
                        cancellable = true,
                    ) {
                        for (file in scopeFiles.files) {
                            ProgressManager.checkCanceled()
                            processedProgress.incrementAndGet()
                            var optimizeImportsApplied = false
                            var reformatApplied = false
                            val output = batchTry(continueOnError) {
                                processSingleFileOperation(project, file, QualityOperationKind.OPTIMIZE_IMPORTS)
                                optimizeImportsApplied = true
                                processSingleFileOperation(project, file, QualityOperationKind.REFORMAT)
                                reformatApplied = true
                            }
                            val filePath = relativizePath(project.basePath, file.path)
                            if (output.error == null) {
                                processedFileCount++
                                successCount++
                                items += QualityScopeQuickFixItemDto(
                                    fileUrl = file.url,
                                    filePath = filePath,
                                    success = true,
                                    optimizeImportsApplied = optimizeImportsApplied,
                                    reformatApplied = reformatApplied,
                                )
                            } else {
                                processedFileCount++
                                failureCount++
                                items += QualityScopeQuickFixItemDto(
                                    fileUrl = file.url,
                                    filePath = filePath,
                                    success = false,
                                    optimizeImportsApplied = optimizeImportsApplied,
                                    reformatApplied = reformatApplied,
                                    error = output.error,
                                )
                            }
                        }
                    }
                    false
                } ?: true
            } finally {
                finished.set(true)
                progressJob.cancel()
            }
        }

        if (timedOut) {
            diagnostics += "Scope quick fix timed out."
            probablyHasMoreMatchingFiles = true
        } else if (processedProgress.get() < scopeFiles.files.size) {
            probablyHasMoreMatchingFiles = true
        }
        val skippedCount = scopeFiles.files.size - processedFileCount

        return QualityScopeQuickFixResultDto(
            scopeDisplayName = resolvedScope.displayName,
            scopeShape = resolvedScope.scopeShape,
            scannedFileCount = scopeFiles.scannedFileCount,
            processedFileCount = processedFileCount,
            successCount = successCount,
            failureCount = failureCount,
            skippedCount = skippedCount,
            items = items,
            probablyHasMoreMatchingFiles = probablyHasMoreMatchingFiles,
            timedOut = timedOut,
            canceled = false,
            diagnostics = (scope.diagnostics + resolvedScope.diagnostics + diagnostics).distinct(),
        )
    }

    @McpTool
    @McpDescription(
        "First-call friendly quick scope fix shortcut (optimize imports + reformat) with preset scope." +
            AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX,
    )
    suspend fun quality_fix_scope_quick_by_preset(
        @McpDescription("Preset scope for quick fix.")
        scopePreset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
        @McpDescription("Maximum number of files to process from matched scope.")
        maxFileCount: Int = 200,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 180000,
        @McpDescription("Whether to continue when a single file processing fails.")
        continueOnError: Boolean = true,
    ): QualityScopeQuickFixResultDto {
        reportActivity(
            Bundle.message(
                "tool.activity.quality.scope.quick.fix.by.preset",
                scopePreset.name,
                maxFileCount,
                continueOnError,
                timeoutMillis,
            ),
        )
        val project = currentCoroutineContext().project
        val descriptor = buildPresetScopeDescriptor(
            project = project,
            preset = scopePreset,
            allowUiInteractiveScopes = false,
        )
        return quality_fix_scope_quick(
            scope = descriptor,
            maxFileCount = maxFileCount,
            timeoutMillis = timeoutMillis,
            continueOnError = continueOnError,
            allowUiInteractiveScopes = false,
        )
    }

    @McpTool
    @McpDescription("List available inspection profile names and mark the current profile.")
    suspend fun quality_list_inspection_profiles(
        @McpDescription("Whether to include application-level profile names in the result.")
        includeApplicationProfiles: Boolean = true,
    ): QualityInspectionProfilesResultDto {
        reportActivity(Bundle.message("tool.activity.quality.list.inspection.profiles", includeApplicationProfiles))
        val project = currentCoroutineContext().project
        return readAction {
            val projectManager = InspectionProjectProfileManager.getInstance(project)
            val projectProfiles = projectManager.profiles.map { it.name }
            val applicationProfiles = if (includeApplicationProfiles) {
                InspectionProfileManager.getInstance().profiles.map { it.name }
            } else {
                emptyList()
            }
            val names = (projectProfiles + applicationProfiles).distinct().sorted()
            val currentProfileName = projectManager.currentProfile.name
            val projectProfileSet = projectProfiles.toSet()
            QualityInspectionProfilesResultDto(
                currentProfileName = currentProfileName,
                profiles = names.map { name ->
                    QualityInspectionProfileItemDto(
                        name = name,
                        isCurrent = name == currentProfileName,
                        isProjectLevel = name in projectProfileSet,
                    )
                },
            )
        }
    }

    @McpTool
    @McpDescription(
        "Run IDE inspection-based code cleanup for a single file by VFS URL using the selected inspection profile.",
    )
    suspend fun quality_code_cleanup_file(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        fileUrl: String,
        @McpDescription("Inspection profile name to use. Defaults to current active profile.")
        inspectionProfileName: String? = null,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 300000,
    ): QualityCleanupFileResultDto {
        validateTimeout(timeoutMillis)
        reportActivity(Bundle.message("tool.activity.quality.file.cleanup", fileUrl, inspectionProfileName ?: "<current>"))
        val project = currentCoroutineContext().project
        val (file, psiFile) = resolveWritablePsiFile(fileUrl)
        val profile = resolveInspectionProfile(project, inspectionProfileName)
        val timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
            withBackgroundProgress(
                project,
                Bundle.message("progress.title.quality.file.cleanup", file.name),
                cancellable = true,
            ) {
                runCodeCleanup(project, profile, listOf(psiFile))
            }
            false
        } ?: true
        val diagnostics = if (timedOut) {
            listOf("Code cleanup timed out; background task may still continue in IDE.")
        } else {
            emptyList()
        }
        return QualityCleanupFileResultDto(
            fileUrl = file.url,
            filePath = relativizePath(project.basePath, file.path),
            inspectionProfileName = profile.name,
            success = !timedOut,
            timedOut = timedOut,
            diagnostics = diagnostics,
        )
    }

    @McpTool
    @McpDescription(
        "Run IDE inspection-based code cleanup for project-content files matched by a scope descriptor.",
    )
    suspend fun quality_code_cleanup_scope_files(
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Inspection profile name to use. Defaults to current active profile.")
        inspectionProfileName: String? = null,
        @McpDescription("Maximum number of files to process from matched scope.")
        maxFileCount: Int = 200,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 300000,
        @McpDescription("Whether to continue when a single file processing fails.")
        continueOnError: Boolean = true,
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
        allowUiInteractiveScopes: Boolean = false,
    ): QualityScopeCleanupResultDto {
        if (maxFileCount < 1) mcpFail("maxFileCount must be >= 1.")
        validateTimeout(timeoutMillis)
        reportActivity(
            Bundle.message(
                "tool.activity.quality.scope.cleanup.start",
                maxFileCount,
                continueOnError,
                timeoutMillis,
                inspectionProfileName ?: "<current>",
            ),
        )
        val project = currentCoroutineContext().project
        val profile = resolveInspectionProfile(project, inspectionProfileName)
        val resolvedScope = ScopeResolverService.getInstance(project).resolveDescriptor(
            project = project,
            descriptor = scope,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        val scopeFiles = collectProjectContentFilesInScope(project, resolvedScope.scope, maxFileCount)
        val diagnostics = mutableListOf<String>()
        if (scopeFiles.truncatedByFileLimit) {
            diagnostics += "Matched files exceed maxFileCount=$maxFileCount; cleanup was truncated."
        }
        if (resolvedScope.scopeShape == ScopeShape.GLOBAL) {
            diagnostics += "Scope traversal processes project content files; external library files are skipped."
        }

        val processedProgress = AtomicInteger(0)
        val finished = AtomicBoolean(false)
        val items = mutableListOf<QualityScopeCleanupItemDto>()
        var processedFileCount = 0
        var successCount = 0
        var failureCount = 0
        var timedOut = false
        var probablyHasMoreMatchingFiles = scopeFiles.truncatedByFileLimit

        coroutineScope {
            val progressJob = launch {
                while (isActive && !finished.get()) {
                    delay(1000)
                    reportActivity(
                        Bundle.message(
                            "tool.activity.quality.scope.cleanup.progress",
                            processedProgress.get(),
                            successCount,
                            failureCount,
                        ),
                    )
                }
            }
            try {
                timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
                    withBackgroundProgress(
                        project,
                        Bundle.message("progress.title.quality.scope.cleanup", resolvedScope.displayName),
                        cancellable = true,
                    ) {
                        for (file in scopeFiles.files) {
                            ProgressManager.checkCanceled()
                            processedProgress.incrementAndGet()
                            val output = batchTry(continueOnError) {
                                val psiFile = resolveWritablePsiFile(project, file)
                                runCodeCleanup(project, profile, listOf(psiFile))
                            }
                            val filePath = relativizePath(project.basePath, file.path)
                            if (output.error == null) {
                                processedFileCount++
                                successCount++
                                items += QualityScopeCleanupItemDto(
                                    fileUrl = file.url,
                                    filePath = filePath,
                                    success = true,
                                )
                            } else {
                                processedFileCount++
                                failureCount++
                                items += QualityScopeCleanupItemDto(
                                    fileUrl = file.url,
                                    filePath = filePath,
                                    success = false,
                                    error = output.error,
                                )
                            }
                        }
                    }
                    false
                } ?: true
            } finally {
                finished.set(true)
                progressJob.cancel()
            }
        }

        if (timedOut) {
            diagnostics += "Scope cleanup timed out; running cleanup tasks may continue in IDE."
            probablyHasMoreMatchingFiles = true
        } else if (processedProgress.get() < scopeFiles.files.size) {
            probablyHasMoreMatchingFiles = true
        }
        val skippedCount = scopeFiles.files.size - processedFileCount

        return QualityScopeCleanupResultDto(
            scopeDisplayName = resolvedScope.displayName,
            scopeShape = resolvedScope.scopeShape,
            inspectionProfileName = profile.name,
            scannedFileCount = scopeFiles.scannedFileCount,
            processedFileCount = processedFileCount,
            successCount = successCount,
            failureCount = failureCount,
            skippedCount = skippedCount,
            items = items,
            probablyHasMoreMatchingFiles = probablyHasMoreMatchingFiles,
            timedOut = timedOut,
            canceled = false,
            diagnostics = (scope.diagnostics + resolvedScope.diagnostics + diagnostics).distinct(),
        )
    }

    private suspend fun processScopeFilesWithOperation(
        scope: ScopeProgramDescriptorDto,
        operation: QualityOperationKind,
        maxFileCount: Int,
        timeoutMillis: Int,
        continueOnError: Boolean,
        allowUiInteractiveScopes: Boolean,
    ): QualityScopeOperationResultDto {
        if (maxFileCount < 1) mcpFail("maxFileCount must be >= 1.")
        validateTimeout(timeoutMillis)
        reportActivity(
            when (operation) {
                QualityOperationKind.REFORMAT -> Bundle.message(
                    "tool.activity.quality.scope.reformat.start",
                    maxFileCount,
                    continueOnError,
                    timeoutMillis,
                )

                QualityOperationKind.OPTIMIZE_IMPORTS -> Bundle.message(
                    "tool.activity.quality.scope.optimize.imports.start",
                    maxFileCount,
                    continueOnError,
                    timeoutMillis,
                )
            },
        )
        val project = currentCoroutineContext().project
        val resolvedScope = ScopeResolverService.getInstance(project).resolveDescriptor(
            project = project,
            descriptor = scope,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        val scopeFiles = collectProjectContentFilesInScope(project, resolvedScope.scope, maxFileCount)
        val diagnostics = mutableListOf<String>()
        if (scopeFiles.truncatedByFileLimit) {
            diagnostics += "Matched files exceed maxFileCount=$maxFileCount; processing was truncated."
        }
        if (resolvedScope.scopeShape == ScopeShape.GLOBAL) {
            diagnostics += "Scope traversal processes project content files; external library files are skipped."
        }

        val processedProgress = AtomicInteger(0)
        val finished = AtomicBoolean(false)
        val items = mutableListOf<QualityScopeOperationItemDto>()
        var processedFileCount = 0
        var successCount = 0
        var failureCount = 0
        var timedOut = false
        var probablyHasMoreMatchingFiles = scopeFiles.truncatedByFileLimit

        coroutineScope {
            val progressJob = launch {
                while (isActive && !finished.get()) {
                    delay(1000)
                    reportActivity(
                        when (operation) {
                            QualityOperationKind.REFORMAT -> Bundle.message(
                                "tool.activity.quality.scope.reformat.progress",
                                processedProgress.get(),
                                successCount,
                                failureCount,
                            )

                            QualityOperationKind.OPTIMIZE_IMPORTS -> Bundle.message(
                                "tool.activity.quality.scope.optimize.imports.progress",
                                processedProgress.get(),
                                successCount,
                                failureCount,
                            )
                        },
                    )
                }
            }
            try {
                timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
                    withBackgroundProgress(
                        project,
                        when (operation) {
                            QualityOperationKind.REFORMAT ->
                                Bundle.message("progress.title.quality.scope.reformat", resolvedScope.displayName)

                            QualityOperationKind.OPTIMIZE_IMPORTS ->
                                Bundle.message("progress.title.quality.scope.optimize.imports", resolvedScope.displayName)
                        },
                        cancellable = true,
                    ) {
                        for (file in scopeFiles.files) {
                            ProgressManager.checkCanceled()
                            processedProgress.incrementAndGet()
                            val output = batchTry(continueOnError) {
                                processSingleFileOperation(project, file, operation)
                            }
                            val filePath = relativizePath(project.basePath, file.path)
                            if (output.error == null) {
                                processedFileCount++
                                successCount++
                                items += QualityScopeOperationItemDto(
                                    fileUrl = file.url,
                                    filePath = filePath,
                                    success = true,
                                )
                            } else {
                                processedFileCount++
                                failureCount++
                                items += QualityScopeOperationItemDto(
                                    fileUrl = file.url,
                                    filePath = filePath,
                                    success = false,
                                    error = output.error,
                                )
                            }
                        }
                    }
                    false
                } ?: true
            } finally {
                finished.set(true)
                progressJob.cancel()
            }
        }

        if (timedOut) {
            diagnostics += "Scope operation timed out."
            probablyHasMoreMatchingFiles = true
        } else if (processedProgress.get() < scopeFiles.files.size) {
            probablyHasMoreMatchingFiles = true
        }
        val skippedCount = scopeFiles.files.size - processedFileCount

        return QualityScopeOperationResultDto(
            operation = operation,
            scopeDisplayName = resolvedScope.displayName,
            scopeShape = resolvedScope.scopeShape,
            scannedFileCount = scopeFiles.scannedFileCount,
            processedFileCount = processedFileCount,
            successCount = successCount,
            failureCount = failureCount,
            skippedCount = skippedCount,
            items = items,
            probablyHasMoreMatchingFiles = probablyHasMoreMatchingFiles,
            timedOut = timedOut,
            canceled = false,
            diagnostics = (scope.diagnostics + resolvedScope.diagnostics + diagnostics).distinct(),
        )
    }

    private suspend fun processSingleFileOperation(
        project: com.intellij.openapi.project.Project,
        file: VirtualFile,
        operation: QualityOperationKind,
    ) {
        val psiFile = resolveWritablePsiFile(project, file)
        val processor = when (operation) {
            QualityOperationKind.REFORMAT -> ReformatCodeProcessor(psiFile, false)
            QualityOperationKind.OPTIMIZE_IMPORTS -> OptimizeImportsProcessor(project, psiFile)
        }
        runCodeProcessor(processor)
    }

    private suspend fun runCodeProcessor(processor: AbstractLayoutCodeProcessor) {
        val completed = CompletableDeferred<Unit>()
        processor.setPostRunnable {
            completed.complete(Unit)
        }
        withContext(Dispatchers.EDT) {
            processor.run()
        }
        completed.await()
    }

    private suspend fun analyzeProblemsInFile(
        project: com.intellij.openapi.project.Project,
        file: VirtualFile,
        errorsOnly: Boolean,
    ): List<QualityProblemDto> {
        val minSeverity = if (errorsOnly) HighlightSeverity.ERROR else HighlightSeverity.WARNING
        val psiFile = readAction {
            if (file.isDirectory) {
                mcpFail("URL points to a directory, not a file: ${file.url}")
            }
            if (file.fileType.isBinary) {
                mcpFail("File at URL '${file.url}' is binary and cannot be analyzed.")
            }
            PsiManager.getInstance(project).findFile(file)
                ?: mcpFail("No PSI file available for URL: ${file.url}")
        }
        val document = readAction {
            FileDocumentManager.getInstance().getDocument(file)
                ?: mcpFail("No text document available for URL: ${file.url}")
        }
        val profile = resolveInspectionProfile(project, null)
        val inspectionManager = InspectionManager.getInstance(project)
        val tools = readAction { collectEnabledInspectionTools(profile, psiFile) }
        val toolSeverityByShortName = readAction {
            tools.associate { wrapper ->
                wrapper.shortName to resolveToolSeverity(profile, psiFile, wrapper)
            }
        }

        val deduplicated = linkedMapOf<String, QualityProblemDto>()

        tools.forEach { tool ->
            ProgressManager.checkCanceled()
            val descriptors = InspectionEngine.runInspectionOnFile(
                psiFile,
                tool,
                inspectionManager.createNewGlobalContext(),
            )
            descriptors.forEach { descriptor ->
                val severity = resolveProblemSeverity(
                    descriptor,
                    toolSeverityByShortName[tool.shortName] ?: HighlightSeverity.WARNING,
                )
                if (severity.myVal < minSeverity.myVal) return@forEach
                val problem = buildProblemFromDescriptor(document, descriptor, severity)
                    ?: return@forEach
                deduplicated.putIfAbsent(
                    "${problem.severity}:${problem.line}:${problem.column}:${problem.description}",
                    problem,
                )
            }
        }

        val syntaxErrors = readAction { PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java) }
        syntaxErrors.forEach { error ->
            val severity = HighlightSeverity.ERROR
            if (severity.myVal < minSeverity.myVal) return@forEach
            val problem = createQualityProblem(
                document = document,
                severity = severity.name,
                description = error.errorDescription,
                startOffset = error.textRange.startOffset,
            )
            deduplicated.putIfAbsent(
                "${problem.severity}:${problem.line}:${problem.column}:${problem.description}",
                problem,
            )
        }

        return deduplicated.values.toList()
    }

    private fun createQualityProblem(
        document: Document,
        severity: String,
        description: String?,
        startOffset: Int,
    ): QualityProblemDto {
        val boundedOffset = startOffset.coerceIn(0, document.textLength)
        val startLine = document.getLineNumber(boundedOffset)
        val lineStartOffset = document.getLineStartOffset(startLine)
        val lineEndOffset = document.getLineEndOffset(startLine)
        val lineContent = document.getText(TextRange(lineStartOffset, lineEndOffset))
        val column = boundedOffset - lineStartOffset
        return QualityProblemDto(
            severity = severity,
            description = description,
            lineContent = lineContent,
            line = startLine + 1,
            column = column + 1,
        )
    }

    private fun collectEnabledInspectionTools(
        profile: InspectionProfile,
        psiFile: PsiFile,
    ): List<InspectionToolWrapper<*, *>> {
        return profile.getInspectionTools(psiFile)
            .asSequence()
            .filter { tool ->
                ProgressManager.checkCanceled()
                val key = tool.displayKey ?: return@filter false
                profile.isToolEnabled(key, psiFile)
            }
            .toList()
    }

    private fun resolveToolSeverity(
        profile: InspectionProfile,
        psiFile: PsiFile,
        tool: InspectionToolWrapper<*, *>,
    ): HighlightSeverity {
        val level = tool.displayKey?.let { key ->
            profile.getErrorLevel(key, psiFile)
        } ?: tool.defaultLevel
        return level.severity
    }

    private fun resolveProblemSeverity(
        descriptor: ProblemDescriptor,
        configuredToolSeverity: HighlightSeverity,
    ): HighlightSeverity {
        return when (descriptor.highlightType) {
            ProblemHighlightType.ERROR,
            ProblemHighlightType.GENERIC_ERROR,
                -> HighlightSeverity.ERROR

            ProblemHighlightType.WARNING -> HighlightSeverity.WARNING
            ProblemHighlightType.WEAK_WARNING -> HighlightSeverity.WEAK_WARNING
            ProblemHighlightType.INFORMATION,
                -> HighlightSeverity.INFORMATION

            else -> configuredToolSeverity
        }
    }

    private suspend fun buildProblemFromDescriptor(
        document: Document,
        descriptor: ProblemDescriptor,
        severity: HighlightSeverity,
    ): QualityProblemDto? {
        val snapshot = readAction {
            val anchor = descriptor.startElement ?: descriptor.psiElement ?: return@readAction null
            val anchorRange = anchor.textRange ?: return@readAction null
            val rangeInElement = descriptor.textRangeInElement
            val startOffset = anchorRange.startOffset + rangeInElement.startOffset
            val description = ProblemDescriptorUtil.renderDescriptionMessage(descriptor, anchor)
            DescriptorProblemSnapshot(
                startOffset = startOffset,
                description = description,
            )
        } ?: return null
        return createQualityProblem(
            document = document,
            severity = severity.name,
            description = snapshot.description,
            startOffset = snapshot.startOffset,
        )
    }

    private suspend fun collectProjectContentFilesInScope(
        project: com.intellij.openapi.project.Project,
        resolvedScope: com.intellij.psi.search.SearchScope,
        maxFileCount: Int,
    ): ScopeFileCollection {
        val files = mutableListOf<VirtualFile>()
        var scanned = 0
        var truncated = false
        readAction {
            val fileIndex = ProjectRootManager.getInstance(project).fileIndex
            fileIndex.iterateContent(ContentIterator { file ->
                ProgressManager.checkCanceled()
                if (file.isDirectory) return@ContentIterator true
                scanned++
                if (!resolvedScope.contains(file)) return@ContentIterator true
                if (file.fileType.isBinary) return@ContentIterator true
                files += file
                if (files.size >= maxFileCount) {
                    truncated = true
                    return@ContentIterator false
                }
                true
            })
        }
        return ScopeFileCollection(
            files = files,
            scannedFileCount = scanned,
            truncatedByFileLimit = truncated,
        )
    }

    private suspend fun resolveFileByUrl(fileUrl: String): VirtualFile {
        val file = findFileByUrlWithRefresh(fileUrl) ?: mcpFail("File not found for URL: $fileUrl")
        if (file.isDirectory) {
            mcpFail("URL points to a directory, not a file: $fileUrl")
        }
        return file
    }

    private suspend fun resolveWritablePsiFile(fileUrl: String): Pair<VirtualFile, PsiFile> {
        val project = currentCoroutineContext().project
        val file = resolveFileByUrl(fileUrl)
        val psiFile = resolveWritablePsiFile(project, file)
        return file to psiFile
    }

    private suspend fun resolveWritablePsiFile(
        project: com.intellij.openapi.project.Project,
        file: VirtualFile,
    ): PsiFile {
        if (!file.isWritable) {
            mcpFail("File is not writable: ${file.url}")
        }
        val psiFile = readAction { PsiManager.getInstance(project).findFile(file) }
            ?: mcpFail("No PSI file available for URL: ${file.url}")
        if (readAction { psiFile.fileType.isBinary }) {
            mcpFail("Binary files are not supported: ${file.url}")
        }
        val document = readAction { FileDocumentManager.getInstance().getDocument(file) }
            ?: mcpFail("No text document available for URL: ${file.url}")
        if (!document.isWritable) {
            mcpFail("Document is not writable: ${file.url}")
        }
        return psiFile
    }

    private suspend fun resolveInspectionProfile(
        project: com.intellij.openapi.project.Project,
        inspectionProfileName: String?,
    ): InspectionProfile {
        return readAction {
            val projectProfileManager = InspectionProjectProfileManager.getInstance(project)
            if (inspectionProfileName.isNullOrBlank()) {
                return@readAction projectProfileManager.currentProfile
            }
            projectProfileManager.getProfile(inspectionProfileName, false)
                ?: InspectionProfileManager.getInstance().getProfile(inspectionProfileName, false)
                ?: mcpFail("Inspection profile not found: $inspectionProfileName")
        }
    }

    private suspend fun runCodeCleanup(
        project: com.intellij.openapi.project.Project,
        profile: InspectionProfile,
        elements: List<PsiFile>,
    ) {
        if (elements.isEmpty()) {
            mcpFail("No PSI files available for cleanup.")
        }
        val completed = CompletableDeferred<Unit>()
        withContext(Dispatchers.EDT) {
            GlobalInspectionContextBase.cleanupElements(
                project,
                { completed.complete(Unit) },
                Predicate { true },
                profile,
                *elements.toTypedArray(),
            )
        }
        completed.await()
    }

    private fun severityRank(threshold: QualitySeverityThreshold): Int {
        return when (threshold) {
            QualitySeverityThreshold.ERROR -> 400
            QualitySeverityThreshold.WARNING -> 300
            QualitySeverityThreshold.WEAK_WARNING -> 200
            QualitySeverityThreshold.INFO -> 100
        }
    }

    private fun severityRank(rawSeverity: String): Int {
        return when (normalizeSeverity(rawSeverity)) {
            "ERROR" -> 400
            "WARNING" -> 300
            "WEAK_WARNING" -> 200
            "INFO", "INFORMATION", "INFORMATIONAL" -> 100
            else -> 50
        }
    }

    private fun normalizeSeverity(rawSeverity: String): String {
        return rawSeverity.trim()
            .uppercase()
            .replace('-', '_')
            .replace(' ', '_')
    }

    private fun validateTimeout(timeoutMillis: Int) {
        if (timeoutMillis < 1) {
            mcpFail("timeoutMillis must be >= 1.")
        }
    }

    private fun relativizePath(projectBasePath: String?, filePath: String): String? {
        return relativizePathOrNull(projectBasePath, filePath)
    }

    private data class DescriptorProblemSnapshot(
        val startOffset: Int,
        val description: String,
    )

    private data class ScopeFileCollection(
        val files: List<VirtualFile>,
        val scannedFileCount: Int,
        val truncatedByFileLimit: Boolean,
    )

    @Serializable
    enum class QualityOperationKind {
        REFORMAT,
        OPTIMIZE_IMPORTS,
    }

    @Serializable
    enum class QualitySeverityThreshold {
        ERROR,
        WARNING,
        WEAK_WARNING,
        INFO,
    }

    @Serializable
    data class QualityProblemDto(
        val severity: String,
        val description: String?,
        val lineContent: String,
        val line: Int,
        val column: Int,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class QualityFileProblemsResultDto(
        val fileUrl: String,
        val filePath: String? = null,
        val problems: List<QualityProblemDto>,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val timedOut: Boolean = false,
    )

    @Serializable
    data class QualityFileProblemsItemDto(
        val fileUrl: String,
        val filePath: String? = null,
        val problems: List<QualityProblemDto>,
        val error: String? = null,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class QualityScopeProblemsResultDto(
        val scopeDisplayName: String,
        val scopeShape: ScopeShape,
        val errorsOnly: Boolean,
        val scannedFileCount: Int,
        val analyzedFileCount: Int,
        val filesWithProblems: Int,
        val problemCount: Int,
        val items: List<QualityFileProblemsItemDto>,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val probablyHasMoreMatchingFiles: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val probablyHasMoreProblems: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val timedOut: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val canceled: Boolean = false,
        val diagnostics: List<String> = emptyList(),
    )

    @Serializable
    data class QualitySeverityCountDto(
        val severity: String,
        val count: Int,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class QualityScopeProblemsBySeverityResultDto(
        val scopeDisplayName: String,
        val scopeShape: ScopeShape,
        val minSeverity: QualitySeverityThreshold,
        val scannedFileCount: Int,
        val analyzedFileCount: Int,
        val filesWithProblems: Int,
        val problemCount: Int,
        val severityCounts: List<QualitySeverityCountDto>,
        val items: List<QualityFileProblemsItemDto>,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val probablyHasMoreMatchingFiles: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val probablyHasMoreProblems: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val timedOut: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val canceled: Boolean = false,
        val diagnostics: List<String> = emptyList(),
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class QualityFileOperationResultDto(
        val operation: QualityOperationKind,
        val fileUrl: String,
        val filePath: String? = null,
        val success: Boolean,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val timedOut: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val error: String? = null,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class QualityQuickFixFileResultDto(
        val fileUrl: String,
        val filePath: String? = null,
        val optimizeImportsApplied: Boolean,
        val reformatApplied: Boolean,
        val success: Boolean,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val timedOut: Boolean = false,
    )

    @Serializable
    data class QualityScopeOperationItemDto(
        val fileUrl: String,
        val filePath: String? = null,
        val success: Boolean,
        val error: String? = null,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class QualityScopeOperationResultDto(
        val operation: QualityOperationKind,
        val scopeDisplayName: String,
        val scopeShape: ScopeShape,
        val scannedFileCount: Int,
        val processedFileCount: Int,
        val successCount: Int,
        val failureCount: Int,
        val skippedCount: Int,
        val items: List<QualityScopeOperationItemDto>,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val probablyHasMoreMatchingFiles: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val timedOut: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val canceled: Boolean = false,
        val diagnostics: List<String> = emptyList(),
    )

    @Serializable
    data class QualityScopeQuickFixItemDto(
        val fileUrl: String,
        val filePath: String? = null,
        val success: Boolean,
        val optimizeImportsApplied: Boolean,
        val reformatApplied: Boolean,
        val error: String? = null,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class QualityScopeQuickFixResultDto(
        val scopeDisplayName: String,
        val scopeShape: ScopeShape,
        val scannedFileCount: Int,
        val processedFileCount: Int,
        val successCount: Int,
        val failureCount: Int,
        val skippedCount: Int,
        val items: List<QualityScopeQuickFixItemDto>,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val probablyHasMoreMatchingFiles: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val timedOut: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val canceled: Boolean = false,
        val diagnostics: List<String> = emptyList(),
    )

    @Serializable
    data class QualityInspectionProfileItemDto(
        val name: String,
        val isCurrent: Boolean,
        val isProjectLevel: Boolean,
    )

    @Serializable
    data class QualityInspectionProfilesResultDto(
        val currentProfileName: String,
        val profiles: List<QualityInspectionProfileItemDto>,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class QualityCleanupFileResultDto(
        val fileUrl: String,
        val filePath: String? = null,
        val inspectionProfileName: String,
        val success: Boolean,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val timedOut: Boolean = false,
        val diagnostics: List<String> = emptyList(),
    )

    @Serializable
    data class QualityScopeCleanupItemDto(
        val fileUrl: String,
        val filePath: String? = null,
        val success: Boolean,
        val error: String? = null,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class QualityScopeCleanupResultDto(
        val scopeDisplayName: String,
        val scopeShape: ScopeShape,
        val inspectionProfileName: String,
        val scannedFileCount: Int,
        val processedFileCount: Int,
        val successCount: Int,
        val failureCount: Int,
        val skippedCount: Int,
        val items: List<QualityScopeCleanupItemDto>,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val probablyHasMoreMatchingFiles: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val timedOut: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val canceled: Boolean = false,
        val diagnostics: List<String> = emptyList(),
    )
}
