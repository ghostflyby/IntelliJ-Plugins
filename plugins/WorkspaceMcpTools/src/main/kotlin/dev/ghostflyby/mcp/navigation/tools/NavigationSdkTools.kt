/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
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
import dev.ghostflyby.mcp.common.WorkspaceResourceException
import dev.ghostflyby.mcp.common.batchTry
import dev.ghostflyby.mcp.common.isLikelyTypeDeclarationClassName
import dev.ghostflyby.mcp.common.reportActivity
import dev.ghostflyby.mcp.navigation.*
import dev.ghostflyby.mcp.route.McpCallContext
import dev.ghostflyby.mcp.route.project
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.schema.Schema

// ---------------------------------------------------------------------------
// NavigationTools class — 14 @Schema-annotated MCP tool functions
// ---------------------------------------------------------------------------


internal class NavigationTools {
    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_get_symbol_info(
        uri: String,
        row: Int,
        column: Int,
    ): CallToolResult {
        reportActivity("navigation_get_symbol_info: $uri:$row:$column")
        val project = call.project()
        val result = resolveSymbolInfo(project, uri, row, column)
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_get_symbol_info_by_offset(
        uri: String,
        offset: Int,
    ): CallToolResult {
        reportActivity("navigation_get_symbol_info_by_offset: $uri offset=$offset")
        val project = call.project()
        val position = runNavigationRead(project) {
            resolvePositionFromOffset(project, uri, offset)
        }
        val info = resolveSymbolInfo(project, uri, position.row, position.column)
        val result = NavigationSymbolInfoResolvedResult(
            documentation = info.documentation,
            row = position.row,
            column = position.column,
            offset = position.offset,
            recommendedNextCalls = listOf(
                "navigation_get_symbol_info(uri='$uri', row=${position.row}, column=${position.column})",
            ),
        )
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_get_symbol_info_auto_position(
        uri: String,
        row: Int? = null,
        column: Int? = null,
        offset: Int? = null,
    ): CallToolResult {
        reportActivity("navigation_get_symbol_info_auto_position: $uri")
        val project = call.project()
        val input = NavigationInternalAutoPositionInput(row = row, column = column, offset = offset)
        val position = runNavigationRead(project) {
            resolveAutoPosition(project, uri, input)
        }
        val info = resolveSymbolInfo(project, uri, position.row, position.column)
        val result = NavigationSymbolInfoResolvedResult(
            documentation = info.documentation,
            row = position.row,
            column = position.column,
            offset = position.offset,
            recommendedNextCalls = listOf(
                "navigation_get_symbol_info(uri='$uri', row=${position.row}, column=${position.column})",
                "navigation_get_symbol_info_by_offset(uri='$uri', offset=${position.offset})",
            ),
        )
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_get_symbol_info_quick(
        uri: String,
        row: Int,
        column: Int,
    ): CallToolResult {
        reportActivity("navigation_get_symbol_info_quick: $uri:$row:$column")
        val project = call.project()
        val position = runNavigationRead(project) {
            ResolvedSourcePosition(row = row, column = column, offset = -1)
        }
        val info = resolveSymbolInfo(project, uri, position.row, position.column)
        val sourceDocument = runNavigationRead(project) {
            resolveCommittedSourceDocument(project, uri)
        }
        val computedOffset = runNavigationRead(project) {
            resolveSourceOffset(sourceDocument, row, column)
        }
        val result = NavigationSymbolInfoResolvedResult(
            documentation = info.documentation,
            row = position.row,
            column = position.column,
            offset = computedOffset,
            recommendedNextCalls = listOf(
                "navigation_get_symbol_info(uri='$uri', row=${position.row}, column=${position.column})",
            ),
        )
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_get_symbol_info_batch(
        inputs: List<NavigationSymbolInfoPosition>,
        continueOnError: Boolean = true,
    ): CallToolResult {
        reportActivity("navigation_get_symbol_info_batch: ${inputs.size} inputs, continueOnError=$continueOnError")
        val project = call.project()
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
        val result = NavigationBatchSymbolInfoResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_to_reference(
        uri: String,
        row: Int,
        column: Int,
    ): CallToolResult {
        reportActivity("navigation_to_reference: $uri:$row:$column")
        val project = call.project()
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, uri, row, column)
            toNavigationResult(context.resolvedTarget, context.psiDocumentManager)
                ?: throw WorkspaceResourceException("Resolved target has no physical text location")
        }
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_to_type_definition(
        uri: String,
        row: Int,
        column: Int,
    ): CallToolResult {
        reportActivity("navigation_to_type_definition: $uri:$row:$column")
        val project = call.project()
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, uri, row, column)
            val typeTarget = findTypeDefinitionTarget(context.resolvedTarget)
                ?: throw WorkspaceResourceException("Type definition not found at $uri:$row:$column")
            toNavigationResult(typeTarget, context.psiDocumentManager)
                ?: throw WorkspaceResourceException("Resolved type definition has no physical text location")
        }
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_to_implementation(
        uri: String,
        row: Int,
        column: Int,
        limit: Int = 50,
        fallbackToReferencesWhenEmpty: Boolean = false,
    ): CallToolResult {
        reportActivity("navigation_to_implementation: $uri:$row:$column")
        val project = call.project()
        val effectiveLimit = if (limit < 1) 20 else limit
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, uri, row, column)
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
                fallbackToReferencesWhenEmpty = fallbackToReferencesWhenEmpty,
                reason = "Implementation search returned no results.",
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = effectiveLimit,
            )
        }
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_find_overrides(
        uri: String,
        row: Int,
        column: Int,
        limit: Int = 50,
        fallbackToReferencesWhenEmpty: Boolean = false,
    ): CallToolResult {
        reportActivity("navigation_find_overrides: $uri:$row:$column")
        val project = call.project()
        val effectiveLimit = if (limit < 1) 20 else limit
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, uri, row, column)
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
                fallbackToReferencesWhenEmpty = fallbackToReferencesWhenEmpty,
                reason = "Override search returned no results.",
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = effectiveLimit,
            )
        }
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_find_inheritors(
        uri: String,
        row: Int,
        column: Int,
        limit: Int = 50,
        fallbackToReferencesWhenEmpty: Boolean = false,
    ): CallToolResult {
        reportActivity("navigation_find_inheritors: $uri:$row:$column")
        val project = call.project()
        val effectiveLimit = if (limit < 1) 20 else limit
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, uri, row, column)
            val typeTarget = findTypeDefinitionTarget(context.resolvedTarget)
                ?: throw WorkspaceResourceException("Inheritor search: no type definition found at $uri:$row:$column. Ensure navigation_to_type_definition resolves first.")
            val primaryItems = collectDefinitions(
                searchTarget = typeTarget.navigationElement,
                psiDocumentManager = context.psiDocumentManager,
                limit = effectiveLimit,
                includeTargetWhenEmpty = false,
                excludeOriginalTarget = true,
            )
            fallbackToReferences(
                primaryItems = primaryItems,
                fallbackToReferencesWhenEmpty = fallbackToReferencesWhenEmpty,
                reason = "Inheritor search returned no results.",
                searchTarget = typeTarget.navigationElement,
                psiDocumentManager = context.psiDocumentManager,
                limit = effectiveLimit,
            )
        }
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_find_references(
        uri: String,
        row: Int,
        column: Int,
        limit: Int = 50,
    ): CallToolResult {
        reportActivity("navigation_find_references: $uri:$row:$column")
        val project = call.project()
        val effectiveLimit = if (limit < 1) 50 else limit
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, uri, row, column)
            val searchTarget = context.resolvedTarget.navigationElement
            NavigationResults(
                items = collectReferences(searchTarget, context.psiDocumentManager, effectiveLimit),
            )
        }
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_get_callers(
        uri: String,
        row: Int,
        column: Int,
        limit: Int = 50,
    ): CallToolResult {
        reportActivity("navigation_get_callers: $uri:$row:$column")
        val project = call.project()
        val effectiveLimit = if (limit < 1) 50 else limit
        val result = runNavigationRead(project) {
            val context = resolveReferenceContext(project, uri, row, column)
            val searchTarget = context.resolvedTarget.navigationElement
            val callFilter: (PsiReference) -> Boolean = { isLikelyCallReference(it) }
            NavigationResults(
                items = collectReferences(searchTarget, context.psiDocumentManager, effectiveLimit, callFilter),
            )
        }
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_to_reference_batch(
        inputs: List<NavigationSourcePosition>,
        continueOnError: Boolean = true,
    ): CallToolResult {
        reportActivity("navigation_to_reference_batch: ${inputs.size} inputs, continueOnError=$continueOnError")
        val project = call.project()
        val items = mutableListOf<NavigationBatchSingleItem>()
        var successCount = 0
        var failureCount = 0
        for (input in inputs) {
            val output = batchTry(continueOnError) {
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
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    suspend fun McpCallContext<CallToolRequest>.navigation_find_references_batch(
        inputs: List<NavigationSourcePosition>,
        limit: Int = 50,
        continueOnError: Boolean = true,
    ): CallToolResult {
        reportActivity("navigation_find_references_batch: ${inputs.size} inputs, continueOnError=$continueOnError")
        val project = call.project()
        val effectiveLimit = if (limit < 1) 50 else limit
        val items = mutableListOf<NavigationBatchMultiItem>()
        var successCount = 0
        var failureCount = 0
        for (input in inputs) {
            val output = batchTry(continueOnError) {
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
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ---------------------------------------------------------------------------
// Internal helper — replaces NavigationSymbolInfoAutoPositionInput
// ---------------------------------------------------------------------------

private data class NavigationInternalAutoPositionInput(
    val row: Int? = null,
    val column: Int? = null,
    val offset: Int? = null,
)

// Shared helpers — replicated from SymbolNavigationMcpTools internals
// ---------------------------------------------------------------------------

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
    input: NavigationInternalAutoPositionInput,
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
