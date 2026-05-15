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

package dev.ghostflyby.mcp.navigation.tools

import com.intellij.lang.LanguageDocumentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.DocumentUtil
import com.intellij.util.Processor
import dev.ghostflyby.mcp.common.VFS_URL_PARAM_DESCRIPTION
import dev.ghostflyby.mcp.common.batchTry
import dev.ghostflyby.mcp.common.isLikelyTypeDeclarationClassName
import dev.ghostflyby.mcp.common.reportActivity
import dev.ghostflyby.mcp.navigation.NavigationBatchMultiItem
import dev.ghostflyby.mcp.navigation.NavigationBatchMultiResult
import dev.ghostflyby.mcp.navigation.NavigationBatchSingleItem
import dev.ghostflyby.mcp.navigation.NavigationBatchSingleResult
import dev.ghostflyby.mcp.navigation.NavigationBatchSymbolInfoItem
import dev.ghostflyby.mcp.navigation.NavigationBatchSymbolInfoResult
import dev.ghostflyby.mcp.navigation.NavigationResult
import dev.ghostflyby.mcp.navigation.NavigationResults
import dev.ghostflyby.mcp.navigation.NavigationSourcePosition
import dev.ghostflyby.mcp.navigation.NavigationSymbolInfoAutoPositionInput
import dev.ghostflyby.mcp.navigation.NavigationSymbolInfoPosition
import dev.ghostflyby.mcp.navigation.NavigationSymbolInfoResolvedResult
import dev.ghostflyby.mcp.navigation.NavigationSymbolInfoResult
import dev.ghostflyby.mcp.resource.WorkspaceResourceException
import dev.ghostflyby.mcp.sdk.tools.SdkToolDescriptor
import dev.ghostflyby.mcp.sdk.tools.SdkToolHandlerContext
import dev.ghostflyby.mcp.sdk.tools.WorkspaceMcpProjectToolArguments
import dev.ghostflyby.mcp.sdk.tools.sdkArrayProperty
import dev.ghostflyby.mcp.sdk.tools.sdkBooleanProperty
import dev.ghostflyby.mcp.sdk.tools.sdkIntegerProperty
import dev.ghostflyby.mcp.sdk.tools.sdkObjectProperty
import dev.ghostflyby.mcp.sdk.tools.sdkStringProperty
import dev.ghostflyby.mcp.sdk.tools.sdkToolDescriptor
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import dev.ghostflyby.mcp.sdk.tools.toolSchema
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

// ---------------------------------------------------------------------------
// Argument DTOs — each tool gets a typed args class implementing
// WorkspaceMcpProjectToolArguments for project resolution.
// ---------------------------------------------------------------------------

