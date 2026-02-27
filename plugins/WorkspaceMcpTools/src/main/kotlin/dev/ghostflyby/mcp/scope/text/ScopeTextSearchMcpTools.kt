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

package dev.ghostflyby.mcp.scope.text

import com.intellij.find.FindManager
import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
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
import dev.ghostflyby.mcp.common.AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX
import dev.ghostflyby.mcp.common.ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION
import dev.ghostflyby.mcp.common.relativizePathOrOriginal
import dev.ghostflyby.mcp.common.reportActivity
import dev.ghostflyby.mcp.scope.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@Suppress("FunctionName")
internal class ScopeTextSearchMcpTools : McpToolset {

    private companion object {
        private const val MAX_LINE_TEXT_CONTEXT_CHARS = 200
    }

    @McpTool
    @McpDescription(
        "Search text within a resolved scope descriptor using IntelliJ Find engine. " +
            "Supports plain text and regex mode, file mask, and search context.",
    )
    suspend fun scope_search_text(request: ScopeTextSearchRequestDto): ScopeTextSearchResultDto {
        reportActivity(
            Bundle.message(
                "tool.activity.scope.text.search.start",
                request.mode.name,
                request.query.length,
                request.maxUsageCount,
                request.timeoutMillis,
            ),
        )
        val execution = executeSearch(request)
        return ScopeTextSearchResultDto(
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
    }

    @McpTool
    @McpDescription("First-call friendly text search shortcut with preset scope.$AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX")
    suspend fun scope_search_text_quick(
        @McpDescription("Text or regex pattern to search.")
        query: String,
        @McpDescription("Search mode.")
        mode: ScopeTextQueryMode = ScopeTextQueryMode.PLAIN,
        @McpDescription("Preset scope for quick text search.")
        scopePreset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
        @McpDescription("Whether to search in a case-sensitive manner.")
        caseSensitive: Boolean = true,
        @McpDescription("Whether to match whole words only.")
        wholeWordsOnly: Boolean = false,
        @McpDescription("Search context filter.")
        searchContext: ScopeTextSearchContextDto = ScopeTextSearchContextDto.ANY,
        @McpDescription("Optional file mask filter, e.g. '*.kt, !*Test.kt'.")
        fileMask: String? = null,
        @McpDescription("Maximum number of occurrences to return.")
        maxUsageCount: Int = 1000,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @McpDescription("Whether to allow patterns that may match empty string in REGEX mode.")
        allowEmptyMatches: Boolean = false,
    ): ScopeTextSearchResultDto {
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
        val project = currentCoroutineContext().project
        val descriptor = buildPresetScopeDescriptor(
            project = project,
            preset = scopePreset,
            allowUiInteractiveScopes = false,
        )
        return scope_search_text(
            buildSearchRequest(
                query = query,
                mode = mode,
                scope = descriptor,
                caseSensitive = caseSensitive,
                wholeWordsOnly = wholeWordsOnly,
                searchContext = searchContext,
                fileMask = fileMask,
                allowUiInteractiveScopes = false,
                maxUsageCount = maxUsageCount,
                timeoutMillis = timeoutMillis,
                allowEmptyMatches = allowEmptyMatches,
            ),
        )
    }

    @McpTool
    @McpDescription("Shortcut: search plain text within a resolved scope descriptor.")
    suspend fun scope_search_text_by_plain(
        @McpDescription("Text to search.")
        query: String,
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Whether to search in a case-sensitive manner.")
        caseSensitive: Boolean = true,
        @McpDescription("Whether to match whole words only.")
        wholeWordsOnly: Boolean = false,
        @McpDescription("Search context filter.")
        searchContext: ScopeTextSearchContextDto = ScopeTextSearchContextDto.ANY,
        @McpDescription("Optional file mask filter, e.g. '*.kt, !*Test.kt'.")
        fileMask: String? = null,
        @McpDescription("Maximum number of occurrences to return.")
        maxUsageCount: Int = 1000,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
        allowUiInteractiveScopes: Boolean = false,
    ): ScopeTextSearchResultDto {
        reportActivity(Bundle.message("tool.activity.scope.text.search.by.plain", query.length))
        return scope_search_text(
            buildSearchRequest(
                query = query,
                mode = ScopeTextQueryMode.PLAIN,
                scope = scope,
                caseSensitive = caseSensitive,
                wholeWordsOnly = wholeWordsOnly,
                searchContext = searchContext,
                fileMask = fileMask,
                allowUiInteractiveScopes = allowUiInteractiveScopes,
                maxUsageCount = maxUsageCount,
                timeoutMillis = timeoutMillis,
            ),
        )
    }

    @McpTool
    @McpDescription("Shortcut: search regex pattern within a resolved scope descriptor.")
    suspend fun scope_search_text_by_regex(
        @McpDescription("Regex pattern to search.")
        query: String,
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Whether to search in a case-sensitive manner.")
        caseSensitive: Boolean = true,
        @McpDescription("Whether to match whole words only.")
        wholeWordsOnly: Boolean = false,
        @McpDescription("Search context filter.")
        searchContext: ScopeTextSearchContextDto = ScopeTextSearchContextDto.ANY,
        @McpDescription("Optional file mask filter, e.g. '*.kt, !*Test.kt'.")
        fileMask: String? = null,
        @McpDescription("Maximum number of occurrences to return.")
        maxUsageCount: Int = 1000,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @McpDescription("Whether to allow patterns that may match empty string.")
        allowEmptyMatches: Boolean = false,
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
        allowUiInteractiveScopes: Boolean = false,
    ): ScopeTextSearchResultDto {
        reportActivity(Bundle.message("tool.activity.scope.text.search.by.regex", query.length))
        return scope_search_text(
            buildSearchRequest(
                query = query,
                mode = ScopeTextQueryMode.REGEX,
                scope = scope,
                caseSensitive = caseSensitive,
                wholeWordsOnly = wholeWordsOnly,
                searchContext = searchContext,
                fileMask = fileMask,
                allowUiInteractiveScopes = allowUiInteractiveScopes,
                maxUsageCount = maxUsageCount,
                timeoutMillis = timeoutMillis,
                allowEmptyMatches = allowEmptyMatches,
            ),
        )
    }

    @McpTool
    @McpDescription(
        "Preview text replacement within a scope. " +
            "This computes replacement text using IntelliJ Find/Replace semantics (including regex groups and preserve-case).",
    )
    suspend fun scope_replace_text_preview(request: ScopeTextReplaceRequestDto): ScopeTextReplacePreviewResultDto {
        reportActivity(
            Bundle.message(
                "tool.activity.scope.text.replace.preview.start",
                request.search.mode.name,
                request.search.query.length,
                request.replaceWith.length,
                request.occurrenceIds.size,
            ),
        )
        val plan = buildReplacementPlan(request)
        return ScopeTextReplacePreviewResultDto(
            scopeDisplayName = plan.execution.scopeDisplayName,
            scopeShape = plan.execution.scopeShape,
            query = request.search.query,
            mode = request.search.mode,
            replaceWith = request.replaceWith,
            selectedEntries = plan.selectedOccurrences.map { occurrence ->
                ScopeTextReplacementPreviewEntryDto(
                    occurrence = occurrence.dto,
                    replacementText = plan.replacementByOccurrenceId[occurrence.dto.occurrenceId]
                        ?: mcpFail("Internal error: missing replacement preview for occurrence '${occurrence.dto.occurrenceId}'."),
                )
            },
            missingOccurrenceIds = plan.missingOccurrenceIds,
            probablyHasMoreMatchingEntries = plan.execution.probablyHasMoreMatchingEntries,
            timedOut = plan.execution.timedOut,
            canceled = plan.execution.canceled,
            diagnostics = plan.execution.diagnostics,
        )
    }

    @McpTool
    @McpDescription(
        "Apply text replacement within a scope. " +
            "If occurrenceIds is empty, all found occurrences are replaced. " +
            "If occurrenceIds is provided, only those matches are replaced.",
    )
    suspend fun scope_replace_text_apply(request: ScopeTextReplaceRequestDto): ScopeTextReplaceApplyResultDto {
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

        if (request.maxReplaceCount < 1) {
            mcpFail("maxReplaceCount must be >= 1.")
        }

        val plan = buildReplacementPlan(request)
        if (plan.execution.timedOut && request.occurrenceIds.isEmpty()) {
            mcpFail(
                "Search timed out while preparing replace-all. " +
                    "Increase timeoutMillis or provide explicit occurrenceIds.",
            )
        }
        val selectedOccurrences = plan.selectedOccurrences
        if (selectedOccurrences.size > request.maxReplaceCount) {
            mcpFail(
                "Selected occurrence count ${selectedOccurrences.size} exceeds maxReplaceCount ${request.maxReplaceCount}.",
            )
        }
        if (selectedOccurrences.isEmpty()) {
            return ScopeTextReplaceApplyResultDto(
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
        }

        val groupedByFile = selectedOccurrences.groupBy { it.file }
        val replacedOccurrenceIds = mutableListOf<String>()
        val project = currentCoroutineContext().project

        backgroundWriteAction {
            val fileDocumentManager = FileDocumentManager.getInstance()
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            val modifiedDocuments = linkedSetOf<com.intellij.openapi.editor.Document>()
            groupedByFile.forEach { (file, occurrences) ->
                val document = fileDocumentManager.getDocument(file)
                    ?: mcpFail("No text document available for file '${file.url}'.")
                if (!file.isWritable || !document.isWritable) {
                    mcpFail("File is not writable: ${file.url}")
                }
                val sortedByOffsetDesc = occurrences.sortedByDescending { it.dto.startOffset }
                for (occurrence in sortedByOffsetDesc) {
                    val dto = occurrence.dto
                    val replacement = plan.replacementByOccurrenceId[dto.occurrenceId]
                        ?: mcpFail("Internal error: missing replacement for occurrence '${dto.occurrenceId}'.")
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

        return ScopeTextReplaceApplyResultDto(
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
    }

    private suspend fun buildReplacementPlan(request: ScopeTextReplaceRequestDto): ReplacementPlan {
        val execution = executeSearch(request.search)
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
        val replacementByOccurrenceId = computeReplacementTexts(selection.selected, replacementModel)
        return ReplacementPlan(
            execution = execution,
            selectedOccurrences = selection.selected,
            missingOccurrenceIds = selection.missingOccurrenceIds,
            replacementByOccurrenceId = replacementByOccurrenceId,
        )
    }

    private suspend fun executeSearch(request: ScopeTextSearchRequestDto): SearchExecution {
        validateSearchRequest(request)
        val project = currentCoroutineContext().project
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
        validateRegexMode(findModel, request)

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

    private fun validateSearchRequest(request: ScopeTextSearchRequestDto) {
        if (request.query.isBlank()) {
            mcpFail("query must not be blank.")
        }
        if (request.maxUsageCount < 1) {
            mcpFail("maxUsageCount must be >= 1.")
        }
        if (request.timeoutMillis < 1) {
            mcpFail("timeoutMillis must be >= 1.")
        }
    }

    private fun validateRegexMode(findModel: FindModel, request: ScopeTextSearchRequestDto) {
        if (request.mode != ScopeTextQueryMode.REGEX) {
            return
        }
        val pattern = findModel.compileRegExp() ?: mcpFail("Invalid regex pattern.")
        if (!request.allowEmptyMatches && pattern.matcher("").find()) {
            mcpFail("Regex pattern may match empty strings. Set allowEmptyMatches=true to override.")
        }
    }

    private fun buildSearchRequest(
        query: String,
        mode: ScopeTextQueryMode,
        scope: ScopeProgramDescriptorDto,
        caseSensitive: Boolean,
        wholeWordsOnly: Boolean,
        searchContext: ScopeTextSearchContextDto,
        fileMask: String?,
        allowUiInteractiveScopes: Boolean,
        maxUsageCount: Int,
        timeoutMillis: Int,
        allowEmptyMatches: Boolean = false,
    ): ScopeTextSearchRequestDto {
        return ScopeTextSearchRequestDto(
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

        val occurrenceId = shortHash(
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
            mcpFail("Some occurrenceIds were not found in current search result: $preview")
        }
        return SelectedOccurrences(
            selected = selected,
            missingOccurrenceIds = missing,
        )
    }

    private suspend fun computeReplacementTexts(
        occurrences: List<RawOccurrence>,
        replaceModel: FindModel,
    ): Map<String, String> {
        if (occurrences.isEmpty()) {
            return emptyMap()
        }
        val project = currentCoroutineContext().project
        val findManager = FindManager.getInstance(project)
        val result = linkedMapOf<String, String>()
        occurrences.forEach { occurrence ->
            val dto = occurrence.dto
            val replacementText = readAction {
                val document = FileDocumentManager.getInstance().getDocument(occurrence.file)
                    ?: mcpFail("No text document available for file '${dto.fileUrl}'.")
                val currentMatched = ensureOccurrenceStillMatches(document, dto)
                try {
                    findManager.getStringToReplace(
                        currentMatched,
                        replaceModel,
                        dto.startOffset,
                        document.text,
                    )
                } catch (error: FindManager.MalformedReplacementStringException) {
                    mcpFail("Malformed replacement string: ${error.message ?: "unknown error"}")
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
            mcpFail(
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
            mcpFail(
                "Occurrence range [$startOffset, $endOffset) is out of bounds for '$fileUrl' " +
                    "(textLength=${document.textLength}).",
            )
        }
    }

    private fun shortHash(text: String): String {
        return sha256ShortHash(text, length = 16)
    }

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
}
