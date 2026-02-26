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
import dev.ghostflyby.mcp.VFS_URL_PARAM_DESCRIPTION
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import kotlinx.coroutines.currentCoroutineContext
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
    )

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
    ): NavigationResults {
        reportActivity(Bundle.message("tool.activity.navigation.to.implementation", uri, row, column, limit))
        val project = currentCoroutineContext().project
        val items = runNavigationRead(project) {
            validateLimit(limit)
            val context = resolveReferenceContext(project, uri, row, column)
            val searchTarget = context.resolvedTarget.navigationElement
            collectDefinitions(
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = limit,
                includeTargetWhenEmpty = true,
                excludeOriginalTarget = false,
            )
        }
        return NavigationResults(items = items)
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
    ): NavigationResults {
        reportActivity(Bundle.message("tool.activity.navigation.find.overrides", uri, row, column, limit))
        val project = currentCoroutineContext().project
        val items = runNavigationRead(project) {
            validateLimit(limit)
            val context = resolveReferenceContext(project, uri, row, column)
            val searchTarget = context.resolvedTarget.navigationElement
            collectDefinitions(
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = limit,
                includeTargetWhenEmpty = false,
                excludeOriginalTarget = true,
            )
        }
        return NavigationResults(items = items)
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
    ): NavigationResults {
        reportActivity(Bundle.message("tool.activity.navigation.find.inheritors", uri, row, column, limit))
        val project = currentCoroutineContext().project
        val items = runNavigationRead(project) {
            validateLimit(limit)
            val context = resolveReferenceContext(project, uri, row, column)
            val typeTarget = findTypeDefinitionTarget(context.resolvedTarget)
                ?: mcpFail("Type declaration not found at $uri:$row:$column")
            collectDefinitions(
                searchTarget = typeTarget.navigationElement,
                psiDocumentManager = context.psiDocumentManager,
                limit = limit,
                includeTargetWhenEmpty = false,
                excludeOriginalTarget = true,
            )
        }
        return NavigationResults(items = items)
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
    ): NavigationResults {
        reportActivity(Bundle.message("tool.activity.navigation.get.callers", uri, row, column, limit))
        val project = currentCoroutineContext().project
        val items = runNavigationRead(project) {
            validateLimit(limit)
            val context = resolveReferenceContext(project, uri, row, column)
            val searchTarget = context.resolvedTarget.navigationElement
            collectReferences(
                searchTarget = searchTarget,
                psiDocumentManager = context.psiDocumentManager,
                limit = limit,
                referenceFilter = ::isLikelyCallReference,
            )
        }
        return NavigationResults(items = items)
    }

    private class ResolvedReferenceContext(
        val resolvedTarget: PsiElement,
        val psiDocumentManager: PsiDocumentManager,
    )

    private fun resolveReferenceContext(
        project: Project,
        uri: String,
        row: Int,
        column: Int,
    ): ResolvedReferenceContext {
        validatePosition(row, column)
        val sourceFile = vfsManager.findFileByUrl(uri) ?: mcpFail("File not found for URL: $uri")
        if (sourceFile.isDirectory) mcpFail("URL points to a directory, not a file: $uri")

        val psiManager = PsiManager.getInstance(project)
        val sourcePsiFile = psiManager.findFile(sourceFile)
            ?: mcpFail("No PSI file available for URL: $uri")
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val sourceDocument = psiDocumentManager.getLastCommittedDocument(sourcePsiFile)
            ?: FileDocumentManager.getInstance().getDocument(sourceFile)
            ?: mcpFail("No text document available for URL: $uri")

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
            ?: mcpFail("No reference found at $uri:$row:$column")
        val resolvedTarget = reference.resolve()
            ?: mcpFail("Reference at $uri:$row:$column cannot be resolved")
        return ResolvedReferenceContext(
            resolvedTarget = resolvedTarget,
            psiDocumentManager = psiDocumentManager,
        )
    }

    private fun findReferenceAt(sourcePsiFile: PsiFile, sourceOffset: Int, lineStartOffset: Int): PsiReference? {
        val primaryReference = sourcePsiFile.findReferenceAt(sourceOffset)
        if (primaryReference != null) return primaryReference
        return if (sourceOffset > lineStartOffset) sourcePsiFile.findReferenceAt(sourceOffset - 1) else null
    }

    private fun toNavigationResult(element: PsiElement, psiDocumentManager: PsiDocumentManager): NavigationResult? {
        val navigationElement = element.navigationElement
        val targetPsiFile = navigationElement.containingFile ?: return null
        val targetFile = targetPsiFile.virtualFile ?: return null
        val targetDocument = psiDocumentManager.getLastCommittedDocument(targetPsiFile)
            ?: FileDocumentManager.getInstance().getDocument(targetFile)
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

    private fun validatePosition(row: Int, column: Int) {
        if (row < 1) mcpFail("row must be >= 1")
        if (column < 1) mcpFail("column must be >= 1")
    }

    private fun validateLimit(limit: Int) {
        if (limit < 1) mcpFail("limit must be >= 1")
    }

    private suspend fun reportActivity(@NlsContexts.Label description: String) {
        currentCoroutineContext().reportToolActivity(description)
    }
}
