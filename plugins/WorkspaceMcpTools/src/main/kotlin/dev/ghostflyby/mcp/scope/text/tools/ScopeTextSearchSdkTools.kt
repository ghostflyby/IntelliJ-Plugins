/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.text.tools

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
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
import dev.ghostflyby.mcp.common.relativizePathOrOriginal
import dev.ghostflyby.mcp.common.reportActivity
import dev.ghostflyby.mcp.common.WorkspaceResourceException
import dev.ghostflyby.mcp.scope.*
import dev.ghostflyby.mcp.sdk.tools.WorkspaceMcpProjectToolArguments
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import dev.ghostflyby.mcp.sdk.callToolWithProject
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Request
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.*
import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

// ── Tool registration entrypoint ─────────────────────────────────

// ── scope_search_text ────────────────────────────────────────────

@Description("Arguments for ScopeSearchTextArgs")
@Schema
@Serializable
internal data class ScopeSearchTextArgs(
    val query: String = "",
    val mode: ScopeTextQueryMode = ScopeTextQueryMode.PLAIN,
    @Description("Whether to search in a case-sensitive manner.")
    val caseSensitive: Boolean = true,
    @Description("Whether to match whole words only.")
    val wholeWordsOnly: Boolean = false,
    @Description("Search context filter.")
    val searchContext: ScopeTextSearchContextDto = ScopeTextSearchContextDto.ANY,
    @Description("Optional file mask filter.")
    val fileMask: String? = null,
    @Description("Scope program descriptor.")
    val `scope`: ScopeProgramDescriptorDto,
    @Description("Whether UI-interactive scopes are allowed.")
    val allowUiInteractiveScopes: Boolean = false,
    @Description("Maximum number of occurrences to return.")
    val maxUsageCount: Int = 1000,
    @Description("Timeout in milliseconds.")
    val timeoutMillis: Int = 30000,
    @Description("Whether to allow empty string matches.")
    val allowEmptyMatches: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal suspend fun ClientConnection.scopeSearchTextHandler(args: ScopeSearchTextArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        projectArgs = args,
    ) { project ->
        if (args.query.isBlank()) {
            return@callToolWithProject CallToolResult(
                content = listOf(TextContent(text = "query must not be blank.")),
                isError = true,
            )
        }
        if (args.maxUsageCount < 1) {
            return@callToolWithProject CallToolResult(
                content = listOf(TextContent(text = "maxUsageCount must be >= 1.")),
                isError = true,
            )
        }
        if (args.timeoutMillis < 1) {
            return@callToolWithProject CallToolResult(
                content = listOf(TextContent(text = "timeoutMillis must be >= 1.")),
                isError = true,
            )
        }

        val request = ScopeTextSearchRequestDto(
            query = args.query,
            mode = args.mode,
            caseSensitive = args.caseSensitive,
            wholeWordsOnly = args.wholeWordsOnly,
            searchContext = args.searchContext,
            fileMask = args.fileMask,
            scope = args.scope,
            allowUiInteractiveScopes = args.allowUiInteractiveScopes,
            maxUsageCount = args.maxUsageCount,
            timeoutMillis = args.timeoutMillis,
            allowEmptyMatches = args.allowEmptyMatches,
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
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── scope_search_text_quick ──────────────────────────────────────

@Description("Arguments for ScopeSearchTextQuickArgs")
@Schema
@Serializable
internal data class ScopeSearchTextQuickArgs(
    val query: String = "",
    val mode: ScopeTextQueryMode = ScopeTextQueryMode.PLAIN,
    @Description("Preset scope identifier.")
    val scopePreset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
    @Description("Whether to search in a case-sensitive manner.")
    val caseSensitive: Boolean = true,
    @Description("Whether to match whole words only.")
    val wholeWordsOnly: Boolean = false,
    @Description("Search context filter.")
    val searchContext: ScopeTextSearchContextDto = ScopeTextSearchContextDto.ANY,
    @Description("Optional file mask filter.")
    val fileMask: String? = null,
    @Description("Maximum number of occurrences to return.")
    val maxUsageCount: Int = 1000,
    @Description("Timeout in milliseconds.")
    val timeoutMillis: Int = 30000,
    @Description("Whether to allow empty string matches.")
    val allowEmptyMatches: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal suspend fun ClientConnection.scopeSearchTextQuickHandler(args: ScopeSearchTextQuickArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        projectArgs = args,
    ) { project ->
        if (args.query.isBlank()) {
            return@callToolWithProject CallToolResult(
                content = listOf(TextContent(text = "query must not be blank.")),
                isError = true,
            )
        }

        reportActivity(
            Bundle.message(
                "tool.activity.scope.text.search.quick",
                args.scopePreset.name,
                args.mode.name,
                args.query.length,
                args.maxUsageCount,
                args.timeoutMillis,
            ),
        )

        val descriptor = buildPresetScopeDescriptor(
            project = project,
            preset = args.scopePreset,
            allowUiInteractiveScopes = false,
        )

        val innerArgs = ScopeSearchTextArgs(
            query = args.query,
            mode = args.mode,
            caseSensitive = args.caseSensitive,
            wholeWordsOnly = args.wholeWordsOnly,
            searchContext = args.searchContext,
            fileMask = args.fileMask,
            scope = descriptor,
            allowUiInteractiveScopes = false,
            maxUsageCount = args.maxUsageCount,
            timeoutMillis = args.timeoutMillis,
            allowEmptyMatches = args.allowEmptyMatches,
        )
        return@callToolWithProject scopeSearchTextHandler(innerArgs, request)
    }
}

// ── scope_search_text_by_plain ───────────────────────────────────

@Description("Arguments for ScopeSearchTextByPlainArgs")
@Schema
@Serializable
internal data class ScopeSearchTextByPlainArgs(
    val query: String = "",
    @Description("Scope program descriptor.")
    val `scope`: ScopeProgramDescriptorDto,
    @Description("Whether to search in a case-sensitive manner.")
    val caseSensitive: Boolean = true,
    @Description("Whether to match whole words only.")
    val wholeWordsOnly: Boolean = false,
    @Description("Search context filter.")
    val searchContext: ScopeTextSearchContextDto = ScopeTextSearchContextDto.ANY,
    @Description("Optional file mask filter.")
    val fileMask: String? = null,
    @Description("Maximum number of occurrences to return.")
    val maxUsageCount: Int = 1000,
    @Description("Timeout in milliseconds.")
    val timeoutMillis: Int = 30000,
    @Description("Whether UI-interactive scopes are allowed.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal suspend fun ClientConnection.scopeSearchTextByPlainHandler(args: ScopeSearchTextByPlainArgs, request: Request?): CallToolResult {
    if (args.query.isBlank()) {
        return CallToolResult(
            content = listOf(TextContent(text = "query must not be blank.")),
            isError = true,
        )
    }
    reportActivity(Bundle.message("tool.activity.scope.text.search.by.plain", args.query.length))
    val innerArgs = ScopeSearchTextArgs(
        query = args.query,
        mode = ScopeTextQueryMode.PLAIN,
        caseSensitive = args.caseSensitive,
        wholeWordsOnly = args.wholeWordsOnly,
        searchContext = args.searchContext,
        fileMask = args.fileMask,
        scope = args.scope,
        allowUiInteractiveScopes = args.allowUiInteractiveScopes,
        maxUsageCount = args.maxUsageCount,
        timeoutMillis = args.timeoutMillis,
    )
    return scopeSearchTextHandler(innerArgs, request)
}

// ── scope_search_text_by_regex ───────────────────────────────────

@Description("Arguments for ScopeSearchTextByRegexArgs")
@Schema
@Serializable
internal data class ScopeSearchTextByRegexArgs(
    val query: String = "",
    @Description("Scope program descriptor.")
    val `scope`: ScopeProgramDescriptorDto,
    @Description("Whether to search in a case-sensitive manner.")
    val caseSensitive: Boolean = true,
    @Description("Whether to match whole words only.")
    val wholeWordsOnly: Boolean = false,
    @Description("Search context filter.")
    val searchContext: ScopeTextSearchContextDto = ScopeTextSearchContextDto.ANY,
    @Description("Optional file mask filter.")
    val fileMask: String? = null,
    @Description("Maximum number of occurrences to return.")
    val maxUsageCount: Int = 1000,
    @Description("Timeout in milliseconds.")
    val timeoutMillis: Int = 30000,
    @Description("Whether to allow empty string matches.")
    val allowEmptyMatches: Boolean = false,
    @Description("Whether UI-interactive scopes are allowed.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal suspend fun ClientConnection.scopeSearchTextByRegexHandler(args: ScopeSearchTextByRegexArgs, request: Request?): CallToolResult {
    if (args.query.isBlank()) {
        return CallToolResult(
            content = listOf(TextContent(text = "query must not be blank.")),
            isError = true,
        )
    }
    reportActivity(Bundle.message("tool.activity.scope.text.search.by.regex", args.query.length))
    val innerArgs = ScopeSearchTextArgs(
        query = args.query,
        mode = ScopeTextQueryMode.REGEX,
        caseSensitive = args.caseSensitive,
        wholeWordsOnly = args.wholeWordsOnly,
        searchContext = args.searchContext,
        fileMask = args.fileMask,
        scope = args.scope,
        allowUiInteractiveScopes = args.allowUiInteractiveScopes,
        maxUsageCount = args.maxUsageCount,
        timeoutMillis = args.timeoutMillis,
        allowEmptyMatches = args.allowEmptyMatches,
    )
    return scopeSearchTextHandler(innerArgs, request)
}

// ── scope_replace_text_preview ───────────────────────────────────

@Description("Arguments for ScopeReplaceTextPreviewArgs")
@Schema
@Serializable
internal data class ScopeReplaceTextPreviewArgs(
    val search: ScopeTextSearchRequestDto,
    @Description("Replacement text.")
    val replaceWith: String = "",
    @Description("Whether to preserve original case during replacement.")
    val preserveCase: Boolean = false,
    @Description("Optional specific occurrence IDs to replace.")
    val occurrenceIds: List<String> = emptyList(),
    @Description("Whether to fail if specified occurrence IDs are not found.")
    val failOnMissingOccurrenceIds: Boolean = true,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal suspend fun ClientConnection.scopeReplaceTextPreviewHandler(args: ScopeReplaceTextPreviewArgs, request: Request?): CallToolResult {
    val request = ScopeTextReplaceRequestDto(
        search = args.search,
        replaceWith = args.replaceWith,
        preserveCase = args.preserveCase,
        occurrenceIds = args.occurrenceIds,
        failOnMissingOccurrenceIds = args.failOnMissingOccurrenceIds,
    )

    return callToolWithProject(
        projectArgs = args,
    ) { project ->
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
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── scope_replace_text_apply ─────────────────────────────────────

@Description("Arguments for ScopeReplaceTextApplyArgs")
@Schema
@Serializable
internal data class ScopeReplaceTextApplyArgs(
    val search: ScopeTextSearchRequestDto,
    @Description("Replacement text.")
    val replaceWith: String = "",
    @Description("Whether to preserve original case during replacement.")
    val preserveCase: Boolean = false,
    @Description("Optional specific occurrence IDs to replace.")
    val occurrenceIds: List<String> = emptyList(),
    @Description("Whether to fail if specified occurrence IDs are not found.")
    val failOnMissingOccurrenceIds: Boolean = true,
    @Description("Whether to save documents after write.")
    val saveAfterWrite: Boolean = true,
    @Description("Maximum number of occurrences to replace.")
    val maxReplaceCount: Int = 10000,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal suspend fun ClientConnection.scopeReplaceTextApplyHandler(args: ScopeReplaceTextApplyArgs, request: Request?): CallToolResult {
    val request = ScopeTextReplaceRequestDto(
        search = args.search,
        replaceWith = args.replaceWith,
        preserveCase = args.preserveCase,
        occurrenceIds = args.occurrenceIds,
        failOnMissingOccurrenceIds = args.failOnMissingOccurrenceIds,
        saveAfterWrite = args.saveAfterWrite,
        maxReplaceCount = args.maxReplaceCount,
    )

    return callToolWithProject(
        projectArgs = args,
    ) { project ->
        if (request.maxReplaceCount < 1) {
            return@callToolWithProject CallToolResult(
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
            return@callToolWithProject CallToolResult(
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
            return@callToolWithProject CallToolResult(
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
            return@callToolWithProject CallToolResult(
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
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
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
                delay(800)
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
