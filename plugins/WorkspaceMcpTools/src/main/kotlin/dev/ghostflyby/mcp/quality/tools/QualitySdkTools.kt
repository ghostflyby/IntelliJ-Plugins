/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.quality.tools

import com.intellij.codeInsight.actions.AbstractLayoutCodeProcessor
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInspection.*
import com.intellij.codeInspection.ex.GlobalInspectionContextBase
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import dev.ghostflyby.mcp.common.WorkspaceResourceException
import dev.ghostflyby.mcp.common.batchTry
import dev.ghostflyby.mcp.common.relativizePathOrNull
import dev.ghostflyby.mcp.common.reportActivity
import dev.ghostflyby.mcp.filecontent.getOrCreateDocument
import dev.ghostflyby.mcp.scope.*
import dev.ghostflyby.mcp.sdk.project
import dev.ghostflyby.mcp.server.route.McpCallContext
import dev.ghostflyby.mcp.server.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.*
import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Predicate
import kotlin.time.Duration.Companion.milliseconds

// ---------------------------------------------------------------------------
// Result DTOs
// ---------------------------------------------------------------------------

@Schema
@Serializable
internal enum class QualityOperationKind {
    REFORMAT,
    OPTIMIZE_IMPORTS,
}

@Schema
@Serializable
internal enum class QualitySeverityThreshold {
    ERROR,
    WARNING,
    WEAK_WARNING,
    INFO,
}

@Schema
@Serializable
internal data class QualityProblemDto(
    val severity: String,
    val description: String?,
    val lineContent: String,
    val line: Int,
    val column: Int,
)

@OptIn(ExperimentalSerializationApi::class)
@Schema
@Serializable
internal data class QualityFileProblemsResultDto(
    @Description("VFS URL of the file to clean up.")
    val fileUrl: String,
    val filePath: String? = null,
    val problems: List<QualityProblemDto>,
    val timedOut: Boolean = false,
)

