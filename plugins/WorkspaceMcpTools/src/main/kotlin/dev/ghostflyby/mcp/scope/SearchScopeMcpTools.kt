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
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.openapi.util.NlsContexts
import kotlinx.coroutines.currentCoroutineContext

@Suppress("FunctionName")
internal class SearchScopeMcpTools : McpToolset {

    @McpTool
    @McpDescription("List available search scopes (Find-like catalog) with stable scopeRefId and metadata.")
    suspend fun scope_list_catalog(
        @McpDescription("Whether to include scopes that depend on current UI context (for example Current File).")
        includeInteractiveScopes: Boolean = true,
    ): ScopeCatalogResultDto {
        this.reportActivity(Bundle.message("tool.activity.scope.list.catalog", includeInteractiveScopes))
        val project = currentCoroutineContext().project
        return ScopeCatalogService.getInstance(project).listCatalog(project, includeInteractiveScopes)
    }

    @McpTool
    @McpDescription("Validate a PackageSet pattern text used by IntelliJ scopes.")
    suspend fun scope_validate_pattern(
        @McpDescription("Scope pattern text in IntelliJ PackageSet syntax.")
        patternText: String,
    ): ScopePatternValidationResultDto {
        this.reportActivity(Bundle.message("tool.activity.scope.validate.pattern", patternText.length))
        val project = currentCoroutineContext().project
        return ScopeResolverService.getInstance(project).validatePattern(patternText)
    }

    @McpTool
    @McpDescription("Compile and normalize scope atoms and RPN tokens into a reusable scope descriptor.")
    suspend fun scope_resolve_program(
        request: ScopeResolveRequestDto,
    ): ScopeResolveResultDto {
        this.reportActivity(
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
        this.reportActivity(
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

    private suspend fun reportActivity(@NlsContexts.Label description: String) {
        currentCoroutineContext().reportToolActivity(description)
    }
}
