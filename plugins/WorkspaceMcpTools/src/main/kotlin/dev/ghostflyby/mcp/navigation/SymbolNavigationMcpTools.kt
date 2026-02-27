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

package dev.ghostflyby.mcp.navigation

import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.common.AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX
import dev.ghostflyby.mcp.common.MCP_FIRST_LIBRARY_QUERY_POLICY_DESCRIPTION_SUFFIX
import dev.ghostflyby.mcp.common.VFS_URL_PARAM_DESCRIPTION
import dev.ghostflyby.mcp.common.batchTry
import dev.ghostflyby.mcp.common.reportActivity
import com.intellij.lang.LanguageDocumentation
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.util.SymbolInfo
import com.intellij.mcpserver.util.convertHtmlToMarkdown
import com.intellij.mcpserver.util.getElementSymbolInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.util.DocumentUtil
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Suppress("FunctionName")
internal class SymbolNavigationMcpTools : McpToolset {
    private val vfsManager get() = service<VirtualFileManager>()

    private companion object {
        private const val DEFAULT_IMPLEMENTATION_LIMIT = 20
        private const val DEFAULT_REFERENCE_LIMIT = 50
        private const val TYPE_SCAN_MAX_DEPTH = 5
        private const val TYPE_SCAN_MAX_NODES = 512
    }

    @Serializable
    class NavigationResult(
        val targetVirtualFileUri: String,
        val row: Int,
        val column: Int,
    )

    @Serializable
    class NavigationResults(
        val items: List<NavigationResult>,
        val diagnostics: List<String> = emptyList(),
    )

    @Serializable
    class NavigationSourcePosition(
        val uri: String,
        val row: Int,
        val column: Int,
    )

    @Serializable
    class NavigationBatchSingleItem(
        val input: NavigationSourcePosition,
        val result: NavigationResult? = null,
        val error: String? = null,
    )

    @Serializable
    class NavigationBatchMultiItem(
        val input: NavigationSourcePosition,
        val result: NavigationResults? = null,
        val error: String? = null,
    )

    @Serializable
    class NavigationBatchSingleResult(
        val items: List<NavigationBatchSingleItem>,
        val successCount: Int,
        val failureCount: Int,
    )

