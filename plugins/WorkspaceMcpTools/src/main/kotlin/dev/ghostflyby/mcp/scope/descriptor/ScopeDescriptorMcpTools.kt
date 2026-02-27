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

package dev.ghostflyby.mcp.scope.descriptor

import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.common.ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION
import dev.ghostflyby.mcp.common.AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX
import dev.ghostflyby.mcp.common.VFS_URL_PARAM_DESCRIPTION
import dev.ghostflyby.mcp.common.findFileByUrlWithRefresh
import dev.ghostflyby.mcp.common.reportActivity
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import dev.ghostflyby.mcp.scope.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

@Suppress("FunctionName")
internal class ScopeDescriptorMcpTools : McpToolset {

    @Serializable
    enum class ScopeCatalogIntent {
        PROJECT_ONLY,
        WITH_LIBRARIES,
        CHANGED_FILES,
        OPEN_FILES,
        CURRENT_FILE,
    }

    @Serializable
    data class ScopeCatalogIntentResultDto(
        val intent: ScopeCatalogIntent,
        val recommendedScopeRefId: String? = null,
        val items: List<ScopeCatalogItemDto>,
        val diagnostics: List<String> = emptyList(),
    )

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
    @McpDescription(
        "Return a ready-to-use default scope descriptor by preset, avoiding catalog+program assembly on first call." +
            AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX,
    )
    suspend fun scope_get_default_descriptor(
        @McpDescription("Preset scope to use.")
        preset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
        allowUiInteractiveScopes: Boolean = false,
    ): ScopeResolveResultDto {
        reportActivity(Bundle.message("tool.activity.scope.get.default.descriptor", preset.name, allowUiInteractiveScopes))
        return scope_resolve_standard_descriptor(
            standardScopeId = preset.toStandardScopeId(),
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
    }

    @McpTool
    @McpDescription(
        "Resolve a standard IDE scope id directly to a normalized reusable descriptor." +
            AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX,
    )
    suspend fun scope_resolve_standard_descriptor(
        @McpDescription("Standard scope id, for example 'Project Files' or 'All Places'.")
        standardScopeId: String,
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
        allowUiInteractiveScopes: Boolean = false,
    ): ScopeResolveResultDto {
        if (standardScopeId.isBlank()) {
            mcpFail("standardScopeId must not be blank.")
        }
        reportActivity(Bundle.message("tool.activity.scope.resolve.standard.descriptor", standardScopeId, allowUiInteractiveScopes))
        val project = currentCoroutineContext().project
        val descriptor = buildStandardScopeDescriptor(
            project = project,
            standardScopeId = standardScopeId,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        return ScopeResolveResultDto(descriptor = descriptor)
    }

    @McpTool
    @McpDescription(
        "Find a compact scope catalog subset by intent (project-only, with-libraries, changed/open/current file) " +
            "to reduce first-call catalog payload.",
    )
    suspend fun scope_catalog_find_by_intent(
        @McpDescription("Selection intent for reducing catalog candidates.")
        intent: ScopeCatalogIntent,
        @McpDescription("Maximum number of catalog items to return.")
        maxResults: Int = 20,
        @McpDescription("Whether to include scopes that depend on current UI context.")
        includeInteractiveScopes: Boolean = false,
    ): ScopeCatalogIntentResultDto {
        if (maxResults < 1) mcpFail("maxResults must be >= 1.")
        reportActivity(Bundle.message("tool.activity.scope.catalog.find.by.intent", intent.name, maxResults, includeInteractiveScopes))
        val project = currentCoroutineContext().project
        val catalog = ScopeCatalogService.getInstance(project).listCatalog(project, includeInteractiveScopes)
        val candidates = catalog.items.filter { item ->
            val serializationId = item.serializationId
            when (intent) {
                ScopeCatalogIntent.PROJECT_ONLY -> serializationId == "Project Files" ||
                    serializationId == "Project Production Files" ||
                    serializationId == "Project Test Files" ||
                    item.kind == ScopeAtomKind.MODULE

                ScopeCatalogIntent.WITH_LIBRARIES -> serializationId == "All Places" ||
                    serializationId == "Project and Libraries" ||
                    item.moduleFlavor == ModuleScopeFlavor.MODULE_WITH_LIBRARIES ||
                    item.moduleFlavor == ModuleScopeFlavor.MODULE_WITH_DEPENDENCIES_AND_LIBRARIES

                ScopeCatalogIntent.CHANGED_FILES -> serializationId == "Recently Changed Files"
                ScopeCatalogIntent.OPEN_FILES -> serializationId == "Open Files"
                ScopeCatalogIntent.CURRENT_FILE -> serializationId == "Current File"
            }
        }
        val sorted = candidates.sortedWith(
            compareBy(
                { it.requiresUserInput },
                { it.unstable },
                { it.displayName.lowercase() },
            ),
        )
        val truncated = sorted.size > maxResults
        val selected = sorted.take(maxResults)
        val recommended = selected.firstOrNull()?.scopeRefId
        val diagnostics = buildList {
            addAll(catalog.diagnostics)
            if (selected.isEmpty()) {
                add("No catalog items matched intent=${intent.name}. Consider includeInteractiveScopes=true or a different intent.")
            }
            if (truncated) {
                add("Matched catalog items exceed maxResults=$maxResults; result was truncated.")
            }
        }.distinct()
        return ScopeCatalogIntentResultDto(
            intent = intent,
            recommendedScopeRefId = recommended,
            items = selected,
            diagnostics = diagnostics,
        )
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
    @McpDescription(
        "Normalize and recompile an existing scope descriptor, useful for migration and compatibility upgrades.",
    )
    suspend fun scope_normalize_program_descriptor(
        descriptor: ScopeProgramDescriptorDto,
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
        allowUiInteractiveScopes: Boolean = false,
    ): ScopeDescribeProgramResultDto {
        reportActivity(Bundle.message("tool.activity.scope.normalize.program.descriptor", descriptor.atoms.size, descriptor.tokens.size))
        val project = currentCoroutineContext().project
        val request = ScopeResolveRequestDto(
            atoms = descriptor.atoms,
            tokens = descriptor.tokens,
            strict = true,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
            nonStrictDefaultFailureMode = ScopeAtomFailureMode.EMPTY_SCOPE,
        )
        val normalized = ScopeResolverService.getInstance(project).compileProgramDescriptor(project, request)
        return ScopeDescribeProgramResultDto(
            descriptor = normalized.copy(
                diagnostics = (descriptor.diagnostics + normalized.diagnostics).distinct(),
            ),
        )
    }

    @McpTool
    @McpDescription("Check whether a file URL belongs to a resolved scope descriptor.")
    suspend fun scope_contains_file(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        fileUrl: String,
        scope: ScopeProgramDescriptorDto,
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
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
        val file = findFileByUrlWithRefresh(fileUrl)
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
        @McpDescription(ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION)
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
            val file = findFileByUrlWithRefresh(url)
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
}