@Serializable
internal data class NavigationSymbolInfoArgs(
    val uri: String,
    val row: Int,
    val column: Int,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Serializable
internal data class NavigationSymbolInfoByOffsetArgs(
    val uri: String,
    val offset: Int,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Serializable
internal data class NavigationSymbolInfoAutoPositionArgs(
    val uri: String,
    val input: NavigationSymbolInfoAutoPositionInput,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Serializable
internal data class NavigationSymbolInfoBatchArgs(
    val inputs: List<NavigationSymbolInfoPosition>,
    val continueOnError: Boolean = true,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Serializable
internal data class NavigationSourcePositionArgs(
    val uri: String,
    val row: Int,
    val column: Int,
    val limit: Int = 50,
    val fallbackToReferencesWhenEmpty: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Serializable
internal data class NavigationFindReferencesArgs(
    val uri: String,
    val row: Int,
    val column: Int,
    val limit: Int = 50,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Serializable
internal data class NavigationToReferenceBatchArgs(
    val inputs: List<NavigationSourcePosition>,
    val continueOnError: Boolean = true,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Serializable
internal data class NavigationFindReferencesBatchArgs(
    val inputs: List<NavigationSourcePosition>,
    val limit: Int = 50,
    val continueOnError: Boolean = true,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

// ---------------------------------------------------------------------------
// Tool factories (14 total)
// ---------------------------------------------------------------------------

internal fun navigationGetSymbolInfoSdkTool(): SdkToolDescriptor<NavigationSymbolInfoArgs> {
    return sdkToolDescriptor<NavigationSymbolInfoArgs>(
        name = "navigation_get_symbol_info",
        description = "Retrieves symbol declaration and IDE quick documentation markdown " +
            "for the source position (1-based row/column) in the specified VFS URL.",
        inputSchema = toolSchema(
            properties = mapOf(
                "uri" to sdkStringProperty(VFS_URL_PARAM_DESCRIPTION),
                "row" to sdkIntegerProperty("Source line number (1-based)."),
                "column" to sdkIntegerProperty("Source column number (1-based)."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("uri", "row", "column"),
        ),
        handler = { args -> navigationGetSymbolInfoHandler(this, args) },
    )
}

internal fun navigationGetSymbolInfoByOffsetSdkTool(): SdkToolDescriptor<NavigationSymbolInfoByOffsetArgs> {
    return sdkToolDescriptor<NavigationSymbolInfoByOffsetArgs>(
        name = "navigation_get_symbol_info_by_offset",
        description = "Retrieves symbol declaration and IDE quick documentation markdown by source offset (0-based) in the specified VFS URL.",
        inputSchema = toolSchema(
            properties = mapOf(
                "uri" to sdkStringProperty(VFS_URL_PARAM_DESCRIPTION),
                "offset" to sdkIntegerProperty("Source offset (0-based)."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("uri", "offset"),
        ),
        handler = { args -> navigationGetSymbolInfoByOffsetHandler(this, args) },
    )
}

internal fun navigationGetSymbolInfoAutoPositionSdkTool(): SdkToolDescriptor<NavigationSymbolInfoAutoPositionArgs> {
    return sdkToolDescriptor<NavigationSymbolInfoAutoPositionArgs>(
        name = "navigation_get_symbol_info_auto_position",
        description = "Retrieves symbol info by either row/column or offset. Exactly one positioning mode must be provided.",
        inputSchema = toolSchema(
            properties = mapOf(
                "uri" to sdkStringProperty(VFS_URL_PARAM_DESCRIPTION),
                "input" to sdkObjectProperty("Positioning input with optional row, column, or offset fields."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("uri", "input"),
        ),
        handler = { args -> navigationGetSymbolInfoAutoPositionHandler(this, args) },
    )
}

internal fun navigationGetSymbolInfoQuickSdkTool(): SdkToolDescriptor<NavigationSymbolInfoArgs> {
    return sdkToolDescriptor<NavigationSymbolInfoArgs>(
        name = "navigation_get_symbol_info_quick",
        description = "First-call friendly symbol info lookup by URI + row/column with normalized position in response.",
        inputSchema = toolSchema(
            properties = mapOf(
                "uri" to sdkStringProperty(VFS_URL_PARAM_DESCRIPTION),
                "row" to sdkIntegerProperty("Source line number (1-based)."),
                "column" to sdkIntegerProperty("Source column number (1-based)."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("uri", "row", "column"),
        ),
        handler = { args -> navigationGetSymbolInfoQuickHandler(this, args) },
    )
}

internal fun navigationGetSymbolInfoBatchSdkTool(): SdkToolDescriptor<NavigationSymbolInfoBatchArgs> {
    return sdkToolDescriptor<NavigationSymbolInfoBatchArgs>(
        name = "navigation_get_symbol_info_batch",
        description = "Batch retrieve symbol declaration and IDE quick documentation markdown for source positions.",
        inputSchema = toolSchema(
            properties = mapOf(
                "inputs" to sdkArrayProperty("Source positions to resolve symbol info from."),
                "continueOnError" to sdkBooleanProperty("Whether to continue collecting results after a single input fails."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("inputs"),
        ),
        handler = { args -> navigationGetSymbolInfoBatchHandler(this, args) },
    )
}

internal fun navigationToReferenceSdkTool(): SdkToolDescriptor<NavigationSourcePositionArgs> {
    return sdkToolDescriptor<NavigationSourcePositionArgs>(
        name = "navigation_to_reference",
        description = "Resolve a reference at source position (1-based row/column) to its target declaration location.",
        inputSchema = toolSchema(
            properties = mapOf(
                "uri" to sdkStringProperty(VFS_URL_PARAM_DESCRIPTION),
                "row" to sdkIntegerProperty("Source line number (1-based)."),
                "column" to sdkIntegerProperty("Source column number (1-based)."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("uri", "row", "column"),
        ),
        handler = { args -> navigationToReferenceHandler(this, args) },
    )
}

internal fun navigationToTypeDefinitionSdkTool(): SdkToolDescriptor<NavigationSourcePositionArgs> {
    return sdkToolDescriptor<NavigationSourcePositionArgs>(
        name = "navigation_to_type_definition",
        description = "Resolve best-effort type declaration for the symbol at source position (1-based row/column). " +
            "May produce false negatives in some languages/PSI shapes.",
        inputSchema = toolSchema(
            properties = mapOf(
                "uri" to sdkStringProperty(VFS_URL_PARAM_DESCRIPTION),
                "row" to sdkIntegerProperty("Source line number (1-based)."),
                "column" to sdkIntegerProperty("Source column number (1-based)."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("uri", "row", "column"),
        ),
        handler = { args -> navigationToTypeDefinitionHandler(this, args) },
    )
}

internal fun navigationToImplementationSdkTool(): SdkToolDescriptor<NavigationSourcePositionArgs> {
    return sdkToolDescriptor<NavigationSourcePositionArgs>(
        name = "navigation_to_implementation",
        description = "Resolve implementations for a reference at source position (1-based row/column).",
        inputSchema = toolSchema(
            properties = mapOf(
                "uri" to sdkStringProperty(VFS_URL_PARAM_DESCRIPTION),
                "row" to sdkIntegerProperty("Source line number (1-based)."),
                "column" to sdkIntegerProperty("Source column number (1-based)."),
                "limit" to sdkIntegerProperty("Maximum number of results to return (default 20)."),
                "fallbackToReferencesWhenEmpty" to sdkBooleanProperty(
                    "If true and no implementation is found, fallback to navigation_find_references semantics.",
                ),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("uri", "row", "column"),
        ),
        handler = { args -> navigationToImplementationHandler(this, args) },
    )
}

internal fun navigationFindOverridesSdkTool(): SdkToolDescriptor<NavigationSourcePositionArgs> {
    return sdkToolDescriptor<NavigationSourcePositionArgs>(
        name = "navigation_find_overrides",
        description = "Find override/implementation declarations for the symbol at source position (1-based row/column).",
        inputSchema = toolSchema(
            properties = mapOf(
                "uri" to sdkStringProperty(VFS_URL_PARAM_DESCRIPTION),
                "row" to sdkIntegerProperty("Source line number (1-based)."),
                "column" to sdkIntegerProperty("Source column number (1-based)."),
                "limit" to sdkIntegerProperty("Maximum number of results to return (default 20)."),
                "fallbackToReferencesWhenEmpty" to sdkBooleanProperty(
                    "If true and no override is found, fallback to navigation_find_references semantics.",
                ),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("uri", "row", "column"),
        ),
        handler = { args -> navigationFindOverridesHandler(this, args) },
    )
}

internal fun navigationFindInheritorsSdkTool(): SdkToolDescriptor<NavigationSourcePositionArgs> {
    return sdkToolDescriptor<NavigationSourcePositionArgs>(
        name = "navigation_find_inheritors",
        description = "Find inheritor declarations for the type symbol at source position (1-based row/column).",
        inputSchema = toolSchema(
            properties = mapOf(
                "uri" to sdkStringProperty(VFS_URL_PARAM_DESCRIPTION),
                "row" to sdkIntegerProperty("Source line number (1-based)."),
                "column" to sdkIntegerProperty("Source column number (1-based)."),
                "limit" to sdkIntegerProperty("Maximum number of results to return (default 20)."),
                "fallbackToReferencesWhenEmpty" to sdkBooleanProperty(
                    "If true and no inheritor is found, fallback to navigation_find_references semantics.",
                ),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("uri", "row", "column"),
        ),
        handler = { args -> navigationFindInheritorsHandler(this, args) },
    )
}

internal fun navigationFindReferencesSdkTool(): SdkToolDescriptor<NavigationFindReferencesArgs> {
    return sdkToolDescriptor<NavigationFindReferencesArgs>(
        name = "navigation_find_references",
        description = "Find reference usages for the symbol at source position (1-based row/column).",
        inputSchema = toolSchema(
            properties = mapOf(
                "uri" to sdkStringProperty(VFS_URL_PARAM_DESCRIPTION),
                "row" to sdkIntegerProperty("Source line number (1-based)."),
                "column" to sdkIntegerProperty("Source column number (1-based)."),
                "limit" to sdkIntegerProperty("Maximum number of results to return (default 50)."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("uri", "row", "column"),
        ),
        handler = { args -> navigationFindReferencesHandler(this, args) },
    )
}

internal fun navigationGetCallersSdkTool(): SdkToolDescriptor<NavigationSourcePositionArgs> {
    return sdkToolDescriptor<NavigationSourcePositionArgs>(
        name = "navigation_get_callers",
        description = "Find caller references for the symbol at source position (1-based row/column). " +
            "Uses heuristic filtering to prefer call/invocation references.",
        inputSchema = toolSchema(
            properties = mapOf(
                "uri" to sdkStringProperty(VFS_URL_PARAM_DESCRIPTION),
                "row" to sdkIntegerProperty("Source line number (1-based)."),
                "column" to sdkIntegerProperty("Source column number (1-based)."),
                "limit" to sdkIntegerProperty("Maximum number of results to return (default 50)."),
                "fallbackToReferencesWhenEmpty" to sdkBooleanProperty(
                    "If true and no caller is found by heuristic filtering, fallback to generic references.",
                ),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("uri", "row", "column"),
        ),
        handler = { args -> navigationGetCallersHandler(this, args) },
    )
}

internal fun navigationToReferenceBatchSdkTool(): SdkToolDescriptor<NavigationToReferenceBatchArgs> {
    return sdkToolDescriptor<NavigationToReferenceBatchArgs>(
        name = "navigation_to_reference_batch",
        description = "Batch resolve references for multiple source positions.",
        inputSchema = toolSchema(
            properties = mapOf(
                "inputs" to sdkArrayProperty("Source positions to resolve."),
                "continueOnError" to sdkBooleanProperty("Whether to continue collecting results after a single input fails."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("inputs"),
        ),
        handler = { args -> navigationToReferenceBatchHandler(this, args) },
    )
}

internal fun navigationFindReferencesBatchSdkTool(): SdkToolDescriptor<NavigationFindReferencesBatchArgs> {
    return sdkToolDescriptor<NavigationFindReferencesBatchArgs>(
        name = "navigation_find_references_batch",
        description = "Batch find references for multiple source positions.",
        inputSchema = toolSchema(
            properties = mapOf(
                "inputs" to sdkArrayProperty("Source positions to search references from."),
                "limit" to sdkIntegerProperty("Maximum number of results to return for each input (default 50)."),
                "continueOnError" to sdkBooleanProperty("Whether to continue collecting results after a single input fails."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("inputs"),
        ),
        handler = { args -> navigationFindReferencesBatchHandler(this, args) },
    )
}

// ---------------------------------------------------------------------------
// Handlers — 14 private implementations
// ---------------------------------------------------------------------------

private suspend fun navigationGetSymbolInfoHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationSymbolInfoArgs,
): CallToolResult {
    reportActivity("navigation_get_symbol_info: ${args.uri}:${args.row}:${args.column}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.uri,
    ) { project ->
        val result = resolveSymbolInfo(project, args.uri, args.row, args.column)
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationGetSymbolInfoByOffsetHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationSymbolInfoByOffsetArgs,
): CallToolResult {
    reportActivity("navigation_get_symbol_info_by_offset: ${args.uri} offset=${args.offset}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.uri,
    ) { project ->
        val position = runNavigationRead(project) {
            resolvePositionFromOffset(project, args.uri, args.offset)
        }
        val info = resolveSymbolInfo(project, args.uri, position.row, position.column)
        val result = NavigationSymbolInfoResolvedResult(
            documentation = info.documentation,
            row = position.row,
            column = position.column,
            offset = position.offset,
            recommendedNextCalls = listOf(
                "navigation_get_symbol_info(uri='${args.uri}', row=${position.row}, column=${position.column})",
            ),
        )
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationGetSymbolInfoAutoPositionHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationSymbolInfoAutoPositionArgs,
): CallToolResult {
    reportActivity("navigation_get_symbol_info_auto_position: ${args.uri}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.uri,
    ) { project ->
        val position = runNavigationRead(project) {
            resolveAutoPosition(project, args.uri, args.input)
        }
        val info = resolveSymbolInfo(project, args.uri, position.row, position.column)
        val result = NavigationSymbolInfoResolvedResult(
            documentation = info.documentation,
            row = position.row,
            column = position.column,
            offset = position.offset,
            recommendedNextCalls = listOf(
                "navigation_get_symbol_info(uri='${args.uri}', row=${position.row}, column=${position.column})",
                "navigation_get_symbol_info_by_offset(uri='${args.uri}', offset=${position.offset})",
            ),
        )
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationGetSymbolInfoQuickHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationSymbolInfoArgs,
): CallToolResult {
    reportActivity("navigation_get_symbol_info_quick: ${args.uri}:${args.row}:${args.column}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.uri,
    ) { project ->
        val position = runNavigationRead(project) {
            ResolvedSourcePosition(row = args.row, column = args.column, offset = -1)
        }
        val info = resolveSymbolInfo(project, args.uri, position.row, position.column)
        val sourceDocument = runNavigationRead(project) {
            resolveCommittedSourceDocument(project, args.uri)
        }
        val computedOffset = runNavigationRead(project) {
            resolveSourceOffset(sourceDocument, args.row, args.column)
        }
        val result = NavigationSymbolInfoResolvedResult(
            documentation = info.documentation,
            row = position.row,
            column = position.column,
            offset = computedOffset,
            recommendedNextCalls = listOf(
                "navigation_get_symbol_info(uri='${args.uri}', row=${position.row}, column=${position.column})",
            ),
        )
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationGetSymbolInfoBatchHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationSymbolInfoBatchArgs,
): CallToolResult {
    reportActivity("navigation_get_symbol_info_batch: ${args.inputs.size} inputs, continueOnError=${args.continueOnError}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        val items = mutableListOf<NavigationBatchSymbolInfoItem>()
        var successCount = 0
        var failureCount = 0
        for (input in args.inputs) {
            val output = batchTry(args.continueOnError) {
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
        val result = NavigationBatchSymbolInfoResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationToReferenceHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationSourcePositionArgs,
): CallToolResult {
    reportActivity("navigation_to_reference: ${args.uri}:${args.row}:${args.column}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.uri,
    ) { project ->
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, args.uri, args.row, args.column)
            toNavigationResult(context.resolvedTarget, context.psiDocumentManager)
                ?: throw WorkspaceResourceException("Resolved target has no physical text location")
        }
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationToTypeDefinitionHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationSourcePositionArgs,
): CallToolResult {
    reportActivity("navigation_to_type_definition: ${args.uri}:${args.row}:${args.column}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.uri,
    ) { project ->
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, args.uri, args.row, args.column)
            val typeTarget = findTypeDefinitionTarget(context.resolvedTarget)
                ?: throw WorkspaceResourceException("Type definition not found at ${args.uri}:${args.row}:${args.column}")
            toNavigationResult(typeTarget, context.psiDocumentManager)
                ?: throw WorkspaceResourceException("Resolved type definition has no physical text location")
        }
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationToImplementationHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationSourcePositionArgs,
): CallToolResult {
    reportActivity("navigation_to_implementation: ${args.uri}:${args.row}:${args.column}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.uri,
    ) { project ->
        val effectiveLimit = if (args.limit < 1) 20 else args.limit
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, args.uri, args.row, args.column)
            val searchTarget = context.resolvedTarget.navigationElement
            val primaryItems = collectDefinitions(
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = effectiveLimit,
                includeTargetWhenEmpty = true,
                excludeOriginalTarget = false,
            )
            fallbackToReferences(
                primaryItems = primaryItems,
                fallbackToReferencesWhenEmpty = args.fallbackToReferencesWhenEmpty,
                reason = "Implementation search returned no results.",
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = effectiveLimit,
            )
        }
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationFindOverridesHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationSourcePositionArgs,
): CallToolResult {
    reportActivity("navigation_find_overrides: ${args.uri}:${args.row}:${args.column}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.uri,
    ) { project ->
        val effectiveLimit = if (args.limit < 1) 20 else args.limit
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, args.uri, args.row, args.column)
            val searchTarget = context.resolvedTarget.navigationElement
            val primaryItems = collectDefinitions(
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = effectiveLimit,
                includeTargetWhenEmpty = false,
                excludeOriginalTarget = true,
            )
            fallbackToReferences(
                primaryItems = primaryItems,
                fallbackToReferencesWhenEmpty = args.fallbackToReferencesWhenEmpty,
                reason = "Override search returned no results.",
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = effectiveLimit,
            )
        }
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationFindInheritorsHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationSourcePositionArgs,
): CallToolResult {
    reportActivity("navigation_find_inheritors: ${args.uri}:${args.row}:${args.column}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.uri,
    ) { project ->
        val effectiveLimit = if (args.limit < 1) 20 else args.limit
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, args.uri, args.row, args.column)
            val typeTarget = findTypeDefinitionTarget(context.resolvedTarget)
                ?: throw WorkspaceResourceException("Type declaration not found at ${args.uri}:${args.row}:${args.column}")
            val primaryItems = collectDefinitions(
                searchTarget = typeTarget.navigationElement,
                psiDocumentManager = context.psiDocumentManager,
                limit = effectiveLimit,
                includeTargetWhenEmpty = false,
                excludeOriginalTarget = true,
            )
            fallbackToReferences(
                primaryItems = primaryItems,
                fallbackToReferencesWhenEmpty = args.fallbackToReferencesWhenEmpty,
                reason = "Inheritor search returned no results.",
                searchTarget = typeTarget.navigationElement,
                psiDocumentManager = context.psiDocumentManager,
                limit = effectiveLimit,
            )
        }
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationFindReferencesHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationFindReferencesArgs,
): CallToolResult {
    reportActivity("navigation_find_references: ${args.uri}:${args.row}:${args.column}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.uri,
    ) { project ->
        val effectiveLimit = if (args.limit < 1) 50 else args.limit
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, args.uri, args.row, args.column)
            val searchTarget = context.resolvedTarget.navigationElement
            NavigationResults(
                items = collectReferences(searchTarget, context.psiDocumentManager, effectiveLimit),
            )
        }
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationGetCallersHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationSourcePositionArgs,
): CallToolResult {
    reportActivity("navigation_get_callers: ${args.uri}:${args.row}:${args.column}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.uri,
    ) { project ->
        val effectiveLimit = if (args.limit < 1) 50 else args.limit
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, args.uri, args.row, args.column)
            val searchTarget = context.resolvedTarget.navigationElement
            val primaryItems = collectReferences(
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = effectiveLimit,
                referenceFilter = ::isLikelyCallReference,
            )
            fallbackToReferences(
                primaryItems = primaryItems,
                fallbackToReferencesWhenEmpty = args.fallbackToReferencesWhenEmpty,
                reason = "Caller heuristic returned no results.",
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = effectiveLimit,
            )
        }
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationToReferenceBatchHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationToReferenceBatchArgs,
): CallToolResult {
    reportActivity("navigation_to_reference_batch: ${args.inputs.size} inputs, continueOnError=${args.continueOnError}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        val items = mutableListOf<NavigationBatchSingleItem>()
        var successCount = 0
        var failureCount = 0
        for (input in args.inputs) {
            val output = batchTry(args.continueOnError) {
                runNavigationRead(project) {
                    val context = resolveReferenceContext(project, input.uri, input.row, input.column)
                    toNavigationResult(context.resolvedTarget, context.psiDocumentManager)
                        ?: throw WorkspaceResourceException("Resolved target has no physical text location")
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
        val result = NavigationBatchSingleResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun navigationFindReferencesBatchHandler(
    ctx: SdkToolHandlerContext,
    args: NavigationFindReferencesBatchArgs,
): CallToolResult {
    reportActivity("navigation_find_references_batch: ${args.inputs.size} inputs, continueOnError=${args.continueOnError}")
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        val effectiveLimit = if (args.limit < 1) 50 else args.limit
        val items = mutableListOf<NavigationBatchMultiItem>()
        var successCount = 0
        var failureCount = 0
        for (input in args.inputs) {
            val output = batchTry(args.continueOnError) {
                runNavigationRead(project) {
                    val context = resolveReferenceContext(project, input.uri, input.row, input.column)
                    val searchTarget = context.resolvedTarget.navigationElement
                    NavigationResults(
                        items = collectReferences(searchTarget, context.psiDocumentManager, effectiveLimit),
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
        val result = NavigationBatchMultiResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ---------------------------------------------------------------------------
// Shared helpers — replicated from SymbolNavigationMcpTools internals
// ---------------------------------------------------------------------------

private const val DEFAULT_IMPLEMENTATION_LIMIT = 20
private const val DEFAULT_REFERENCE_LIMIT = 50
private const val TYPE_SCAN_MAX_DEPTH = 5
private const val TYPE_SCAN_MAX_NODES = 512

private class ResolvedReferenceContext(
    val resolvedTarget: PsiElement,
    val psiDocumentManager: PsiDocumentManager,
)

private class SymbolInfoContext(
    val symbolName: String,
    val documentationHtml: String,
)

private class ResolvedSourcePosition(
    val row: Int,
    val column: Int,
    val offset: Int,
)

private val vfsManager: VirtualFileManager
    get() = service<VirtualFileManager>()

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
        documentation = if (context.documentationHtml.isBlank()) "" else context.documentationHtml.replace(Regex("<[^>]+>"), ""),
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
        throw WorkspaceResourceException("Provide either offset or row+column, not both.")
    }
    if (!hasOffset && !hasRowColumn) {
        throw WorkspaceResourceException("Provide either offset or row+column.")
    }
    if (hasOffset) {
        return resolvePositionFromOffset(project, uri, input.offset)
    }
    val row = input.row ?: throw WorkspaceResourceException("row is required when offset is not provided.")
    val column = input.column ?: throw WorkspaceResourceException("column is required when offset is not provided.")
    val sourceDocument = resolveCommittedSourceDocument(project, uri)
    val offset = resolveSourceOffset(sourceDocument, row, column)
    return ResolvedSourcePosition(row = row, column = column, offset = offset)
}

private fun resolvePositionFromOffset(
    project: Project,
    uri: String,
    offset: Int,
): ResolvedSourcePosition {
    assertReadAccess()
    if (offset < 0) {
        throw WorkspaceResourceException("offset must be >= 0")
    }
    val sourceDocument = resolveCommittedSourceDocument(project, uri)
    if (sourceDocument.textLength == 0) {
        return ResolvedSourcePosition(row = 1, column = 1, offset = 0)
    }
    if (offset >= sourceDocument.textLength) {
        throw WorkspaceResourceException("offset must be in [0, ${sourceDocument.textLength - 1}] for URL: $uri")
    }
    val row = sourceDocument.getLineNumber(offset) + 1
    val lineStart = sourceDocument.getLineStartOffset(row - 1)
    val column = (offset - lineStart) + 1
    return ResolvedSourcePosition(row = row, column = column, offset = offset)
}

private fun resolveCommittedSourceDocument(project: Project, uri: String): Document {
    val sourceFile = vfsManager.findFileByUrl(uri) ?: throw WorkspaceResourceException("File not found for URL: $uri")
    if (sourceFile.isDirectory) {
        throw WorkspaceResourceException("URL points to a directory, not a file: $uri")
    }
    val sourcePsiFile = PsiManager.getInstance(project).findFile(sourceFile)
        ?: throw WorkspaceResourceException("No PSI file available for URL: $uri${vfsReadHint(sourceFile)}")
    return PsiDocumentManager.getInstance(project).getLastCommittedDocument(sourcePsiFile)
        ?: throw WorkspaceResourceException("No committed text document available for URL: $uri. Commit pending changes and retry.${vfsReadHint(sourceFile)}")
}

private fun resolveSourceOffset(
    sourceDocument: Document,
    row: Int,
    column: Int,
): Int {
    val lineIndex = row - 1
    if (!DocumentUtil.isValidLine(lineIndex, sourceDocument)) {
        throw WorkspaceResourceException("row must be in [1, ${sourceDocument.lineCount}], but was $row")
    }
    val lineStartOffset = sourceDocument.getLineStartOffset(lineIndex)
    val lineEndOffset = sourceDocument.getLineEndOffset(lineIndex)
    val maxColumn = (lineEndOffset - lineStartOffset) + 1
    if (column > maxColumn) {
        throw WorkspaceResourceException("column must be in [1, $maxColumn] for row $row, but was $column")
    }
    val sourceOffset = lineStartOffset + column - 1
    if (!DocumentUtil.isValidOffset(sourceOffset, sourceDocument)) {
        throw WorkspaceResourceException("Line and column $row:$column(offset=$sourceOffset) is out of bounds (file has ${sourceDocument.textLength} characters)")
    }
    return sourceOffset
}

private fun readSymbolInfoContext(
    project: Project,
    uri: String,
    row: Int,
    column: Int,
): SymbolInfoContext {
    assertReadAccess()
    validatePosition(row, column)
    val sourceFile = vfsManager.findFileByUrl(uri) ?: throw WorkspaceResourceException("File not found for URL: $uri")
    if (sourceFile.isDirectory) {
        throw WorkspaceResourceException("URL points to a directory, not a file: $uri")
    }
    val sourcePsiFile = PsiManager.getInstance(project).findFile(sourceFile)
        ?: throw WorkspaceResourceException("No PSI file available for URL: $uri${vfsReadHint(sourceFile)}")
    val sourceDocument = PsiDocumentManager.getInstance(project).getLastCommittedDocument(sourcePsiFile)
        ?: throw WorkspaceResourceException("No committed text document available for URL: $uri. Commit pending changes and retry.${vfsReadHint(sourceFile)}")
    val sourceOffset = resolveSourceOffset(sourceDocument, row, column)
    val lineStartOffset = sourceDocument.getLineStartOffset(row - 1)
    val sourceElement = findElementAt(sourcePsiFile, sourceOffset, lineStartOffset)
    val reference = findReferenceAt(sourcePsiFile, sourceOffset, lineStartOffset)
    val resolvedReference = reference?.resolve()
    val symbolName = resolvedReference?.let { (it as? PsiNamedElement)?.name ?: it.text.take(80) } ?: ""
    val documentationTarget = resolvedReference ?: sourceElement
    val documentationHtml = generateDocumentationHtml(
        targetElement = documentationTarget,
        originalElement = sourceElement,
    )
    return SymbolInfoContext(
        symbolName = symbolName,
        documentationHtml = documentationHtml,
    )
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

private fun resolveReferenceContext(
    project: Project,
    uri: String,
    row: Int,
    column: Int,
): ResolvedReferenceContext {
    assertReadAccess()
    validatePosition(row, column)
    val sourceFile = vfsManager.findFileByUrl(uri) ?: throw WorkspaceResourceException("File not found for URL: $uri")
    if (sourceFile.isDirectory) throw WorkspaceResourceException("URL points to a directory, not a file: $uri")

    val psiManager = PsiManager.getInstance(project)
    val sourcePsiFile = psiManager.findFile(sourceFile)
        ?: throw WorkspaceResourceException("No PSI file available for URL: $uri${vfsReadHint(sourceFile)}")
    val psiDocumentManager = PsiDocumentManager.getInstance(project)
    val sourceDocument = psiDocumentManager.getLastCommittedDocument(sourcePsiFile)
        ?: throw WorkspaceResourceException("No committed text document available for URL: $uri. Commit pending changes and retry.${vfsReadHint(sourceFile)}")

    if (row > sourceDocument.lineCount) {
        throw WorkspaceResourceException("row must be in [1, ${sourceDocument.lineCount}], but was $row")
    }
    val lineStartOffset = sourceDocument.getLineStartOffset(row - 1)
    val lineEndOffset = sourceDocument.getLineEndOffset(row - 1)
    val maxColumn = (lineEndOffset - lineStartOffset) + 1
    if (column > maxColumn) {
        throw WorkspaceResourceException("column must be in [1, $maxColumn] for row $row, but was $column")
    }
    val sourceOffset = lineStartOffset + column - 1

    val reference = findReferenceAt(sourcePsiFile, sourceOffset, lineStartOffset)
        ?: throw WorkspaceResourceException("No reference found at $uri:$row:$column${vfsReadHint(sourceFile)}")
    val resolvedTarget = reference.resolve()
        ?: throw WorkspaceResourceException("Reference at $uri:$row:$column cannot be resolved")
    return ResolvedReferenceContext(
        resolvedTarget = resolvedTarget,
        psiDocumentManager = psiDocumentManager,
    )
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
    return isLikelyTypeDeclarationClassName(element.javaClass.simpleName)
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
        throw WorkspaceResourceException("Code navigation is temporarily unavailable while indexes are updating. Please retry.")
    }
}

private fun ensureIndicesReady(project: Project) {
    if (DumbService.isDumb(project)) {
        throw WorkspaceResourceException("Code navigation is unavailable while indexing is in progress. Please retry after indexing completes.")
    }
}

private fun assertReadAccess() {
    if (!ApplicationManager.getApplication().isReadAccessAllowed) {
        throw WorkspaceResourceException("Internal error: PSI/VFS read attempted outside read action.")
    }
}

private fun validatePosition(row: Int, column: Int) {
    if (row < 1) throw WorkspaceResourceException("row must be >= 1")
    if (column < 1) throw WorkspaceResourceException("column must be >= 1")
}

private fun vfsReadHint(sourceFile: VirtualFile): String {
    return if (sourceFile.fileSystem.protocol == "jar") {
        ". For JAR/ZIP virtual files, prefer vfs_read_file* tools."
    } else {
        ""
    }
}