    @Serializable
    class NavigationBatchMultiResult(
        val items: List<NavigationBatchMultiItem>,
        val successCount: Int,
        val failureCount: Int,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    class NavigationSymbolInfoResult(
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val symbolInfo: SymbolInfo? = null,
        val documentation: String,
    )

    @Serializable
    class NavigationSymbolInfoPosition(
        val uri: String,
        val row: Int,
        val column: Int,
    )

    @Serializable
    class NavigationSymbolInfoAutoPositionInput(
        val row: Int? = null,
        val column: Int? = null,
        val offset: Int? = null,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    class NavigationSymbolInfoResolvedResult(
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val symbolInfo: SymbolInfo? = null,
        val documentation: String,
        val row: Int,
        val column: Int,
        val offset: Int,
        val recommendedNextCalls: List<String> = emptyList(),
    )

    @Serializable
    class NavigationBatchSymbolInfoItem(
        val input: NavigationSymbolInfoPosition,
        val result: NavigationSymbolInfoResult? = null,
        val error: String? = null,
    )

    @Serializable
    class NavigationBatchSymbolInfoResult(
        val items: List<NavigationBatchSymbolInfoItem>,
        val successCount: Int,
        val failureCount: Int,
    )

    @McpTool
    @McpDescription(
        "Retrieves symbol declaration and IDE quick documentation markdown " +
            "for the source position (1-based row/column) in the specified VFS URL." +
            MCP_FIRST_LIBRARY_QUERY_POLICY_DESCRIPTION_SUFFIX,
    )
    suspend fun navigation_get_symbol_info(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        uri: String,
        @McpDescription("Source line number (1-based).")
        row: Int,
        @McpDescription("Source column number (1-based).")
        column: Int,
    ): NavigationSymbolInfoResult {
        reportActivity(Bundle.message("tool.activity.navigation.get.symbol.info", uri, row, column))
        val project = currentCoroutineContext().project
        return resolveSymbolInfo(project, uri, row, column)
    }

    @McpTool
    @McpDescription(
        "Retrieves symbol declaration and IDE quick documentation markdown by source offset (0-based) in the specified VFS URL.",
    )
    suspend fun navigation_get_symbol_info_by_offset(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        uri: String,
        @McpDescription("Source offset (0-based).")
        offset: Int,
    ): NavigationSymbolInfoResolvedResult {
        reportActivity(Bundle.message("tool.activity.navigation.get.symbol.info.by.offset", uri, offset))
        val project = currentCoroutineContext().project
        val position = runNavigationRead(project) {
            resolvePositionFromOffset(project, uri, offset)
        }
        val info = resolveSymbolInfo(project, uri, position.row, position.column)
        return NavigationSymbolInfoResolvedResult(
            symbolInfo = info.symbolInfo,
            documentation = info.documentation,
            row = position.row,
            column = position.column,
            offset = position.offset,
            recommendedNextCalls = listOf(
                "navigation_get_symbol_info(uri='${uri}', row=${position.row}, column=${position.column})",
            ),
        )
    }

    @McpTool
    @McpDescription(
        "Retrieves symbol info by either row/column or offset. Exactly one positioning mode must be provided.",
    )
    suspend fun navigation_get_symbol_info_auto_position(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        uri: String,
        input: NavigationSymbolInfoAutoPositionInput,
    ): NavigationSymbolInfoResolvedResult {
        reportActivity(
            Bundle.message(
                "tool.activity.navigation.get.symbol.info.auto.position",
                uri,
                input.row?.toString() ?: "null",
                input.column?.toString() ?: "null",
                input.offset?.toString() ?: "null",
            ),
        )
        val project = currentCoroutineContext().project
        val position = runNavigationRead(project) {
            resolveAutoPosition(project, uri, input)
        }
        val info = resolveSymbolInfo(project, uri, position.row, position.column)
        return NavigationSymbolInfoResolvedResult(
            symbolInfo = info.symbolInfo,
            documentation = info.documentation,
            row = position.row,
            column = position.column,
            offset = position.offset,
            recommendedNextCalls = listOf(
                "navigation_get_symbol_info(uri='${uri}', row=${position.row}, column=${position.column})",
                "navigation_get_symbol_info_by_offset(uri='${uri}', offset=${position.offset})",
            ),
        )
    }

    @McpTool
    @McpDescription(
        "First-call friendly symbol info lookup by URI + row/column with normalized position in response." +
            AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX +
            MCP_FIRST_LIBRARY_QUERY_POLICY_DESCRIPTION_SUFFIX,
    )
    suspend fun navigation_get_symbol_info_quick(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        uri: String,
        @McpDescription("Source line number (1-based).")
        row: Int,
        @McpDescription("Source column number (1-based).")
        column: Int,
    ): NavigationSymbolInfoResolvedResult {
        return navigation_get_symbol_info_auto_position(
            uri = uri,
            input = NavigationSymbolInfoAutoPositionInput(
                row = row,
                column = column,
                offset = null,
            ),
        )
    }

    @McpTool
    @McpDescription("Batch retrieve symbol declaration and IDE quick documentation markdown for source positions.")
    suspend fun navigation_get_symbol_info_batch(
        @McpDescription("Source positions to resolve symbol info from.")
        inputs: List<NavigationSymbolInfoPosition>,
        @McpDescription("Whether to continue collecting results after a single input fails.")
        continueOnError: Boolean = true,
    ): NavigationBatchSymbolInfoResult {
        reportActivity(Bundle.message("tool.activity.navigation.get.symbol.info.batch", inputs.size, continueOnError))
        val project = currentCoroutineContext().project
        val items = mutableListOf<NavigationBatchSymbolInfoItem>()
        var successCount = 0
        var failureCount = 0
        for (input in inputs) {
            val output = batchTry(continueOnError) {
                resolveSymbolInfo(project, input.uri, input.row, input.column)
            }
            if (output.error == null) {
                successCount++
            } else {
                failureCount++
            }
            items += NavigationBatchSymbolInfoItem(
                input = input,
                result = output.value,
                error = output.error,
            )
        }
        return NavigationBatchSymbolInfoResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
    }

    @McpTool
    @McpDescription("Resolve a reference at source position (1-based row/column) to its target declaration location.")
    suspend fun navigation_to_reference(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        uri: String,
        @McpDescription("Source line number (1-based).")
        row: Int,
        @McpDescription("Source column number (1-based).")
        column: Int,
    ): NavigationResult {
        reportActivity(Bundle.message("tool.activity.navigation.to.reference", uri, row, column))
        val project = currentCoroutineContext().project
        return runNavigationRead(project) {
            val context = resolveReferenceContext(project, uri, row, column)
            toNavigationResult(context.resolvedTarget, context.psiDocumentManager)
                ?: mcpFail("Resolved target has no physical text location")
        }
    }

    @McpTool
    @McpDescription(
        "Resolve best-effort type declaration for the symbol at source position (1-based row/column). " +
            "May produce false negatives in some languages/PSI shapes. " +
            "If empty or not found, fallback to navigation_find_references.",
    )
    suspend fun navigation_to_type_definition(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        uri: String,
        @McpDescription("Source line number (1-based).")
        row: Int,
        @McpDescription("Source column number (1-based).")
        column: Int,
    ): NavigationResult {
        reportActivity(Bundle.message("tool.activity.navigation.to.type.definition", uri, row, column))
        val project = currentCoroutineContext().project
        return runNavigationRead(project) {
            val context = resolveReferenceContext(project, uri, row, column)
            val typeTarget = findTypeDefinitionTarget(context.resolvedTarget)
                ?: mcpFail("Type definition not found at $uri:$row:$column")
            toNavigationResult(typeTarget, context.psiDocumentManager)
                ?: mcpFail("Resolved type definition has no physical text location")
        }
    }

    @McpTool
    @McpDescription(
        "Resolve implementations for a reference at source position (1-based row/column). " +
            "Best-effort and may produce false negatives. " +
            "If empty, fallback to navigation_find_references.",
    )
    suspend fun navigation_to_implementation(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        uri: String,
        @McpDescription("Source line number (1-based).")
        row: Int,
        @McpDescription("Source column number (1-based).")
        column: Int,
        @McpDescription("Maximum number of results to return.")
        limit: Int = DEFAULT_IMPLEMENTATION_LIMIT,
        @McpDescription("If true and no implementation is found, fallback to navigation_find_references semantics.")
        fallbackToReferencesWhenEmpty: Boolean = false,
    ): NavigationResults {
        reportActivity(
            Bundle.message(
                "tool.activity.navigation.to.implementation",
                uri,
                row,
                column,
                limit,
                fallbackToReferencesWhenEmpty,
            ),
        )
        val project = currentCoroutineContext().project
        return runNavigationRead(project) {
            validateLimit(limit)
            val context = resolveReferenceContext(project, uri, row, column)
            val searchTarget = context.resolvedTarget.navigationElement
            val primaryItems = collectDefinitions(
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = limit,
                includeTargetWhenEmpty = true,
                excludeOriginalTarget = false,
            )
            fallbackToReferences(
                primaryItems = primaryItems,
                fallbackToReferencesWhenEmpty = fallbackToReferencesWhenEmpty,
                reason = "Implementation search returned no results.",
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = limit,
            )
        }
    }

    @McpTool
    @McpDescription(
        "Find override/implementation declarations for the symbol at source position (1-based row/column). " +
            "Best-effort and may produce false negatives. " +
            "If empty, fallback to navigation_find_references.",
    )
    suspend fun navigation_find_overrides(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        uri: String,
        @McpDescription("Source line number (1-based).")
        row: Int,
        @McpDescription("Source column number (1-based).")
        column: Int,
        @McpDescription("Maximum number of results to return.")
        limit: Int = DEFAULT_IMPLEMENTATION_LIMIT,
        @McpDescription("If true and no override is found, fallback to navigation_find_references semantics.")
        fallbackToReferencesWhenEmpty: Boolean = false,
    ): NavigationResults {
        reportActivity(
            Bundle.message(
                "tool.activity.navigation.find.overrides",
                uri,
                row,
                column,
                limit,
                fallbackToReferencesWhenEmpty,
            ),
        )
        val project = currentCoroutineContext().project
        return runNavigationRead(project) {
            validateLimit(limit)
            val context = resolveReferenceContext(project, uri, row, column)
            val searchTarget = context.resolvedTarget.navigationElement
            val primaryItems = collectDefinitions(
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = limit,
                includeTargetWhenEmpty = false,
                excludeOriginalTarget = true,
            )
            fallbackToReferences(
                primaryItems = primaryItems,
                fallbackToReferencesWhenEmpty = fallbackToReferencesWhenEmpty,
                reason = "Override search returned no results.",
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = limit,
            )
        }
    }

    @McpTool
    @McpDescription(
        "Find inheritor declarations for the type symbol at source position (1-based row/column). " +
            "Best-effort and may produce false negatives. " +
            "If empty, fallback to navigation_find_references.",
    )
    suspend fun navigation_find_inheritors(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        uri: String,
        @McpDescription("Source line number (1-based).")
        row: Int,
        @McpDescription("Source column number (1-based).")
        column: Int,
        @McpDescription("Maximum number of results to return.")
        limit: Int = DEFAULT_IMPLEMENTATION_LIMIT,
        @McpDescription("If true and no inheritor is found, fallback to navigation_find_references semantics.")
        fallbackToReferencesWhenEmpty: Boolean = false,
    ): NavigationResults {
        reportActivity(
            Bundle.message(
                "tool.activity.navigation.find.inheritors",
                uri,
                row,
                column,
                limit,
                fallbackToReferencesWhenEmpty,
            ),
        )
        val project = currentCoroutineContext().project
        return runNavigationRead(project) {
            validateLimit(limit)
            val context = resolveReferenceContext(project, uri, row, column)
            val typeTarget = findTypeDefinitionTarget(context.resolvedTarget)
                ?: mcpFail("Type declaration not found at $uri:$row:$column")
            val primaryItems = collectDefinitions(
                searchTarget = typeTarget.navigationElement,
                psiDocumentManager = context.psiDocumentManager,
                limit = limit,
                includeTargetWhenEmpty = false,
                excludeOriginalTarget = true,
            )
            fallbackToReferences(
                primaryItems = primaryItems,
                fallbackToReferencesWhenEmpty = fallbackToReferencesWhenEmpty,
                reason = "Inheritor search returned no results.",
                searchTarget = typeTarget.navigationElement,
                psiDocumentManager = context.psiDocumentManager,
                limit = limit,
            )
        }
    }

    @McpTool
    @McpDescription("Find reference usages for the symbol at source position (1-based row/column).")
    suspend fun navigation_find_references(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        uri: String,
        @McpDescription("Source line number (1-based).")
        row: Int,
        @McpDescription("Source column number (1-based).")
        column: Int,
        @McpDescription("Maximum number of results to return.")
        limit: Int = DEFAULT_REFERENCE_LIMIT,
    ): NavigationResults {
        reportActivity(Bundle.message("tool.activity.navigation.find.references", uri, row, column, limit))
        val project = currentCoroutineContext().project
        val items = runNavigationRead(project) {
            validateLimit(limit)
            val context = resolveReferenceContext(project, uri, row, column)
            val searchTarget = context.resolvedTarget.navigationElement
            collectReferences(searchTarget, context.psiDocumentManager, limit)
        }
        return NavigationResults(items = items)
    }

    @McpTool
    @McpDescription(
        "Find caller locations for the symbol at source position (1-based row/column). " +
            "This uses heuristic call-site detection and may produce false negatives. " +
            "If empty, fallback to navigation_find_references.",
    )
    suspend fun navigation_get_callers(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        uri: String,
        @McpDescription("Source line number (1-based).")
        row: Int,
        @McpDescription("Source column number (1-based).")
        column: Int,
        @McpDescription("Maximum number of results to return.")
        limit: Int = DEFAULT_REFERENCE_LIMIT,
        @McpDescription("If true and no caller is found by heuristic filtering, fallback to generic references.")
        fallbackToReferencesWhenEmpty: Boolean = false,
    ): NavigationResults {
        reportActivity(
            Bundle.message(
                "tool.activity.navigation.get.callers",
                uri,
                row,
                column,
                limit,
                fallbackToReferencesWhenEmpty,
            ),
        )
        val project = currentCoroutineContext().project
        return runNavigationRead(project) {
            validateLimit(limit)
            val context = resolveReferenceContext(project, uri, row, column)
            val searchTarget = context.resolvedTarget.navigationElement
            val primaryItems = collectReferences(
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = limit,
                referenceFilter = ::isLikelyCallReference,
            )
            fallbackToReferences(
                primaryItems = primaryItems,
                fallbackToReferencesWhenEmpty = fallbackToReferencesWhenEmpty,
                reason = "Caller heuristic returned no results.",
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = limit,
            )
        }
    }

    @McpTool
    @McpDescription("Batch resolve references for multiple source positions.")
    suspend fun navigation_to_reference_batch(
        @McpDescription("Source positions to resolve.")
        inputs: List<NavigationSourcePosition>,
        @McpDescription("Whether to continue collecting results after a single input fails.")
        continueOnError: Boolean = true,
    ): NavigationBatchSingleResult {
        reportActivity(Bundle.message("tool.activity.navigation.to.reference.batch", inputs.size, continueOnError))
        val project = currentCoroutineContext().project
        val items = mutableListOf<NavigationBatchSingleItem>()
        var successCount = 0
        var failureCount = 0
        for (input in inputs) {
            val output = batchTry(continueOnError) {
                runNavigationRead(project) {
                    val context = resolveReferenceContext(project, input.uri, input.row, input.column)
                    toNavigationResult(context.resolvedTarget, context.psiDocumentManager)
                        ?: mcpFail("Resolved target has no physical text location")
                }
            }
            if (output.error == null) {
                successCount++
            } else {
                failureCount++
            }
            items += NavigationBatchSingleItem(
                input = input,
                result = output.value,
                error = output.error,
            )
        }
        return NavigationBatchSingleResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
    }

    @McpTool
    @McpDescription("Batch find references for multiple source positions.")
    suspend fun navigation_find_references_batch(
        @McpDescription("Source positions to search references from.")
        inputs: List<NavigationSourcePosition>,
        @McpDescription("Maximum number of results to return for each input.")
        limit: Int = DEFAULT_REFERENCE_LIMIT,
        @McpDescription("Whether to continue collecting results after a single input fails.")
        continueOnError: Boolean = true,
    ): NavigationBatchMultiResult {
        reportActivity(Bundle.message("tool.activity.navigation.find.references.batch", inputs.size, limit, continueOnError))
        val project = currentCoroutineContext().project
        val items = mutableListOf<NavigationBatchMultiItem>()
        var successCount = 0
        var failureCount = 0
        for (input in inputs) {
            val output = batchTry(continueOnError) {
                runNavigationRead(project) {
                    validateLimit(limit)
                    val context = resolveReferenceContext(project, input.uri, input.row, input.column)
                    val searchTarget = context.resolvedTarget.navigationElement
                    NavigationResults(
                        items = collectReferences(searchTarget, context.psiDocumentManager, limit),
                    )
                }
            }
            if (output.error == null) {
                successCount++
            } else {
                failureCount++
            }
            items += NavigationBatchMultiItem(
                input = input,
                result = output.value,
                error = output.error,
            )
        }
        return NavigationBatchMultiResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
    }

    private class ResolvedReferenceContext(
        val resolvedTarget: PsiElement,
        val psiDocumentManager: PsiDocumentManager,
    )

    private class SymbolInfoContext(
        val symbolInfo: SymbolInfo?,
        val documentationHtml: String,
    )

    private class ResolvedSourcePosition(
        val row: Int,
        val column: Int,
        val offset: Int,
    )

    private suspend fun resolveSymbolInfo(
        project: Project,
        uri: String,
        row: Int,
        column: Int,
    ): NavigationSymbolInfoResult {
        val context = runNavigationRead(project) {
            readSymbolInfoContext(project, uri, row, column)
        }
        return NavigationSymbolInfoResult(
            symbolInfo = context.symbolInfo,
            documentation = if (context.documentationHtml.isBlank()) {
                ""
            } else {
                convertHtmlToMarkdown(context.documentationHtml)
            },
        )
    }

    private fun resolveAutoPosition(
        project: Project,
        uri: String,
        input: NavigationSymbolInfoAutoPositionInput,
    ): ResolvedSourcePosition {
        assertReadAccess()
        val hasOffset = input.offset != null
        val hasRowColumn = input.row != null || input.column != null
        if (hasOffset && hasRowColumn) {
            mcpFail("Provide either offset or row+column, not both.")
        }
        if (!hasOffset && !hasRowColumn) {
            mcpFail("Provide either offset or row+column.")
        }
        if (hasOffset) {
            val sourceOffset = input.offset
            return resolvePositionFromOffset(project, uri, sourceOffset)
        }
        val row = input.row ?: mcpFail("row is required when offset is not provided.")
        val column = input.column ?: mcpFail("column is required when offset is not provided.")
        val sourceFile = vfsManager.findFileByUrl(uri) ?: mcpFail("File not found for URL: $uri")
        if (sourceFile.isDirectory) {
            mcpFail("URL points to a directory, not a file: $uri")
        }
        val sourcePsiFile = PsiManager.getInstance(project).findFile(sourceFile)
            ?: mcpFail("No PSI file available for URL: $uri${vfsReadHint(sourceFile)}")
        val sourceDocument = PsiDocumentManager.getInstance(project).getLastCommittedDocument(sourcePsiFile)
            ?: mcpFail("No committed text document available for URL: $uri. Commit pending changes and retry.${vfsReadHint(sourceFile)}")
        val offset = resolveSourceOffset(sourceDocument, row, column)
        return ResolvedSourcePosition(
            row = row,
            column = column,
            offset = offset,
        )
    }

    private fun resolvePositionFromOffset(
        project: Project,
        uri: String,
        offset: Int,
    ): ResolvedSourcePosition {
        assertReadAccess()
        if (offset < 0) {
            mcpFail("offset must be >= 0")
        }
        val sourceFile = vfsManager.findFileByUrl(uri) ?: mcpFail("File not found for URL: $uri")
        if (sourceFile.isDirectory) {
            mcpFail("URL points to a directory, not a file: $uri")
        }
        val sourcePsiFile = PsiManager.getInstance(project).findFile(sourceFile)
            ?: mcpFail("No PSI file available for URL: $uri${vfsReadHint(sourceFile)}")
        val sourceDocument = PsiDocumentManager.getInstance(project).getLastCommittedDocument(sourcePsiFile)
            ?: mcpFail("No committed text document available for URL: $uri. Commit pending changes and retry.${vfsReadHint(sourceFile)}")
        if (sourceDocument.textLength == 0) {
            return ResolvedSourcePosition(
                row = 1,
                column = 1,
                offset = 0,
            )
        }
        if (offset >= sourceDocument.textLength) {
            mcpFail("offset must be in [0, ${sourceDocument.textLength - 1}] for URL: $uri")
        }
        val row = sourceDocument.getLineNumber(offset) + 1
        val lineStart = sourceDocument.getLineStartOffset(row - 1)
        val column = (offset - lineStart) + 1
        return ResolvedSourcePosition(
            row = row,
            column = column,
            offset = offset,
        )
    }

    private fun resolveReferenceContext(
        project: Project,
        uri: String,
        row: Int,
        column: Int,
    ): ResolvedReferenceContext {
        assertReadAccess()
        validatePosition(row, column)
        val sourceFile = vfsManager.findFileByUrl(uri) ?: mcpFail("File not found for URL: $uri")
        if (sourceFile.isDirectory) mcpFail("URL points to a directory, not a file: $uri")

        val psiManager = PsiManager.getInstance(project)
        val sourcePsiFile = psiManager.findFile(sourceFile)
            ?: mcpFail("No PSI file available for URL: $uri${vfsReadHint(sourceFile)}")
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val sourceDocument = psiDocumentManager.getLastCommittedDocument(sourcePsiFile)
            ?: mcpFail("No committed text document available for URL: $uri. Commit pending changes and retry.${vfsReadHint(sourceFile)}")

        if (row > sourceDocument.lineCount) {
            mcpFail("row must be in [1, ${sourceDocument.lineCount}], but was $row")
        }
        val lineStartOffset = sourceDocument.getLineStartOffset(row - 1)
        val lineEndOffset = sourceDocument.getLineEndOffset(row - 1)
        val maxColumn = (lineEndOffset - lineStartOffset) + 1
        if (column > maxColumn) {
            mcpFail("column must be in [1, $maxColumn] for row $row, but was $column")
        }
        val sourceOffset = lineStartOffset + column - 1

        val reference = findReferenceAt(sourcePsiFile, sourceOffset, lineStartOffset)
            ?: mcpFail("No reference found at $uri:$row:$column${vfsReadHint(sourceFile)}")
        val resolvedTarget = reference.resolve()
            ?: mcpFail("Reference at $uri:$row:$column cannot be resolved")
        return ResolvedReferenceContext(
            resolvedTarget = resolvedTarget,
            psiDocumentManager = psiDocumentManager,
        )
    }

    private fun readSymbolInfoContext(
        project: Project,
        uri: String,
        row: Int,
        column: Int,
    ): SymbolInfoContext {
        assertReadAccess()
        validatePosition(row, column)
        val sourceFile = vfsManager.findFileByUrl(uri) ?: mcpFail("File not found for URL: $uri")
        if (sourceFile.isDirectory) {
            mcpFail("URL points to a directory, not a file: $uri")
        }
        val sourcePsiFile = PsiManager.getInstance(project).findFile(sourceFile)
            ?: mcpFail("No PSI file available for URL: $uri${vfsReadHint(sourceFile)}")
        val sourceDocument = PsiDocumentManager.getInstance(project).getLastCommittedDocument(sourcePsiFile)
            ?: mcpFail("No committed text document available for URL: $uri. Commit pending changes and retry.${vfsReadHint(sourceFile)}")
        val sourceOffset = resolveSourceOffset(sourceDocument, row, column)
        val lineStartOffset = sourceDocument.getLineStartOffset(row - 1)
        val sourceElement = findElementAt(sourcePsiFile, sourceOffset, lineStartOffset)
        val reference = findReferenceAt(sourcePsiFile, sourceOffset, lineStartOffset)
        val resolvedReference = reference?.resolve()
        val symbolInfo = resolvedReference?.let { getElementSymbolInfo(it, extraLines = 1) }
        val documentationTarget = resolvedReference ?: sourceElement
        val documentationHtml = generateDocumentationHtml(
            targetElement = documentationTarget,
            originalElement = sourceElement,
        )
        return SymbolInfoContext(
            symbolInfo = symbolInfo,
            documentationHtml = documentationHtml,
        )
    }

    private fun resolveSourceOffset(
        sourceDocument: Document,
        row: Int,
        column: Int,
    ): Int {
        val lineIndex = row - 1
        if (!DocumentUtil.isValidLine(lineIndex, sourceDocument)) {
            mcpFail("row must be in [1, ${sourceDocument.lineCount}], but was $row")
        }
        val lineStartOffset = sourceDocument.getLineStartOffset(lineIndex)
        val lineEndOffset = sourceDocument.getLineEndOffset(lineIndex)
        val maxColumn = (lineEndOffset - lineStartOffset) + 1
        if (column > maxColumn) {
            mcpFail("column must be in [1, $maxColumn] for row $row, but was $column")
        }
        val sourceOffset = lineStartOffset + column - 1
        if (!DocumentUtil.isValidOffset(sourceOffset, sourceDocument)) {
            mcpFail(
                "Line and column $row:$column(offset=$sourceOffset) is out of bounds " +
                    "(file has ${sourceDocument.textLength} characters)",
            )
        }
        return sourceOffset
    }

    private fun generateDocumentationHtml(
        targetElement: PsiElement?,
        originalElement: PsiElement?,
    ): String {
        targetElement ?: return ""
        val provider = LanguageDocumentation.INSTANCE.forLanguage(targetElement.language) ?: return ""
        return provider.generateDoc(targetElement, originalElement)
            ?: provider.generateHoverDoc(targetElement, originalElement)
            ?: ""
    }

    private fun vfsReadHint(sourceFile: com.intellij.openapi.vfs.VirtualFile): String {
        return if (sourceFile.fileSystem.protocol == "jar") {
            ". For JAR/ZIP virtual files, prefer vfs_read_file* tools."
        } else {
            ""
        }
    }

    private fun findReferenceAt(sourcePsiFile: PsiFile, sourceOffset: Int, lineStartOffset: Int): PsiReference? {
        assertReadAccess()
        val primaryReference = sourcePsiFile.findReferenceAt(sourceOffset)
        if (primaryReference != null) return primaryReference
        return if (sourceOffset > lineStartOffset) sourcePsiFile.findReferenceAt(sourceOffset - 1) else null
    }

    private fun findElementAt(sourcePsiFile: PsiFile, sourceOffset: Int, lineStartOffset: Int): PsiElement? {
        assertReadAccess()
        val primaryElement = sourcePsiFile.findElementAt(sourceOffset)
        if (primaryElement != null) return primaryElement
        return if (sourceOffset > lineStartOffset) sourcePsiFile.findElementAt(sourceOffset - 1) else null
    }

    private fun toNavigationResult(element: PsiElement, psiDocumentManager: PsiDocumentManager): NavigationResult? {
        assertReadAccess()
        val navigationElement = element.navigationElement
        val targetPsiFile = navigationElement.containingFile ?: return null
        val targetFile = targetPsiFile.virtualFile ?: return null
        val targetDocument = psiDocumentManager.getLastCommittedDocument(targetPsiFile)
            ?: return null
        val textLength = targetDocument.textLength
        if (textLength <= 0) {
            return NavigationResult(
                targetVirtualFileUri = targetFile.url,
                row = 1,
                column = 1,
            )
        }
        val targetOffset = navigationElement.textOffset.coerceIn(0, textLength - 1)
        val targetLine = targetDocument.getLineNumber(targetOffset) + 1
        val targetLineStartOffset = targetDocument.getLineStartOffset(targetLine - 1)
        val targetColumn = (targetOffset - targetLineStartOffset) + 1
        return NavigationResult(
            targetVirtualFileUri = targetFile.url,
            row = targetLine,
            column = targetColumn,
        )
    }

    private fun isLikelyCallReference(reference: PsiReference): Boolean {
        return generateSequence(reference.element) { it.parent }
            .take(8)
            .map { it.javaClass.simpleName }
            .any { className ->
                className.contains("Call", ignoreCase = true) ||
                    className.contains("Invocation", ignoreCase = true) ||
                    className.contains("NewExpression", ignoreCase = true) ||
                    className.contains("Constructor", ignoreCase = true)
            }
    }

    private fun findTypeDefinitionTarget(resolvedTarget: PsiElement): PsiElement? {
        val normalizedTarget = resolvedTarget.navigationElement
        if (isLikelyTypeDeclaration(normalizedTarget)) return normalizedTarget
        findReferencedTypeDeclaration(normalizedTarget)?.let { return it }
        return generateSequence(normalizedTarget) { it.parent }
            .drop(1)
            .map { it.navigationElement }
            .firstOrNull(::isLikelyTypeDeclaration)
    }

    private fun findReferencedTypeDeclaration(root: PsiElement): PsiElement? {
        val queue = ArrayDeque<Pair<PsiElement, Int>>()
        queue.add(root to 0)
        val normalizedRoot = root.navigationElement
        var visited = 0

        while (queue.isNotEmpty() && visited < TYPE_SCAN_MAX_NODES) {
            val (current, depth) = queue.removeFirst()
            visited++
            if (current !== root) {
                current.references.asSequence()
                    .mapNotNull { it.resolve()?.navigationElement }
                    .firstOrNull { candidate ->
                        !candidate.isEquivalentTo(normalizedRoot) && isLikelyTypeDeclaration(candidate)
                    }
                    ?.let { return it }
            }
            if (depth >= TYPE_SCAN_MAX_DEPTH) continue
            var child = current.firstChild
            while (child != null) {
                queue.add(child to depth + 1)
                child = child.nextSibling
            }
        }
        return null
    }

    private fun isLikelyTypeDeclaration(element: PsiElement): Boolean {
        val className = element.javaClass.simpleName
        return className.contains("Class", ignoreCase = true) ||
            className.contains("Interface", ignoreCase = true) ||
            className.contains("Enum", ignoreCase = true) ||
            className.contains("Record", ignoreCase = true) ||
            className.contains("Object", ignoreCase = true) ||
            className.contains("TypeAlias", ignoreCase = true) ||
            className.contains("Struct", ignoreCase = true) ||
            className.contains("Trait", ignoreCase = true)
    }

    private fun collectDefinitions(
        searchTarget: PsiElement,
        psiDocumentManager: PsiDocumentManager,
        limit: Int,
        includeTargetWhenEmpty: Boolean,
        excludeOriginalTarget: Boolean,
    ): List<NavigationResult> {
        val unique = LinkedHashMap<String, NavigationResult>()
        val originalKey = toNavigationResult(searchTarget, psiDocumentManager)?.let(::toResultKey)
        var hasDefinitions = false
        DefinitionsScopedSearch.search(searchTarget).forEach(Processor { candidate ->
            hasDefinitions = true
            val result = toNavigationResult(candidate, psiDocumentManager) ?: return@Processor true
            if (excludeOriginalTarget && originalKey != null && toResultKey(result) == originalKey) {
                return@Processor true
            }
            addUniqueResult(unique, result)
            unique.size < limit
        })
        if (!hasDefinitions && includeTargetWhenEmpty) {
            toNavigationResult(searchTarget, psiDocumentManager)?.let { addUniqueResult(unique, it) }
        }
        return unique.values.toList()
    }

    private fun collectReferences(
        searchTarget: PsiElement,
        psiDocumentManager: PsiDocumentManager,
        limit: Int,
        referenceFilter: (PsiReference) -> Boolean = { true },
    ): List<NavigationResult> {
        val unique = LinkedHashMap<String, NavigationResult>()
        ReferencesSearch.search(searchTarget).forEach(Processor { reference ->
            if (!referenceFilter(reference)) {
                return@Processor true
            }
            toNavigationResult(reference.element, psiDocumentManager)?.let { addUniqueResult(unique, it) }
            unique.size < limit
        })
        return unique.values.toList()
    }

    private fun fallbackToReferences(
        primaryItems: List<NavigationResult>,
        fallbackToReferencesWhenEmpty: Boolean,
        reason: String,
        searchTarget: PsiElement,
        psiDocumentManager: PsiDocumentManager,
        limit: Int,
    ): NavigationResults {
        if (primaryItems.isNotEmpty()) {
            return NavigationResults(items = primaryItems)
        }
        if (!fallbackToReferencesWhenEmpty) {
            return NavigationResults(items = emptyList())
        }
        val fallbackItems = collectReferences(
            searchTarget = searchTarget,
            psiDocumentManager = psiDocumentManager,
            limit = limit,
        )
        val diagnostics = if (fallbackItems.isEmpty()) {
            listOf("$reason Fallback to references also returned no results.")
        } else {
            listOf("$reason Fallback to references was applied.")
        }
        return NavigationResults(
            items = fallbackItems,
            diagnostics = diagnostics,
        )
    }

    private fun addUniqueResult(unique: LinkedHashMap<String, NavigationResult>, result: NavigationResult) {
        val key = toResultKey(result)
        unique.putIfAbsent(key, result)
    }

    private fun toResultKey(result: NavigationResult): String {
        return "${result.targetVirtualFileUri}:${result.row}:${result.column}"
    }

    private suspend fun <T> runNavigationRead(project: Project, action: () -> T): T {
        ensureIndicesReady(project)
        return try {
            readAction { action() }
        } catch (_: IndexNotReadyException) {
            mcpFail("Code navigation is temporarily unavailable while indexes are updating. Please retry.")
        }
    }

    private fun ensureIndicesReady(project: Project) {
        if (DumbService.isDumb(project)) {
            mcpFail("Code navigation is unavailable while indexing is in progress. Please retry after indexing completes.")
        }
    }

    private fun assertReadAccess() {
        if (!ApplicationManager.getApplication().isReadAccessAllowed) {
            mcpFail("Internal error: PSI/VFS read attempted outside read action.")
        }
    }

    private fun validatePosition(row: Int, column: Int) {
        if (row < 1) mcpFail("row must be >= 1")
        if (column < 1) mcpFail("column must be >= 1")
    }

    private fun validateLimit(limit: Int) {
        if (limit < 1) mcpFail("limit must be >= 1")
    }
}
