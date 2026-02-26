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

package dev.ghostflyby.mcp.scope

import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.VFS_URL_PARAM_DESCRIPTION
import dev.ghostflyby.mcp.reportActivity
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.coroutines.currentCoroutineContext

@Suppress("FunctionName")
internal class SearchScopeMcpTools : McpToolset {

    @McpTool
    @McpDescription("List available search scopes (Find-like catalog) with stable scopeRefId and metadata.")
    suspend fun scope_list_catalog(
        @McpDescription("Whether to include scopes that depend on current UI context (for example Current File).")
        includeInteractiveScopes: Boolean = true,
    ): ScopeCatalogResultDto {
        reportActivity(Bundle.message("tool.activity.scope.list.catalog", includeInteractiveScopes))
        val project = currentCoroutineContext().project
        return ScopeCatalogService.getInstance(project).listCatalog(project, includeInteractiveScopes)
    }

    @McpTool
    @McpDescription("Validate a PackageSet pattern text used by IntelliJ scopes.")
    suspend fun scope_validate_pattern(
        @McpDescription("Scope pattern text in IntelliJ PackageSet syntax.")
        patternText: String,
    ): ScopePatternValidationResultDto {
        reportActivity(Bundle.message("tool.activity.scope.validate.pattern", patternText.length))
        val project = currentCoroutineContext().project
        return ScopeResolverService.getInstance(project).validatePattern(patternText)
    }

    @McpTool
    @McpDescription("Compile and normalize scope atoms and RPN tokens into a reusable scope descriptor.")
    suspend fun scope_resolve_program(
        request: ScopeResolveRequestDto,
    ): ScopeResolveResultDto {
        reportActivity(
            Bundle.message(
                "tool.activity.scope.resolve.program",
                request.atoms.size,
                request.tokens.size,
                request.strict,
                request.allowUiInteractiveScopes,
                request.nonStrictDefaultFailureMode.name,
            ),
        )
        val project = currentCoroutineContext().project
        val descriptor = ScopeResolverService.getInstance(project).compileProgramDescriptor(project, request)
        return ScopeResolveResultDto(
            descriptor = descriptor,
        )
    }

    @McpTool
    @McpDescription("Describe (normalize + validate) a scope program and return only structured descriptor data.")
    suspend fun scope_describe_program(
        request: ScopeResolveRequestDto,
    ): ScopeDescribeProgramResultDto {
        reportActivity(
            Bundle.message(
                "tool.activity.scope.describe.program",
                request.atoms.size,
                request.tokens.size,
                request.strict,
                request.allowUiInteractiveScopes,
                request.nonStrictDefaultFailureMode.name,
            ),
        )
        val project = currentCoroutineContext().project
        val descriptor = ScopeResolverService.getInstance(project).compileProgramDescriptor(project, request)
        return ScopeDescribeProgramResultDto(
            descriptor = descriptor,
        )
    }

    @McpTool
    @McpDescription("Check whether a file URL belongs to a resolved scope descriptor.")
    suspend fun scope_contains_file(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        fileUrl: String,
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): ScopeContainsFileResultDto {
        reportActivity(
            Bundle.message(
                "tool.activity.scope.contains.file",
                fileUrl,
                allowUiInteractiveScopes,
            ),
        )
        val project = currentCoroutineContext().project
        val resolved = ScopeResolverService.getInstance(project).resolveDescriptor(
            project = project,
            descriptor = scope,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        val file = findFileByUrl(fileUrl)
            ?: mcpFail("File URL '$fileUrl' not found.")
        if (file.isDirectory) {
            mcpFail("URL '$fileUrl' points to a directory, not a file.")
        }
        val matches = readAction { resolved.scope.contains(file) }
        return ScopeContainsFileResultDto(
            fileUrl = fileUrl,
            matches = matches,
            scopeDisplayName = resolved.displayName,
            scopeShape = resolved.scopeShape,
            diagnostics = (scope.diagnostics + resolved.diagnostics).distinct(),
        )
    }

    @McpTool
    @McpDescription("Filter file URLs by whether they belong to a resolved scope descriptor.")
    suspend fun scope_filter_files(
        @McpDescription("Input file URLs to test against the scope.")
        fileUrls: List<String>,
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): ScopeFilterFilesResultDto {
        reportActivity(
            Bundle.message(
                "tool.activity.scope.filter.files",
                fileUrls.size,
                allowUiInteractiveScopes,
            ),
        )
        val project = currentCoroutineContext().project
        val resolved = ScopeResolverService.getInstance(project).resolveDescriptor(
            project = project,
            descriptor = scope,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )

        val diagnostics = mutableListOf<String>()
        val matched = mutableListOf<String>()
        val excluded = mutableListOf<String>()
        val missing = mutableListOf<String>()

        fileUrls.forEach { url ->
            val file = findFileByUrl(url)
            when {
                file == null -> {
                    missing += url
                    diagnostics += "File URL '$url' not found."
                }

                file.isDirectory -> {
                    missing += url
                    diagnostics += "URL '$url' points to a directory and was skipped."
                }

                readAction { resolved.scope.contains(file) } -> matched += url
                else -> excluded += url
            }
        }

        return ScopeFilterFilesResultDto(
            scopeDisplayName = resolved.displayName,
            scopeShape = resolved.scopeShape,
            matchedFileUrls = matched,
            excludedFileUrls = excluded,
            missingFileUrls = missing,
            diagnostics = (scope.diagnostics + resolved.diagnostics + diagnostics).distinct(),
        )
    }

    private suspend fun findFileByUrl(url: String): VirtualFile? {
        val manager = VirtualFileManager.getInstance()
        val direct = readAction { manager.findFileByUrl(url) }
        if (direct != null) return direct
        return backgroundWriteAction { manager.refreshAndFindFileByUrl(url) }
    }
}
