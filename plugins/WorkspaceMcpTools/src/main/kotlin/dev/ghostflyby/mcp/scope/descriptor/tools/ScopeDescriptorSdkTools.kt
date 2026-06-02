/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.descriptor.tools

import com.intellij.openapi.application.readAction
import dev.ghostflyby.mcp.common.findFileByUrlWithRefresh
import dev.ghostflyby.mcp.scope.*
import dev.ghostflyby.mcp.sdk.project
import dev.ghostflyby.mcp.server.route.McpCallContext
import dev.ghostflyby.mcp.server.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable

@Schema
@Serializable
internal enum class ScopeSdkCatalogIntent {
    PROJECT_ONLY,
    WITH_LIBRARIES,
    CHANGED_FILES,
    OPEN_FILES,
    CURRENT_FILE,
}

@Schema
@Serializable
internal data class ScopeSdkCatalogIntentResultDto(
    @Description("Selection intent for reducing catalog candidates.")
    val intent: ScopeSdkCatalogIntent,
    val recommendedScopeRefId: String? = null,
    val items: List<ScopeCatalogItemDto>,
    val diagnostics: List<String> = emptyList(),
)

@Schema
@Serializable


internal class ScopeDescriptorTools {
    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeListCatalog(
        @Description("Whether to include scopes that depend on current UI context.")
        includeInteractiveScopes: Boolean = true,
    ): CallToolResult {
        val project = call.project()
        val result = ScopeCatalogService.getInstance(project).listCatalog(project, includeInteractiveScopes)
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeGetDefaultDescriptor(
        @Description("Preset scope to use.")
        preset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
        @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        val descriptor = buildPresetScopeDescriptor(
            project = project,
            preset = preset,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        val result = ScopeResolveResultDto(descriptor = descriptor)
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeResolveStandardDescriptor(
        @Description("Standard scope id, for example 'Project Files' or 'All Places'.")
        standardScopeId: String,
        @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        if (standardScopeId.isBlank()) {
            return CallToolResult(
                content = listOf(TextContent(text = "standardScopeId must not be blank.")),
                isError = true,
            )
        }
        val project = call.project()
        val descriptor = buildStandardScopeDescriptor(
            project = project,
            standardScopeId = standardScopeId,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        val result = ScopeResolveResultDto(descriptor = descriptor)
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeCatalogFindByIntent(
        @Description("Selection intent for reducing catalog candidates.")
        intent: ScopeSdkCatalogIntent,
        @Description("Maximum number of catalog items to return.")
        maxResults: Int = 20,
        @Description("Whether to include scopes that depend on current UI context.")
        includeInteractiveScopes: Boolean = false,
    ): CallToolResult {
        if (maxResults < 1) {
            return CallToolResult(
                content = listOf(TextContent(text = "maxResults must be >= 1.")),
                isError = true,
            )
        }
        val project = call.project()
        val catalog = ScopeCatalogService.getInstance(project).listCatalog(project, includeInteractiveScopes)
        val candidates = catalog.items.filter { item ->
            val serializationId = item.serializationId
            when (intent) {
                ScopeSdkCatalogIntent.PROJECT_ONLY -> serializationId == "Project Files" ||
                        serializationId == "Project Production Files" ||
                        serializationId == "Project Test Files" ||
                        item.kind == ScopeAtomKind.MODULE

                ScopeSdkCatalogIntent.WITH_LIBRARIES -> serializationId == "All Places" ||
                        serializationId == "Project and Libraries" ||
                        item.moduleFlavor == ModuleScopeFlavor.MODULE_WITH_LIBRARIES ||
                        item.moduleFlavor == ModuleScopeFlavor.MODULE_WITH_DEPENDENCIES_AND_LIBRARIES

                ScopeSdkCatalogIntent.CHANGED_FILES -> serializationId == "Recently Changed Files"
                ScopeSdkCatalogIntent.OPEN_FILES -> serializationId == "Open Files"
                ScopeSdkCatalogIntent.CURRENT_FILE -> serializationId == "Current File"
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
                add("No catalog items matched intent=${intent.name}.")
            }
            if (truncated) {
                add("Matched catalog items exceed maxResults=${maxResults}; result was truncated.")
            }
        }.distinct()
        val result = ScopeSdkCatalogIntentResultDto(
            intent = intent,
            recommendedScopeRefId = recommended,
            items = selected,
            diagnostics = diagnostics,
        )
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeValidatePattern(
        @Description("Scope pattern text in IntelliJ PackageSet syntax.")
        patternText: String,
    ): CallToolResult {
        val project = call.project()
        val result = ScopeResolverService.getInstance(project).validatePattern(patternText)
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeResolveProgram(
        @Description("Compile request with atoms and RPN tokens.")
        request: ScopeResolveRequestDto,
    ): CallToolResult {
        val project = call.project()
        val descriptor = ScopeResolverService.getInstance(project).compileProgramDescriptor(project, request)
        val result = ScopeResolveResultDto(descriptor = descriptor)
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeNormalizeProgramDescriptor(
        @Description("Existing scope descriptor to normalize.")
        descriptor: ScopeProgramDescriptorDto,
        @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        val request = ScopeResolveRequestDto(
            atoms = descriptor.atoms,
            tokens = descriptor.tokens,
            strict = true,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
            nonStrictDefaultFailureMode = ScopeAtomFailureMode.EMPTY_SCOPE,
        )
        val normalized = ScopeResolverService.getInstance(project).compileProgramDescriptor(project, request)
        val result = ScopeDescribeProgramResultDto(
            descriptor = normalized.copy(
                diagnostics = (descriptor.diagnostics + normalized.diagnostics).distinct(),
            ),
        )
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeContainsFile(
        @Description("VFS URL of the file to check.")
        fileUrl: String,
        @Description("Scope program descriptor.")
        scope: ScopeProgramDescriptorDto,
        @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        val resolver = ScopeResolverService.getInstance(project)
        val resolved = resolver.resolveDescriptor(
            project = project,
            descriptor = scope,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        val file = findFileByUrlWithRefresh(fileUrl) ?: return CallToolResult(
            content = listOf(TextContent(text = "File URL '$fileUrl' not found.")),
            isError = true,
        )
        if (file.isDirectory) {
            return CallToolResult(
                content = listOf(TextContent(text = "URL '$fileUrl' points to a directory, not a file.")),
                isError = true,
            )
        }
        val matches = readAction { resolved.scope.contains(file) }
        val result = ScopeContainsFileResultDto(
            fileUrl = fileUrl,
            matches = matches,
            scopeDisplayName = resolved.displayName,
            scopeShape = resolved.scopeShape,
            diagnostics = (scope.diagnostics + resolved.diagnostics).distinct(),
        )
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeFilterFiles(
        @Description("Input file URLs to test against the scope.")
        fileUrls: List<String>,
        @Description("Scope program descriptor.")
        scope: ScopeProgramDescriptorDto,
        @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        val resolver = ScopeResolverService.getInstance(project)
        val resolved = resolver.resolveDescriptor(
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
        val result = ScopeFilterFilesResultDto(
            scopeDisplayName = resolved.displayName,
            scopeShape = resolved.scopeShape,
            matchedFileUrls = matched,
            excludedFileUrls = excluded,
            missingFileUrls = missing,
            diagnostics = (scope.diagnostics + resolved.diagnostics + diagnostics).distinct(),
        )
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}