@Schema
@Serializable
internal data class QualityFileProblemsItemDto(
    @Description("VFS URL of the file to clean up.")
    val fileUrl: String,
    val filePath: String? = null,
    val problems: List<QualityProblemDto>,
    val error: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Schema
@Serializable
internal data class QualityScopeProblemsResultDto(
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    @Description("Whether to include only errors or include both errors and warnings.")
    val errorsOnly: Boolean,
    val scannedFileCount: Int,
    val analyzedFileCount: Int,
    val filesWithProblems: Int,
    val problemCount: Int,
    val items: List<QualityFileProblemsItemDto>,
    val probablyHasMoreMatchingFiles: Boolean = false,
    val probablyHasMoreProblems: Boolean = false,
    val timedOut: Boolean = false,
    val canceled: Boolean = false,
    val diagnostics: List<String> = emptyList(),
)

@Schema
@Serializable
internal data class QualitySeverityCountDto(
    val severity: String,
    val count: Int,
)

@OptIn(ExperimentalSerializationApi::class)
@Schema
@Serializable
internal data class QualityScopeProblemsBySeverityResultDto(
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    @Description("Minimum severity threshold.")
    val minSeverity: QualitySeverityThreshold,
    val scannedFileCount: Int,
    val analyzedFileCount: Int,
    val filesWithProblems: Int,
    val problemCount: Int,
    val severityCounts: List<QualitySeverityCountDto>,
    val items: List<QualityFileProblemsItemDto>,
    val probablyHasMoreMatchingFiles: Boolean = false,
    val probablyHasMoreProblems: Boolean = false,
    val timedOut: Boolean = false,
    val canceled: Boolean = false,
    val diagnostics: List<String> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Schema
@Serializable
internal data class QualityFileOperationResultDto(
    val operation: QualityOperationKind,
    @Description("VFS URL of the file to clean up.")
    val fileUrl: String,
    val filePath: String? = null,
    val success: Boolean,
    val timedOut: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Schema
@Serializable
internal data class QualityQuickFixFileResultDto(
    @Description("VFS URL of the file to clean up.")
    val fileUrl: String,
    val filePath: String? = null,
    val optimizeImportsApplied: Boolean,
    val reformatApplied: Boolean,
    val success: Boolean,
    val timedOut: Boolean = false,
)

@Schema
@Serializable
internal data class QualityScopeOperationItemDto(
    @Description("VFS URL of the file to clean up.")
    val fileUrl: String,
    val filePath: String? = null,
    val success: Boolean,
    val error: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Schema
@Serializable
internal data class QualityScopeOperationResultDto(
    val operation: QualityOperationKind,
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    val scannedFileCount: Int,
    val processedFileCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val skippedCount: Int,
    val items: List<QualityScopeOperationItemDto>,
    val probablyHasMoreMatchingFiles: Boolean = false,
    val timedOut: Boolean = false,
    val canceled: Boolean = false,
    val diagnostics: List<String> = emptyList(),
)

@Schema
@Serializable
internal data class QualityScopeQuickFixItemDto(
    @Description("VFS URL of the file to clean up.")
    val fileUrl: String,
    val filePath: String? = null,
    val success: Boolean,
    val optimizeImportsApplied: Boolean,
    val reformatApplied: Boolean,
    val error: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Schema
@Serializable
internal data class QualityScopeQuickFixResultDto(
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    val scannedFileCount: Int,
    val processedFileCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val skippedCount: Int,
    val items: List<QualityScopeQuickFixItemDto>,
    val probablyHasMoreMatchingFiles: Boolean = false,
    val timedOut: Boolean = false,
    val canceled: Boolean = false,
    val diagnostics: List<String> = emptyList(),
)

@Schema
@Serializable
internal data class QualityInspectionProfileItemDto(
    val name: String,
    val isCurrent: Boolean,
    val isProjectLevel: Boolean,
)

@Schema
@Serializable
internal data class QualityInspectionProfilesResultDto(
    val currentProfileName: String,
    val profiles: List<QualityInspectionProfileItemDto>,
)

@OptIn(ExperimentalSerializationApi::class)
@Schema
@Serializable
internal data class QualityCleanupFileResultDto(
    @Description("VFS URL of the file to clean up.")
    val fileUrl: String,
    val filePath: String? = null,
    @Description("Inspection profile name. Defaults to current active profile.")
    val inspectionProfileName: String,
    val success: Boolean,
    val timedOut: Boolean = false,
    val diagnostics: List<String> = emptyList(),
)

@Schema
@Serializable
internal data class QualityScopeCleanupItemDto(
    @Description("VFS URL of the file to clean up.")
    val fileUrl: String,
    val filePath: String? = null,
    val success: Boolean,
    val error: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Schema
@Serializable
internal data class QualityScopeCleanupResultDto(
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    @Description("Inspection profile name. Defaults to current active profile.")
    val inspectionProfileName: String,
    val scannedFileCount: Int,
    val processedFileCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val skippedCount: Int,
    val items: List<QualityScopeCleanupItemDto>,
    val probablyHasMoreMatchingFiles: Boolean = false,
    val timedOut: Boolean = false,
    val canceled: Boolean = false,
    val diagnostics: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

private data class DescriptorProblemSnapshot(
    val startOffset: Int,
    val description: String?,
    val severity: String,
)

private data class ScopeFileCollection(
    val files: List<VirtualFile>,
    val scannedFileCount: Int,
    val truncatedByFileLimit: Boolean,
)

private fun validateTimeout(timeoutMillis: Int) {
    if (timeoutMillis < 1) throw WorkspaceResourceException("timeoutMillis must be >= 1.")
}

private fun relativizePath(projectBasePath: String?, filePath: String): String? =
    relativizePathOrNull(projectBasePath, filePath)

private suspend fun resolveFileByUrl(fileUrl: String): VirtualFile {
    val file = findFileByUrlWithRefresh(fileUrl)
        ?: throw WorkspaceResourceException("File not found for URL: $fileUrl")
    if (file.isDirectory) {
        throw WorkspaceResourceException("URL points to a directory, not a file: $fileUrl")
    }
    return file
}

private suspend fun resolveWritablePsiFile(
    project: Project,
    file: VirtualFile,
): PsiFile {
    if (!file.isWritable) {
        throw WorkspaceResourceException("File is not writable: ${file.url}")
    }
    val psiFile = readAction { PsiManager.getInstance(project).findFile(file) }
        ?: throw WorkspaceResourceException("No PSI file available for URL: ${file.url}")
    if (readAction { psiFile.fileType.isBinary }) {
        throw WorkspaceResourceException("Binary files are not supported: ${file.url}")
    }
    val document = readAction { getOrCreateDocument(file) }
        ?: throw WorkspaceResourceException("No text document available for URL: ${file.url}")
    if (!document.isWritable) {
        throw WorkspaceResourceException("Document is not writable: ${file.url}")
    }
    return psiFile
}

private suspend fun resolveInspectionProfile(
    project: Project,
    inspectionProfileName: String?,
): InspectionProfile {
    return readAction {
        val projectProfileManager = InspectionProjectProfileManager.getInstance(project)
        if (inspectionProfileName.isNullOrBlank()) {
            return@readAction projectProfileManager.currentProfile
        }
        projectProfileManager.getProfile(inspectionProfileName, false)
            ?: InspectionProfileManager.getInstance().getProfile(inspectionProfileName, false)
            ?: throw WorkspaceResourceException("Inspection profile not found: $inspectionProfileName")
    }
}

private suspend fun runCodeProcessor(processor: AbstractLayoutCodeProcessor) {
    withContext(Dispatchers.EDT) {
        processor.run()
    }
}

private suspend fun processSingleFileOperation(
    project: Project,
    file: VirtualFile,
    operation: QualityOperationKind,
) {
    val psiFile = resolveWritablePsiFile(project, file)
    when (operation) {
        QualityOperationKind.REFORMAT -> runCodeProcessor(ReformatCodeProcessor(psiFile, false))
        QualityOperationKind.OPTIMIZE_IMPORTS -> runCodeProcessor(OptimizeImportsProcessor(project, psiFile))
    }
}

private suspend fun runCodeCleanup(
    project: Project,
    profile: InspectionProfile,
    elements: List<PsiFile>,
) {
    if (elements.isEmpty()) {
        throw WorkspaceResourceException("No PSI files available for cleanup.")
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

private suspend fun collectProjectContentFilesInScope(
    project: Project,
    resolvedScope: com.intellij.psi.search.SearchScope,
    maxFileCount: Int,
): ScopeFileCollection {
    val files = mutableListOf<VirtualFile>()
    var scanned = 0
    var truncated = false
    readAction {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        fileIndex.iterateContent(
            ContentIterator { file ->
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
            },
        )
    }
    return ScopeFileCollection(
        files = files,
        scannedFileCount = scanned,
        truncatedByFileLimit = truncated,
    )
}

private suspend fun findFileByUrlWithRefresh(fileUrl: String): VirtualFile? {
    return readAction {
        com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(fileUrl)
    }
}

private suspend fun analyzeProblemsInFile(
    project: Project,
    file: VirtualFile,
    errorsOnly: Boolean,
): List<QualityProblemDto> {
    val psiFile = readAction { PsiManager.getInstance(project).findFile(file) }
        ?: return emptyList()
    if (readAction { psiFile.fileType.isBinary }) return emptyList()
    val document = readAction { getOrCreateDocument(file) } ?: return emptyList()

    val minSeverity = if (errorsOnly) HighlightSeverity.ERROR else HighlightSeverity.WEAK_WARNING
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
        checkCanceled()
        val currentJob = currentCoroutineContext()[Job]
        val problemSnapshots = readAction {
            val daemonIndicator = DaemonProgressIndicator().also { it.start() }
            val cancelHandle = currentJob?.invokeOnCompletion { cause ->
                if (cause != null) daemonIndicator.cancel()
            }
            try {
                val descriptors = ProgressManager.getInstance().runProcess(
                    Computable {
                        InspectionEngine.runInspectionOnFile(
                            psiFile,
                            tool,
                            inspectionManager.createNewGlobalContext(),
                        )
                    },
                    daemonIndicator,
                )
                descriptors.mapNotNull { descriptor ->
                    ProgressManager.checkCanceled()
                    buildProblemSnapshotFromDescriptor(
                        descriptor = descriptor,
                        configuredToolSeverity = toolSeverityByShortName[tool.shortName]
                            ?: HighlightSeverity.WARNING,
                        minSeverity = minSeverity,
                    )
                }
            } finally {
                cancelHandle?.dispose()
                daemonIndicator.stop()
            }
        }
        problemSnapshots.forEach { snapshot ->
            val problem = createQualityProblem(
                document = document,
                severity = snapshot.severity,
                description = snapshot.description,
                startOffset = snapshot.startOffset,
            )
            deduplicated.putIfAbsent(
                "${problem.severity}:${problem.line}:${problem.column}:${problem.description}",
                problem,
            )
        }
    }

    val syntaxErrorSnapshots = readAction {
        PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
            .map { error ->
                DescriptorProblemSnapshot(
                    startOffset = error.textRange.startOffset,
                    description = error.errorDescription,
                    severity = HighlightSeverity.ERROR.name,
                )
            }
    }
    syntaxErrorSnapshots.forEach { snapshot ->
        val severity = HighlightSeverity.ERROR
        if (severity.myVal < minSeverity.myVal) return@forEach
        val problem = createQualityProblem(
            document = document,
            severity = snapshot.severity,
            description = snapshot.description,
            startOffset = snapshot.startOffset,
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
        ProblemHighlightType.INFORMATION -> HighlightSeverity.INFORMATION
        else -> configuredToolSeverity
    }
}

private fun buildProblemSnapshotFromDescriptor(
    descriptor: ProblemDescriptor,
    configuredToolSeverity: HighlightSeverity,
    minSeverity: HighlightSeverity,
): DescriptorProblemSnapshot? {
    val severity = resolveProblemSeverity(descriptor, configuredToolSeverity)
    if (severity.myVal < minSeverity.myVal) return null
    val anchor = descriptor.startElement ?: descriptor.psiElement ?: return null
    val anchorRange = anchor.textRange ?: return null
    val rangeInElement = descriptor.textRangeInElement
    val startOffset = anchorRange.startOffset + rangeInElement.startOffset
    val description = ProblemDescriptorUtil.renderDescriptionMessage(descriptor, anchor)
    return DescriptorProblemSnapshot(
        startOffset = startOffset,
        description = description,
        severity = severity.name,
    )
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
// ---------------------------------------------------------------------------
// Scope iteration helpers
// ---------------------------------------------------------------------------

private suspend fun resolveScopeAndCollectFiles(
    project: Project,
    scope: ScopeProgramDescriptorDto,
    maxFileCount: Int,
    allowUiInteractiveScopes: Boolean,
): Pair<ScopeResolveResult, ScopeFileCollection> {
    val resolvedScope = ScopeResolverService.getInstance(project).resolveDescriptor(
        project = project,
        descriptor = scope,
        allowUiInteractiveScopes = allowUiInteractiveScopes,
    )
    val scopeFiles = collectProjectContentFilesInScope(project, resolvedScope.scope, maxFileCount)
    return resolvedScope.toSimple() to scopeFiles
}

private data class ScopeResolveResult(
    val scope: com.intellij.psi.search.SearchScope,
    val displayName: String,
    val scopeShape: ScopeShape,
    val diagnostics: List<String>,
)

private fun ScopeResolverService.ResolvedScope.toSimple(): ScopeResolveResult =
    ScopeResolveResult(
        scope = scope,
        displayName = displayName,
        scopeShape = scopeShape,
        diagnostics = diagnostics,
    )

private fun ScopeProgramDescriptorDto.combinedDiagnostics(
    resolvedScope: ScopeResolveResult,
    additional: List<String>,
): List<String> {
    return (diagnostics + resolvedScope.diagnostics + additional).distinct()
}

private fun encodeJson(obj: Any): String {
    @Suppress("UNCHECKED_CAST")
    return when (obj) {
        is QualityFileProblemsResultDto -> toolArgsJson.encodeToString(obj)
        is QualityScopeProblemsResultDto -> toolArgsJson.encodeToString(obj)
        is QualityScopeProblemsBySeverityResultDto -> toolArgsJson.encodeToString(obj)
        is QualityFileOperationResultDto -> toolArgsJson.encodeToString(obj)
        is QualityScopeOperationResultDto -> toolArgsJson.encodeToString(obj)
        is QualityQuickFixFileResultDto -> toolArgsJson.encodeToString(obj)
        is QualityScopeQuickFixResultDto -> toolArgsJson.encodeToString(obj)
        is QualityInspectionProfilesResultDto -> toolArgsJson.encodeToString(obj)
        is QualityCleanupFileResultDto -> toolArgsJson.encodeToString(obj)
        is QualityScopeCleanupResultDto -> toolArgsJson.encodeToString(obj)
        else -> obj.toString()
    }
}

@Suppress("unused")
internal class QualityTools {
    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityGetFileProblems(
        @Description("VFS URL of the file to clean up.")
        fileUrl: String,
        @Description("Whether to include only errors or include both errors and warnings.")
        errorsOnly: Boolean = true,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
    ): CallToolResult {
        val project = call.project()
        validateTimeout(timeoutMillis)
        val file = resolveFileByUrl(fileUrl)
        val filePath = relativizePath(project.basePath, file.path)
        var timedOut = false
        val problems = withTimeoutOrNull(timeoutMillis.milliseconds) {
            withBackgroundProgress(project, "Analyzing problems in ${file.name}", cancellable = true) {
                analyzeProblemsInFile(project, file, errorsOnly)
            }
        } ?: run { timedOut = true; emptyList() }
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = encodeJson(
                        QualityFileProblemsResultDto(
                            fileUrl = file.url,
                            filePath = filePath,
                            problems = problems,
                            timedOut = timedOut,
                        ),
                    ),
                ),
            ),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityGetScopeProblems(
        scope: ScopeProgramDescriptorDto,
        @Description("Whether to include only errors or include both errors and warnings.")
        errorsOnly: Boolean = true,
        @Description("Maximum number of files to process.")
        maxFileCount: Int = 200,
        @Description("Maximum number of problems to collect.")
        maxProblemCount: Int = 5000,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
        @Description("Continue on single file failure.")
        continueOnError: Boolean = true,
        @Description("Allow UI-interactive scopes.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        if (maxFileCount < 1) return errorResult("maxFileCount must be >= 1.")
        if (maxProblemCount < 1) return errorResult("maxProblemCount must be >= 1.")
        validateTimeout(timeoutMillis)
        val project = call.project()
        val (resolvedScope, scopeFiles) = resolveScopeAndCollectFiles(
            project,
            scope,
            maxFileCount,
            allowUiInteractiveScopes,
        )
        val diagnostics = mutableListOf<String>()
        if (scopeFiles.truncatedByFileLimit) diagnostics += "Matched files exceed maxFileCount=$maxFileCount; analysis was truncated."
        if (resolvedScope.scopeShape == ScopeShape.GLOBAL) diagnostics += "Scope traversal analyzes project content files; external library files are skipped."
        val base = qualityGetScopeProblemsInner(
            project,
            scope,
            errorsOnly,
            maxFileCount,
            maxProblemCount,
            timeoutMillis,
            continueOnError,
            allowUiInteractiveScopes,
        )
        return CallToolResult(content = listOf(TextContent(text = encodeJson(base))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityGetScopeProblemsQuick(
        @Description("Preset scope identifier.")
        scopePreset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
        @Description("Whether to include only errors or include both errors and warnings.")
        errorsOnly: Boolean = true,
        @Description("Maximum number of files to process.")
        maxFileCount: Int = 200,
        @Description("Maximum number of problems to collect.")
        maxProblemCount: Int = 5000,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
        @Description("Continue on single file failure.")
        continueOnError: Boolean = true,
    ): CallToolResult {
        val project = call.project()
        val descriptor = buildPresetScopeDescriptor(project, scopePreset, allowUiInteractiveScopes = false)
        return qualityGetScopeProblems(
            descriptor,
            errorsOnly,
            maxFileCount,
            maxProblemCount,
            timeoutMillis,
            continueOnError,
            allowUiInteractiveScopes = false,
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityReformatFile(
        @Description("VFS URL of the file to clean up.")
        fileUrl: String,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
    ): CallToolResult {
        val project = call.project()
        validateTimeout(timeoutMillis)
        val file = resolveFileByUrl(fileUrl)
        val psiFile = resolveWritablePsiFile(project, file)
        val timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
            runCodeProcessor(ReformatCodeProcessor(psiFile, false))
            false
        } ?: true
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = encodeJson(
                        QualityFileOperationResultDto(
                            operation = QualityOperationKind.REFORMAT,
                            fileUrl = file.url,
                            filePath = relativizePath(project.basePath, file.path),
                            success = !timedOut,
                            timedOut = timedOut,
                        ),
                    ),
                ),
            ),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityOptimizeImportsFile(
        @Description("VFS URL of the file to clean up.")
        fileUrl: String,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
    ): CallToolResult {
        val project = call.project()
        validateTimeout(timeoutMillis)
        val file = resolveFileByUrl(fileUrl)
        val psiFile = resolveWritablePsiFile(project, file)
        val timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
            runCodeProcessor(OptimizeImportsProcessor(project, psiFile))
            false
        } ?: true
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = encodeJson(
                        QualityFileOperationResultDto(
                            operation = QualityOperationKind.OPTIMIZE_IMPORTS,
                            fileUrl = file.url,
                            filePath = relativizePath(project.basePath, file.path),
                            success = !timedOut,
                            timedOut = timedOut,
                        ),
                    ),
                ),
            ),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityReformatScopeFiles(
        scope: ScopeProgramDescriptorDto,
        @Description("Maximum number of files to process.")
        maxFileCount: Int = 200,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 180000,
        @Description("Continue on single file failure.")
        continueOnError: Boolean = true,
        @Description("Allow UI-interactive scopes.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        return processScopeFilesWithOp(
            project,
            scope,
            QualityOperationKind.REFORMAT,
            maxFileCount,
            timeoutMillis,
            continueOnError,
            allowUiInteractiveScopes,
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityOptimizeImportsScopeFiles(
        scope: ScopeProgramDescriptorDto,
        @Description("Maximum number of files to process.")
        maxFileCount: Int = 200,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 180000,
        @Description("Continue on single file failure.")
        continueOnError: Boolean = true,
        @Description("Allow UI-interactive scopes.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        return processScopeFilesWithOp(
            project,
            scope,
            QualityOperationKind.OPTIMIZE_IMPORTS,
            maxFileCount,
            timeoutMillis,
            continueOnError,
            allowUiInteractiveScopes,
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityGetScopeProblemsBySeverity(
        scope: ScopeProgramDescriptorDto,
        @Description("Minimum severity threshold.")
        minSeverity: QualitySeverityThreshold = QualitySeverityThreshold.WARNING,
        @Description("Maximum number of files to process.")
        maxFileCount: Int = 200,
        @Description("Maximum number of problems to collect.")
        maxProblemCount: Int = 5000,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
        @Description("Continue on single file failure.")
        continueOnError: Boolean = true,
        @Description("Allow UI-interactive scopes.")
        allowUiInteractiveScopes: Boolean = false,
        @Description("Keep files with no problems after filtering.")
        includeFilesWithoutMatchingProblems: Boolean = false,
    ): CallToolResult {
        if (maxFileCount < 1) return errorResult("maxFileCount must be >= 1.")
        if (maxProblemCount < 1) return errorResult("maxProblemCount must be >= 1.")
        validateTimeout(timeoutMillis)
        val project = call.project()
        val base = qualityGetScopeProblemsInner(
            project,
            scope,
            minSeverity == QualitySeverityThreshold.ERROR,
            maxFileCount,
            maxProblemCount,
            timeoutMillis,
            continueOnError,
            allowUiInteractiveScopes,
        )
        val thresholdRank = severityRank(minSeverity)
        val severityCounts = LinkedHashMap<String, Int>()
        val items = mutableListOf<QualityFileProblemsItemDto>()
        var filesWithProblems = 0
        var problemCount = 0
        base.items.forEach { item ->
            val filtered = item.problems.filter { p -> severityRank(p.severity) >= thresholdRank }
            val sevMap = linkedMapOf<String, Int>()
            filtered.forEach { p -> val sev = normalizeSeverity(p.severity); sevMap[sev] = (sevMap[sev] ?: 0) + 1 }
            sevMap.forEach { (sev, cnt) -> severityCounts[sev] = (severityCounts[sev] ?: 0) + cnt }
            if (filtered.isNotEmpty() || includeFilesWithoutMatchingProblems) {
                if (filtered.isNotEmpty()) filesWithProblems++
                problemCount += filtered.size
                items += item.copy(problems = filtered)
            }
        }
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = encodeJson(
                        QualityScopeProblemsBySeverityResultDto(
                            scopeDisplayName = base.scopeDisplayName,
                            scopeShape = base.scopeShape,
                            minSeverity = minSeverity,
                            scannedFileCount = base.scannedFileCount,
                            analyzedFileCount = base.analyzedFileCount,
                            filesWithProblems = filesWithProblems,
                            problemCount = problemCount,
                            severityCounts = severityCounts.map { (s, c) -> QualitySeverityCountDto(s, c) },
                            items = items,
                            probablyHasMoreMatchingFiles = base.probablyHasMoreMatchingFiles,
                            probablyHasMoreProblems = base.probablyHasMoreProblems,
                            timedOut = base.timedOut,
                            canceled = base.canceled,
                            diagnostics = base.diagnostics,
                        ),
                    ),
                ),
            ),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityGetScopeProblemsBySeverityQuick(
        @Description("Preset scope identifier.")
        scopePreset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
        @Description("Minimum severity threshold.")
        minSeverity: QualitySeverityThreshold = QualitySeverityThreshold.WARNING,
        @Description("Maximum number of files to process.")
        maxFileCount: Int = 200,
        @Description("Maximum number of problems to collect.")
        maxProblemCount: Int = 5000,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
        @Description("Continue on single file failure.")
        continueOnError: Boolean = true,
        @Description("Keep files with no problems after filtering.")
        includeFilesWithoutMatchingProblems: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        val descriptor = buildPresetScopeDescriptor(project, scopePreset, allowUiInteractiveScopes = false)
        return qualityGetScopeProblemsBySeverity(
            descriptor,
            minSeverity,
            maxFileCount,
            maxProblemCount,
            timeoutMillis,
            continueOnError,
            allowUiInteractiveScopes = false,
            includeFilesWithoutMatchingProblems,
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityFixFileQuick(
        @Description("VFS URL of the file to clean up.")
        fileUrl: String,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 120000,
    ): CallToolResult {
        val project = call.project()
        validateTimeout(timeoutMillis)
        val file = resolveFileByUrl(fileUrl)
        var optimizeImportsApplied = false
        var reformatApplied = false
        val timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
            withBackgroundProgress(project, "Quick fix: ${file.name}", cancellable = true) {
                processSingleFileOperation(
                    project,
                    file,
                    QualityOperationKind.OPTIMIZE_IMPORTS,
                ); optimizeImportsApplied = true
                processSingleFileOperation(project, file, QualityOperationKind.REFORMAT); reformatApplied = true
            }
            false
        } ?: true
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = encodeJson(
                        QualityQuickFixFileResultDto(
                            fileUrl = file.url, filePath = relativizePath(project.basePath, file.path),
                            optimizeImportsApplied = optimizeImportsApplied, reformatApplied = reformatApplied,
                            success = !timedOut && optimizeImportsApplied && reformatApplied, timedOut = timedOut,
                        ),
                    ),
                ),
            ),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityFixScopeQuick(
        scope: ScopeProgramDescriptorDto,
        @Description("Maximum number of files to process.")
        maxFileCount: Int = 200,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 180000,
        @Description("Continue on single file failure.")
        continueOnError: Boolean = true,
        @Description("Allow UI-interactive scopes.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        if (maxFileCount < 1) return errorResult("maxFileCount must be >= 1.")
        validateTimeout(timeoutMillis)
        val project = call.project()
        val (resolvedScope, scopeFiles) = resolveScopeAndCollectFiles(
            project,
            scope,
            maxFileCount,
            allowUiInteractiveScopes,
        )
        val diagnostics = mutableListOf<String>()
        if (scopeFiles.truncatedByFileLimit) diagnostics += "Matched files exceed maxFileCount=$maxFileCount; quick fix was truncated."
        if (resolvedScope.scopeShape == ScopeShape.GLOBAL) diagnostics += "Scope traversal processes project content files; external library files are skipped."
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
                    delay(1000.milliseconds); reportActivity("Scope quick fix: processed $processedProgress, success $successCount, failure $failureCount")
                }
            }
            try {
                timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
                    withBackgroundProgress(
                        project,
                        "Scope quick fix: ${resolvedScope.displayName}",
                        cancellable = true,
                    ) {
                        for (file in scopeFiles.files) {
                            checkCanceled(); processedProgress.incrementAndGet()
                            var optimizeImportsApplied = false
                            var reformatApplied = false
                            val output = batchTry(continueOnError) {
                                processSingleFileOperation(
                                    project,
                                    file,
                                    QualityOperationKind.OPTIMIZE_IMPORTS,
                                ); optimizeImportsApplied = true
                                processSingleFileOperation(
                                    project,
                                    file,
                                    QualityOperationKind.REFORMAT,
                                ); reformatApplied = true
                            }
                            val filePath = relativizePath(project.basePath, file.path)
                            if (output.error == null) {
                                processedFileCount++; successCount++
                                items += QualityScopeQuickFixItemDto(
                                    fileUrl = file.url,
                                    filePath = filePath,
                                    success = true,
                                    optimizeImportsApplied = optimizeImportsApplied,
                                    reformatApplied = reformatApplied,
                                )
                            } else {
                                processedFileCount++; failureCount++
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
                finished.set(true); progressJob.cancel()
            }
        }
        if (timedOut) {
            diagnostics += "Scope quick fix timed out."; probablyHasMoreMatchingFiles = true
        } else if (processedProgress.get() < scopeFiles.files.size) probablyHasMoreMatchingFiles = true
        val skippedCount = scopeFiles.files.size - processedFileCount
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = encodeJson(
                        QualityScopeQuickFixResultDto(
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
                            diagnostics = scope.combinedDiagnostics(resolvedScope, diagnostics),
                        ),
                    ),
                ),
            ),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityFixScopeQuickByPreset(
        @Description("Preset scope identifier.")
        scopePreset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
        @Description("Maximum number of files to process.")
        maxFileCount: Int = 200,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 180000,
        @Description("Continue on single file failure.")
        continueOnError: Boolean = true,
    ): CallToolResult {
        val project = call.project()
        val descriptor = buildPresetScopeDescriptor(project, scopePreset, allowUiInteractiveScopes = false)
        return qualityFixScopeQuick(
            descriptor,
            maxFileCount,
            timeoutMillis,
            continueOnError,
            allowUiInteractiveScopes = false,
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityListInspectionProfiles(
        @Description("Whether to include application-level profile names.")
        includeApplicationProfiles: Boolean = true,
    ): CallToolResult {
        val project = call.project()
        val result = readAction {
            val projectManager = InspectionProjectProfileManager.getInstance(project)
            val projectProfiles = projectManager.profiles.map { it.name }
            val applicationProfiles =
                if (includeApplicationProfiles) InspectionProfileManager.getInstance().profiles.map { it.name } else emptyList()
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
        return CallToolResult(content = listOf(TextContent(text = encodeJson(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityCodeCleanupFile(
        @Description("VFS URL of the file to clean up.")
        fileUrl: String,
        @Description("Inspection profile name. Defaults to current active profile.")
        inspectionProfileName: String? = null,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 300000,
    ): CallToolResult {
        val project = call.project()
        validateTimeout(timeoutMillis)
        val file = resolveFileByUrl(fileUrl)
        val psiFile = resolveWritablePsiFile(project, file)
        val profile = resolveInspectionProfile(project, inspectionProfileName)
        val timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
            withBackgroundProgress(project, "Cleanup: ${file.name}", cancellable = true) {
                runCodeCleanup(
                    project,
                    profile,
                    listOf(psiFile),
                )
            }
            false
        } ?: true
        val diags =
            if (timedOut) listOf("Code cleanup timed out; background task may still continue in IDE.") else emptyList()
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = encodeJson(
                        QualityCleanupFileResultDto(
                            fileUrl = file.url,
                            filePath = relativizePath(project.basePath, file.path),
                            inspectionProfileName = profile.name,
                            success = !timedOut,
                            timedOut = timedOut,
                            diagnostics = diags,
                        ),
                    ),
                ),
            ),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.qualityCodeCleanupScopeFiles(
        scope: ScopeProgramDescriptorDto,
        @Description("Inspection profile name. Defaults to current active profile.")
        inspectionProfileName: String? = null,
        @Description("Maximum number of files to process.")
        maxFileCount: Int = 200,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 300000,
        @Description("Continue on single file failure.")
        continueOnError: Boolean = true,
        @Description("Allow UI-interactive scopes.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        if (maxFileCount < 1) return errorResult("maxFileCount must be >= 1.")
        validateTimeout(timeoutMillis)
        val project = call.project()
        val profile = resolveInspectionProfile(project, inspectionProfileName)
        val (resolvedScope, scopeFiles) = resolveScopeAndCollectFiles(
            project,
            scope,
            maxFileCount,
            allowUiInteractiveScopes,
        )
        val diagnostics = mutableListOf<String>()
        if (scopeFiles.truncatedByFileLimit) diagnostics += "Matched files exceed maxFileCount=$maxFileCount; cleanup was truncated."
        if (resolvedScope.scopeShape == ScopeShape.GLOBAL) diagnostics += "Scope traversal processes project content files; external library files are skipped."
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
                    delay(1000.milliseconds); reportActivity("Scope cleanup: processed $processedProgress, success $successCount, failure $failureCount")
                }
            }
            try {
                timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
                    withBackgroundProgress(project, "Scope cleanup: ${resolvedScope.displayName}", cancellable = true) {
                        for (file in scopeFiles.files) {
                            checkCanceled(); processedProgress.incrementAndGet()
                            val output = batchTry(continueOnError) {
                                val psiFile = resolveWritablePsiFile(project, file); runCodeCleanup(
                                project,
                                profile,
                                listOf(psiFile),
                            )
                            }
                            val filePath = relativizePath(project.basePath, file.path)
                            if (output.error == null) {
                                processedFileCount++; successCount++
                                items += QualityScopeCleanupItemDto(
                                    fileUrl = file.url,
                                    filePath = filePath,
                                    success = true,
                                )
                            } else {
                                processedFileCount++; failureCount++
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
                finished.set(true); progressJob.cancel()
            }
        }
        if (timedOut) {
            diagnostics += "Scope cleanup timed out; running cleanup tasks may continue in IDE."; probablyHasMoreMatchingFiles =
                true
        } else if (processedProgress.get() < scopeFiles.files.size) probablyHasMoreMatchingFiles = true
        val skippedCount = scopeFiles.files.size - processedFileCount
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = encodeJson(
                        QualityScopeCleanupResultDto(
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
                            diagnostics = scope.combinedDiagnostics(resolvedScope, diagnostics),
                        ),
                    ),
                ),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Shared internal scope operation helper
// ---------------------------------------------------------------------------

private suspend fun processScopeFilesWithOp(
    project: Project,
    scope: ScopeProgramDescriptorDto,
    operation: QualityOperationKind,
    maxFileCount: Int,
    timeoutMillis: Int,
    continueOnError: Boolean,
    allowUiInteractiveScopes: Boolean,
): CallToolResult {
    val resolvedScopeObj = ScopeResolverService.getInstance(project).resolveDescriptor(
        project = project,
        descriptor = scope,
        allowUiInteractiveScopes = allowUiInteractiveScopes,
    )
    val resolvedScope = resolvedScopeObj.toSimple()
    val scopeFiles = collectProjectContentFilesInScope(project, resolvedScope.scope, maxFileCount)
    val diagnostics = mutableListOf<String>()
    if (scopeFiles.truncatedByFileLimit) {
        diagnostics += "Matched files exceed maxFileCount=$maxFileCount; operation was truncated."
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
                delay(1000.milliseconds)
                reportActivity("Scope $operation: processed $processedProgress, success $successCount, failure $failureCount")
            }
        }
        try {
            timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
                withBackgroundProgress(
                    project, "Scope $operation: ${resolvedScope.displayName}",
                    cancellable = true,
                ) {
                    for (file in scopeFiles.files) {
                        checkCanceled()
                        processedProgress.incrementAndGet()
                        val output = batchTry(continueOnError) {
                            processSingleFileOperation(project, file, operation)
                        }
                        val filePath = relativizePath(project.basePath, file.path)
                        if (output.error == null) {
                            processedFileCount++
                            successCount++
                            items += QualityScopeOperationItemDto(
                                fileUrl = file.url, filePath = filePath, success = true,
                            )
                        } else {
                            processedFileCount++
                            failureCount++
                            items += QualityScopeOperationItemDto(
                                fileUrl = file.url, filePath = filePath,
                                success = false, error = output.error,
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
        diagnostics += "Scope $operation timed out."
        probablyHasMoreMatchingFiles = true
    } else if (processedProgress.get() < scopeFiles.files.size) {
        probablyHasMoreMatchingFiles = true
    }
    val skippedCount = scopeFiles.files.size - processedFileCount

    return CallToolResult(
        content = listOf(
            TextContent(
                text = encodeJson(
                    QualityScopeOperationResultDto(
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
                        diagnostics = scope.combinedDiagnostics(resolvedScope, diagnostics),
                    ),
                ),
            ),
        ),
    )
}

// ---------------------------------------------------------------------------
// Helper: inline scope problems (used by by-severity variant)
// ---------------------------------------------------------------------------

private suspend fun qualityGetScopeProblemsInner(
    project: Project,
    scope: ScopeProgramDescriptorDto,
    errorsOnly: Boolean,
    maxFileCount: Int,
    maxProblemCount: Int,
    timeoutMillis: Int,
    continueOnError: Boolean,
    allowUiInteractiveScopes: Boolean,
): QualityScopeProblemsResultDto {
    if (maxFileCount < 1) throw WorkspaceResourceException("maxFileCount must be >= 1.")
    if (maxProblemCount < 1) throw WorkspaceResourceException("maxProblemCount must be >= 1.")
    validateTimeout(timeoutMillis)

    val (resolvedScope, scopeFiles) = resolveScopeAndCollectFiles(
        project, scope, maxFileCount, allowUiInteractiveScopes,
    )
    val diagnostics = mutableListOf<String>()
    if (scopeFiles.truncatedByFileLimit) {
        diagnostics += "Matched files exceed maxFileCount=$maxFileCount; analysis was truncated."
    }
    if (resolvedScope.scopeShape == ScopeShape.GLOBAL) {
        diagnostics += "Scope traversal analyzes project content files; external library files are skipped."
    }

    val scanCount = AtomicInteger(0)
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
                delay(1000.milliseconds)
                reportActivity("Scope problems inner: scanned ${scanCount.get()}, analyzed $analyzedFileCount, total $totalProblemCount")
            }
        }
        try {
            timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
                withBackgroundProgress(
                    project, "Analyzing scope: ${resolvedScope.displayName}",
                    cancellable = true,
                ) {
                    for (file in scopeFiles.files) {
                        checkCanceled()
                        scanCount.incrementAndGet()
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
                            if (selected.isNotEmpty()) filesWithProblems++
                            items += QualityFileProblemsItemDto(
                                fileUrl = file.url, filePath = filePath, problems = selected,
                            )
                        } else {
                            items += QualityFileProblemsItemDto(
                                fileUrl = file.url, filePath = filePath,
                                problems = emptyList(), error = output.error,
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

    if (timedOut) diagnostics += "Scope problem analysis timed out."
    if (scanCount.get() < scopeFiles.files.size) probablyHasMoreMatchingFiles = true

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
        diagnostics = scope.combinedDiagnostics(resolvedScope, diagnostics),
    )
}

// ---------------------------------------------------------------------------
// General helpers
// ---------------------------------------------------------------------------

private fun normalizeSeverity(rawSeverity: String): String {
    return rawSeverity.trim()
        .uppercase()
        .replace('-', '_')
        .replace(' ', '_')
}

private fun errorResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = message)), isError = true)
