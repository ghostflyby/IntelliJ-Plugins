/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.text.tools

import com.intellij.find.FindManager
import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.common.WorkspaceResourceException
import dev.ghostflyby.mcp.common.relativizePathOrOriginal
import dev.ghostflyby.mcp.common.reportActivity
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
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

internal class ScopeTextSearchTools {
    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeSearchText(
        query: String,
        mode: ScopeTextQueryMode = ScopeTextQueryMode.PLAIN,
        @Description("Whether to search in a case-sensitive manner.")
        caseSensitive: Boolean = true,
        @Description("Whether to match whole words only.")
        wholeWordsOnly: Boolean = false,
        @Description("Search context filter.")
        searchContext: ScopeTextSearchContextDto = ScopeTextSearchContextDto.ANY,
        @Description("Optional file mask filter.")
        fileMask: String? = null,
        @Description("Scope program descriptor.")
        scope: ScopeProgramDescriptorDto,
        @Description("Whether UI-interactive scopes are allowed.")
        allowUiInteractiveScopes: Boolean = false,
        @Description("Maximum number of occurrences to return.")
        maxUsageCount: Int = 1000,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @Description("Whether to allow empty string matches.")
        allowEmptyMatches: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        if (query.isBlank()) {
            return CallToolResult(
                content = listOf(TextContent(text = "query must not be blank.")),
                isError = true,
            )
        }
        if (maxUsageCount < 1) {
            return CallToolResult(
                content = listOf(TextContent(text = "maxUsageCount must be >= 1.")),
                isError = true,
            )
        }
        if (timeoutMillis < 1) {
            return CallToolResult(
                content = listOf(TextContent(text = "timeoutMillis must be >= 1.")),
                isError = true,
            )
        }

        val request = ScopeTextSearchRequestDto(
            query = query,
            mode = mode,
            caseSensitive = caseSensitive,
            wholeWordsOnly = wholeWordsOnly,
            searchContext = searchContext,
            fileMask = fileMask,
            scope = scope,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
            maxUsageCount = maxUsageCount,
            timeoutMillis = timeoutMillis,
            allowEmptyMatches = allowEmptyMatches,
        )

        reportActivity(
            Bundle.message(
                "tool.activity.scope.text.search.start",
                request.mode.name,
                request.query.length,
                request.maxUsageCount,
                request.timeoutMillis,
            ),
        )

        return scopeSearchAndFormat(request, project)
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeSearchTextQuick(
        query: String,
        mode: ScopeTextQueryMode = ScopeTextQueryMode.PLAIN,
        @Description("Preset scope identifier.")
        scopePreset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
        @Description("Whether to search in a case-sensitive manner.")
        caseSensitive: Boolean = true,
        @Description("Whether to match whole words only.")
        wholeWordsOnly: Boolean = false,
        @Description("Search context filter.")
        searchContext: ScopeTextSearchContextDto = ScopeTextSearchContextDto.ANY,
        @Description("Optional file mask filter.")
        fileMask: String? = null,
        @Description("Maximum number of occurrences to return.")
        maxUsageCount: Int = 1000,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @Description("Whether to allow empty string matches.")
        allowEmptyMatches: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        if (query.isBlank()) {
            return CallToolResult(
                content = listOf(TextContent(text = "query must not be blank.")),
                isError = true,
            )
        }

        reportActivity(
            Bundle.message(
                "tool.activity.scope.text.search.quick",
                scopePreset.name,
                mode.name,
                query.length,
                maxUsageCount,
                timeoutMillis,
            ),
        )

        val descriptor = buildPresetScopeDescriptor(
            project = project,
            preset = scopePreset,
            allowUiInteractiveScopes = false,
        )

        val request = ScopeTextSearchRequestDto(
            query = query,
            mode = mode,
            caseSensitive = caseSensitive,
            wholeWordsOnly = wholeWordsOnly,
            searchContext = searchContext,
            fileMask = fileMask,
            scope = descriptor,
            allowUiInteractiveScopes = false,
            maxUsageCount = maxUsageCount,
            timeoutMillis = timeoutMillis,
            allowEmptyMatches = allowEmptyMatches,
        )
        return scopeSearchAndFormat(request, project)
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeSearchTextByPlain(
        query: String,
        @Description("Scope program descriptor.")
        scope: ScopeProgramDescriptorDto,
        @Description("Whether to search in a case-sensitive manner.")
        caseSensitive: Boolean = true,
        @Description("Whether to match whole words only.")
        wholeWordsOnly: Boolean = false,
        @Description("Search context filter.")
        searchContext: ScopeTextSearchContextDto = ScopeTextSearchContextDto.ANY,
        @Description("Optional file mask filter.")
        fileMask: String? = null,
        @Description("Maximum number of occurrences to return.")
        maxUsageCount: Int = 1000,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @Description("Whether UI-interactive scopes are allowed.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        if (query.isBlank()) {
            return CallToolResult(
                content = listOf(TextContent(text = "query must not be blank.")),
                isError = true,
            )
        }
        reportActivity(Bundle.message("tool.activity.scope.text.search.by.plain", query.length))
        val project = call.project()
        val request = ScopeTextSearchRequestDto(
            query = query,
            mode = ScopeTextQueryMode.PLAIN,
            caseSensitive = caseSensitive,
            wholeWordsOnly = wholeWordsOnly,
            searchContext = searchContext,
            fileMask = fileMask,
            scope = scope,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
            maxUsageCount = maxUsageCount,
            timeoutMillis = timeoutMillis,
        )
        return scopeSearchAndFormat(request, project)
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeSearchTextByRegex(
        query: String,
        @Description("Scope program descriptor.")
        scope: ScopeProgramDescriptorDto,
        @Description("Whether to search in a case-sensitive manner.")
        caseSensitive: Boolean = true,
        @Description("Whether to match whole words only.")
        wholeWordsOnly: Boolean = false,
        @Description("Search context filter.")
        searchContext: ScopeTextSearchContextDto = ScopeTextSearchContextDto.ANY,
        @Description("Optional file mask filter.")
        fileMask: String? = null,
        @Description("Maximum number of occurrences to return.")
        maxUsageCount: Int = 1000,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @Description("Whether to allow empty string matches.")
        allowEmptyMatches: Boolean = false,
        @Description("Whether UI-interactive scopes are allowed.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        if (query.isBlank()) {
            return CallToolResult(
                content = listOf(TextContent(text = "query must not be blank.")),
                isError = true,
            )
        }
        reportActivity(Bundle.message("tool.activity.scope.text.search.by.regex", query.length))
        val project = call.project()
        val request = ScopeTextSearchRequestDto(
            query = query,
            mode = ScopeTextQueryMode.REGEX,
            caseSensitive = caseSensitive,
            wholeWordsOnly = wholeWordsOnly,
            searchContext = searchContext,
            fileMask = fileMask,
            scope = scope,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
            maxUsageCount = maxUsageCount,
            timeoutMillis = timeoutMillis,
            allowEmptyMatches = allowEmptyMatches,
        )
        return scopeSearchAndFormat(request, project)
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeReplaceTextPreview(
        @Description("Search and scope configuration.")
        search: ScopeTextSearchRequestDto,
        @Description("Replacement text.")
        replaceWith: String = "",
        @Description("Whether to preserve original case during replacement.")
        preserveCase: Boolean = false,
        @Description("Optional specific occurrence IDs to replace.")
        occurrenceIds: List<String> = emptyList(),
        @Description("Whether to fail if specified occurrence IDs are not found.")
        failOnMissingOccurrenceIds: Boolean = true,
    ): CallToolResult {
        val project = call.project()
        val request = ScopeTextReplaceRequestDto(
            search = search,
            replaceWith = replaceWith,
            preserveCase = preserveCase,
            occurrenceIds = occurrenceIds,
            failOnMissingOccurrenceIds = failOnMissingOccurrenceIds,
        )

        reportActivity(
            Bundle.message(
                "tool.activity.scope.text.replace.preview.start",
                request.search.mode.name,
                request.search.query.length,
                request.replaceWith.length,
                request.occurrenceIds.size,
            ),
        )
        val plan = buildReplacementPlan(project, request)
        val result = ScopeTextReplacePreviewResultDto(
            scopeDisplayName = plan.execution.scopeDisplayName,
            scopeShape = plan.execution.scopeShape,
            query = request.search.query,
            mode = request.search.mode,
            replaceWith = request.replaceWith,
            selectedEntries = plan.selectedOccurrences.map { occurrence ->
                ScopeTextReplacementPreviewEntryDto(
                    occurrence = occurrence.dto,
                    replacementText = plan.replacementByOccurrenceId[occurrence.dto.occurrenceId]
                        ?: throw WorkspaceResourceException("Internal error: missing replacement preview for occurrence '${occurrence.dto.occurrenceId}'."),
                )
            },
            missingOccurrenceIds = plan.missingOccurrenceIds,
            probablyHasMoreMatchingEntries = plan.execution.probablyHasMoreMatchingEntries,
            timedOut = plan.execution.timedOut,
            canceled = plan.execution.canceled,
            diagnostics = plan.execution.diagnostics,
        )
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeReplaceTextApply(
        @Description("Search and scope configuration.")
        search: ScopeTextSearchRequestDto,
        @Description("Replacement text.")
        replaceWith: String = "",
        @Description("Whether to preserve original case during replacement.")
        preserveCase: Boolean = false,
        @Description("Optional specific occurrence IDs to replace.")
        occurrenceIds: List<String> = emptyList(),
        @Description("Whether to fail if specified occurrence IDs are not found.")
        failOnMissingOccurrenceIds: Boolean = true,
        @Description("Whether to save documents after write.")
        saveAfterWrite: Boolean = true,
        @Description("Maximum number of occurrences to replace.")
        maxReplaceCount: Int = 10000,
    ): CallToolResult {
        val project = call.project()
        val request = ScopeTextReplaceRequestDto(
            search = search,
            replaceWith = replaceWith,
            preserveCase = preserveCase,
            occurrenceIds = occurrenceIds,
            failOnMissingOccurrenceIds = failOnMissingOccurrenceIds,
            saveAfterWrite = saveAfterWrite,
            maxReplaceCount = maxReplaceCount,
        )

        if (request.maxReplaceCount < 1) {
            return CallToolResult(
                content = listOf(TextContent(text = "maxReplaceCount must be >= 1.")),
                isError = true,
            )
        }

        reportActivity(
            Bundle.message(
                "tool.activity.scope.text.replace.apply.start",
                request.search.mode.name,
                request.search.query.length,
                request.replaceWith.length,
                request.occurrenceIds.size,
                request.saveAfterWrite,
            ),
        )

        val plan = buildReplacementPlan(project, request)

        if (plan.execution.timedOut && request.occurrenceIds.isEmpty()) {
            return CallToolResult(
                content = listOf(
                    TextContent(
                        text = "Search timed out while preparing replace-all. " +
                                "Increase timeoutMillis or provide explicit occurrenceIds.",
                    ),
                ),
                isError = true,
            )
        }

        val selectedOccurrences = plan.selectedOccurrences
        if (selectedOccurrences.size > request.maxReplaceCount) {
            return CallToolResult(
                content = listOf(
                    TextContent(
                        text = "Selected occurrence count ${selectedOccurrences.size} exceeds maxReplaceCount ${request.maxReplaceCount}.",
                    ),
                ),
                isError = true,
            )
        }

        if (selectedOccurrences.isEmpty()) {
            val emptyResult = ScopeTextReplaceApplyResultDto(
                scopeDisplayName = plan.execution.scopeDisplayName,
                scopeShape = plan.execution.scopeShape,
                query = request.search.query,
                mode = request.search.mode,
                replaceWith = request.replaceWith,
                requestedOccurrenceCount = 0,
                replacedOccurrenceCount = 0,
                replacedFileCount = 0,
                replacedOccurrenceIds = emptyList(),
                missingOccurrenceIds = plan.missingOccurrenceIds,
                timedOut = plan.execution.timedOut,
                canceled = plan.execution.canceled,
                diagnostics = plan.execution.diagnostics,
            )
            return CallToolResult(
                content = listOf(TextContent(text = toolArgsJson.encodeToString(emptyResult))),
            )
        }

        val groupedByFile = selectedOccurrences.groupBy { it.file }
        val replacedOccurrenceIds = mutableListOf<String>()

        backgroundWriteAction {
            val fileDocumentManager = FileDocumentManager.getInstance()
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            val modifiedDocuments = linkedSetOf<com.intellij.openapi.editor.Document>()
            groupedByFile.forEach { (file, occurrences) ->
                val document = fileDocumentManager.getDocument(file)
                    ?: throw WorkspaceResourceException("No text document available for file '${file.url}'.")
                if (!file.isWritable || !document.isWritable) {
                    throw WorkspaceResourceException("File is not writable: ${file.url}")
                }
                val sortedByOffsetDesc = occurrences.sortedByDescending { it.dto.startOffset }
                for (occurrence in sortedByOffsetDesc) {
                    val dto = occurrence.dto
                    val replacement = plan.replacementByOccurrenceId[dto.occurrenceId]
                        ?: throw WorkspaceResourceException("Internal error: missing replacement for occurrence '${dto.occurrenceId}'.")
                    ensureOccurrenceStillMatches(document, dto)
                    document.replaceString(dto.startOffset, dto.endOffset, replacement)
                    replacedOccurrenceIds += dto.occurrenceId
                }
                modifiedDocuments += document
            }
            modifiedDocuments.forEach { document ->
                psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)
                psiDocumentManager.commitDocument(document)
            }
            if (request.saveAfterWrite) {
                modifiedDocuments.forEach(fileDocumentManager::saveDocument)
            }
        }

        val result = ScopeTextReplaceApplyResultDto(
            scopeDisplayName = plan.execution.scopeDisplayName,
            scopeShape = plan.execution.scopeShape,
            query = request.search.query,
            mode = request.search.mode,
            replaceWith = request.replaceWith,
            requestedOccurrenceCount = selectedOccurrences.size,
            replacedOccurrenceCount = replacedOccurrenceIds.size,
            replacedFileCount = groupedByFile.size,
            replacedOccurrenceIds = replacedOccurrenceIds,
            missingOccurrenceIds = plan.missingOccurrenceIds,
            timedOut = plan.execution.timedOut,
            canceled = plan.execution.canceled,
            diagnostics = plan.execution.diagnostics,
        )
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── Shared helper for search tools ──────────────────────────────

private suspend fun scopeSearchAndFormat(
    request: ScopeTextSearchRequestDto,
    project: com.intellij.openapi.project.Project,
): CallToolResult {
    val execution = executeSearch(project, request)
    val result = ScopeTextSearchResultDto(
        scopeDisplayName = execution.scopeDisplayName,
        scopeShape = execution.scopeShape,
        mode = request.mode,
        query = request.query,
        caseSensitive = request.caseSensitive,
        wholeWordsOnly = request.wholeWordsOnly,
        searchContext = request.searchContext,
        fileMask = request.fileMask,
        occurrences = execution.occurrences.map { it.dto },
        probablyHasMoreMatchingEntries = execution.probablyHasMoreMatchingEntries,
        timedOut = execution.timedOut,
        canceled = execution.canceled,
        diagnostics = execution.diagnostics,
    )
    return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
}

// ══════════════════════════════════════════════════════════════════
//  Helper functions (migrated from ScopeTextSearchMcpTools.kt)
// ══════════════════════════════════════════════════════════════════

private const val MAX_LINE_TEXT_CONTEXT_CHARS = 200

private suspend fun buildReplacementPlan(
    project: com.intellij.openapi.project.Project,
    request: ScopeTextReplaceRequestDto,
): ReplacementPlan {
    val execution = executeSearch(project, request.search)
    val selection = selectOccurrences(
        all = execution.occurrences,
        occurrenceIds = request.occurrenceIds,
        failOnMissingOccurrenceIds = request.failOnMissingOccurrenceIds,
    )
    val replacementModel = buildFindModel(
        request = request.search,
        scope = execution.resolvedScope.scope,
        scopeDisplayName = execution.scopeDisplayName,
        replaceWith = request.replaceWith,
        preserveCase = request.preserveCase,
    )
    val replacementByOccurrenceId = computeReplacementTexts(project, selection.selected, replacementModel)
    return ReplacementPlan(
        execution = execution,
        selectedOccurrences = selection.selected,
        missingOccurrenceIds = selection.missingOccurrenceIds,
        replacementByOccurrenceId = replacementByOccurrenceId,
    )
}

private suspend fun executeSearch(
    project: com.intellij.openapi.project.Project,
    request: ScopeTextSearchRequestDto,
): SearchExecution {
    val resolvedScope = ScopeResolverService.getInstance(project).resolveDescriptor(
        project = project,
        descriptor = request.scope,
        allowUiInteractiveScopes = request.allowUiInteractiveScopes,
    )
    val findModel = buildFindModel(
        request = request,
        scope = resolvedScope.scope,
        scopeDisplayName = resolvedScope.displayName,
        replaceWith = null,
        preserveCase = false,
    )

    if (request.mode == ScopeTextQueryMode.REGEX) {
        val pattern = findModel.compileRegExp()
            ?: throw WorkspaceResourceException("Invalid regex pattern.")
        if (!request.allowEmptyMatches && pattern.matcher("").find()) {
            throw WorkspaceResourceException(
                "Regex pattern may match empty strings. Set allowEmptyMatches=true to override.",
            )
        }
    }

    val usages = CopyOnWriteArrayList<UsageInfo>()
    val matchedCount = AtomicInteger(0)
    val finished = AtomicBoolean(false)
    var timedOut = false

    coroutineScope {
        val progressReporter = launch {
            while (isActive && !finished.get()) {
                delay(800.milliseconds)
                reportActivity(
                    Bundle.message(
                        "tool.activity.scope.text.search.progress",
                        matchedCount.get(),
                    ),
                )
            }
        }

        try {
            timedOut = withTimeoutOrNull(request.timeoutMillis.milliseconds) {
                withBackgroundProgress(
                    project,
                    Bundle.message("progress.title.scope.text.search", request.query),
                    cancellable = true,
                ) {
                    coroutineToIndicator { indicator ->
                        FindInProjectUtil.findUsages(
                            findModel,
                            project,
                            indicator,
                            FindUsagesProcessPresentation(UsageViewPresentation()),
                            emptySet(),
                            Processor { usage ->
                                usages += usage
                                matchedCount.incrementAndGet()
                                usages.size < request.maxUsageCount
                            },
                        )
                    }
                }
                false
            } ?: true
        } finally {
            finished.set(true)
            progressReporter.cancel()
        }
    }

    val rawOccurrences = usages.mapNotNull { toRawOccurrence(project.basePath, it, request) }
    val diagnostics = buildList {
        addAll(request.scope.diagnostics)
        addAll(resolvedScope.diagnostics)
        if (timedOut) {
            add("Search timed out before completion.")
        }
    }.distinct()

    return SearchExecution(
        resolvedScope = resolvedScope,
        scopeDisplayName = resolvedScope.displayName,
        scopeShape = resolvedScope.scopeShape,
        occurrences = rawOccurrences,
        probablyHasMoreMatchingEntries = usages.size >= request.maxUsageCount,
        timedOut = timedOut,
        canceled = false,
        diagnostics = diagnostics,
    )
}

private fun buildFindModel(
    request: ScopeTextSearchRequestDto,
    scope: com.intellij.psi.search.SearchScope,
    scopeDisplayName: String,
    replaceWith: String?,
    preserveCase: Boolean,
): FindModel {
    return FindModel().apply {
        stringToFind = request.query
        stringToReplace = replaceWith ?: ""
        isReplaceState = replaceWith != null
        isPreserveCase = preserveCase
        isCaseSensitive = request.caseSensitive
        isWholeWordsOnly = request.wholeWordsOnly
        isRegularExpressions = request.mode == ScopeTextQueryMode.REGEX
        searchContext = FindModel.SearchContext.valueOf(request.searchContext.name)
        isMultipleFiles = true
        isProjectScope = false
        moduleName = null
        directoryName = null
        fileFilter = request.fileMask
        isWithSubdirectories = true
        isSearchInProjectFiles = false
        isCustomScope = true
        customScope = scope
        customScopeName = scopeDisplayName
    }
}

private suspend fun toRawOccurrence(
    projectBasePath: String?,
    usage: UsageInfo,
    request: ScopeTextSearchRequestDto,
): RawOccurrence? {
    val file = usage.virtualFile ?: return null
    val navigationRange = usage.navigationRange ?: return null
    val snapshot = readAction {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@readAction null
        if (navigationRange.startOffset < 0 || navigationRange.endOffset < navigationRange.startOffset) {
            return@readAction null
        }
        if (navigationRange.endOffset > document.textLength) {
            return@readAction null
        }
        val startLineIndex = document.getLineNumber(navigationRange.startOffset)
        val endLineIndex = document.getLineNumber(navigationRange.endOffset.coerceAtLeast(navigationRange.startOffset))
        val startLineOffset = document.getLineStartOffset(startLineIndex)
        val endLineOffset = document.getLineEndOffset(endLineIndex)

        val before = document.getText(TextRange(startLineOffset, navigationRange.startOffset))
            .takeLast(MAX_LINE_TEXT_CONTEXT_CHARS)
        val matchedText = document.getText(TextRange(navigationRange.startOffset, navigationRange.endOffset))
        val after = document.getText(TextRange(navigationRange.endOffset, endLineOffset))
            .take(MAX_LINE_TEXT_CONTEXT_CHARS)
        val lineText = "$before||$matchedText||$after"
        val filePath = relativizePathOrOriginal(projectBasePath, file.path)

        OccurrenceSnapshot(
            filePath = filePath,
            lineNumber = startLineIndex + 1,
            startOffset = navigationRange.startOffset,
            endOffset = navigationRange.endOffset,
            lineText = lineText,
            matchedText = matchedText,
        )
    } ?: return null

    val occurrenceId = sha256ShortHash(
        listOf(
            file.url,
            snapshot.startOffset.toString(),
            snapshot.endOffset.toString(),
            request.mode.name,
            request.query,
        ).joinToString("|"),
    )
    val dto = ScopeTextOccurrenceDto(
        occurrenceId = occurrenceId,
        fileUrl = file.url,
        filePath = snapshot.filePath,
        lineNumber = snapshot.lineNumber,
        startOffset = snapshot.startOffset,
        endOffset = snapshot.endOffset,
        lineText = snapshot.lineText,
        matchedText = snapshot.matchedText,
    )
    return RawOccurrence(
        file = file,
        dto = dto,
    )
}

private fun selectOccurrences(
    all: List<RawOccurrence>,
    occurrenceIds: List<String>,
    failOnMissingOccurrenceIds: Boolean,
): SelectedOccurrences {
    if (occurrenceIds.isEmpty()) {
        return SelectedOccurrences(
            selected = all,
            missingOccurrenceIds = emptyList(),
        )
    }
    val uniqueRequestedIds = occurrenceIds.distinct()
    val byId = linkedMapOf<String, RawOccurrence>()
    all.forEach { occurrence ->
        byId.putIfAbsent(occurrence.dto.occurrenceId, occurrence)
    }
    val selected = uniqueRequestedIds.mapNotNull(byId::get)
    val missing = uniqueRequestedIds.filterNot(byId::containsKey)
    if (missing.isNotEmpty() && failOnMissingOccurrenceIds) {
        val preview = missing.take(10).joinToString(", ")
        throw WorkspaceResourceException("Some occurrenceIds were not found in current search result: $preview")
    }
    return SelectedOccurrences(
        selected = selected,
        missingOccurrenceIds = missing,
    )
}

private suspend fun computeReplacementTexts(
    project: com.intellij.openapi.project.Project,
    occurrences: List<RawOccurrence>,
    replaceModel: FindModel,
): Map<String, String> {
    if (occurrences.isEmpty()) {
        return emptyMap()
    }
    val findManager = FindManager.getInstance(project)
    val result = linkedMapOf<String, String>()
    occurrences.forEach { occurrence ->
        val dto = occurrence.dto
        val replacementText = readAction {
            val document = FileDocumentManager.getInstance().getDocument(occurrence.file)
                ?: throw WorkspaceResourceException("No text document available for file '${dto.fileUrl}'.")
            val currentMatched = ensureOccurrenceStillMatches(document, dto)
            try {
                findManager.getStringToReplace(
                    currentMatched,
                    replaceModel,
                    dto.startOffset,
                    document.text,
                )
            } catch (error: FindManager.MalformedReplacementStringException) {
                throw WorkspaceResourceException("Malformed replacement string: ${error.message ?: "unknown error"}")
            }
        }
        result[dto.occurrenceId] = replacementText
    }
    return result
}

private fun ensureOccurrenceStillMatches(
    document: com.intellij.openapi.editor.Document,
    occurrence: ScopeTextOccurrenceDto,
): String {
    validateRangeInDocument(document, occurrence.startOffset, occurrence.endOffset, occurrence.fileUrl)
    val currentMatched = document.getText(TextRange(occurrence.startOffset, occurrence.endOffset))
    if (currentMatched != occurrence.matchedText) {
        throw WorkspaceResourceException(
            "Occurrence '${occurrence.occurrenceId}' no longer matches current content in '${occurrence.fileUrl}'. " +
                    "Please rerun preview/search.",
        )
    }
    return currentMatched
}

private fun validateRangeInDocument(
    document: com.intellij.openapi.editor.Document,
    startOffset: Int,
    endOffset: Int,
    fileUrl: String,
) {
    if (startOffset !in 0..endOffset || endOffset > document.textLength) {
        throw WorkspaceResourceException(
            "Occurrence range [$startOffset, $endOffset) is out of bounds for '$fileUrl' " +
                    "(textLength=${document.textLength}).",
        )
    }
}

private fun sha256ShortHash(text: String, length: Int = 16): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }.take(length)
}

// ── Internal data classes ────────────────────────────────────────

private data class OccurrenceSnapshot(
    val filePath: String,
    val lineNumber: Int,
    val startOffset: Int,
    val endOffset: Int,
    val lineText: String,
    val matchedText: String,
)

private data class RawOccurrence(
    val file: VirtualFile,
    val dto: ScopeTextOccurrenceDto,
)

private data class SearchExecution(
    val resolvedScope: ScopeResolverService.ResolvedScope,
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    val occurrences: List<RawOccurrence>,
    val probablyHasMoreMatchingEntries: Boolean,
    val timedOut: Boolean,
    val canceled: Boolean,
    val diagnostics: List<String>,
)

private data class SelectedOccurrences(
    val selected: List<RawOccurrence>,
    val missingOccurrenceIds: List<String>,
)

private data class ReplacementPlan(
    val execution: SearchExecution,
    val selectedOccurrences: List<RawOccurrence>,
    val missingOccurrenceIds: List<String>,
    val replacementByOccurrenceId: Map<String, String>,
)
