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

package dev.ghostflyby.mcp.scope.descriptor.tools

import com.intellij.openapi.application.readAction
import dev.ghostflyby.mcp.common.findFileByUrlWithRefresh
import dev.ghostflyby.mcp.scope.*
import dev.ghostflyby.mcp.sdk.tools.*
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable

/**
 * Local serializable enum for catalog intent; duplicates the shape from
 * [ScopeSdkCatalogIntent] to avoid depending on annotation-tool internals.
 */
@Schema
@Serializable
internal enum class ScopeSdkCatalogIntent {
    PROJECT_ONLY,
    WITH_LIBRARIES,
    CHANGED_FILES,
    OPEN_FILES,
    CURRENT_FILE,
}

/**
 * Local result DTO for catalog-by-intent lookup.
 */
@Schema
@Serializable
internal data class ScopeSdkCatalogIntentResultDto(
    @Description("Selection intent for reducing catalog candidates. One of: PROJECT_ONLY, WITH_LIBRARIES, CHANGED_FILES, OPEN_FILES, CURRENT_FILE.")
    val intent: ScopeSdkCatalogIntent,
    val recommendedScopeRefId: String? = null,
    val items: List<ScopeCatalogItemDto>,
    val diagnostics: List<String> = emptyList(),
)

// ── scope_list_catalog ──────────────────────────────────────────

@Description("Arguments for ScopeListCatalogArgs")
@Schema
@Serializable
internal data class ScopeListCatalogArgs(
    @Description("Whether to include scopes that depend on current UI context.")
    val includeInteractiveScopes: Boolean = true,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeListCatalogSdkTool(): SdkToolDescriptor<ScopeListCatalogArgs> {
    return sdkToolDescriptor<ScopeListCatalogArgs>(
        name = "scope_list_catalog",
        description = "List available search scopes (Find-like catalog) with stable scopeRefId and metadata.",
        handler = { args -> scopeListCatalogHandler(this, args) },
    )
}

private suspend fun scopeListCatalogHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeListCatalogArgs,
): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        val result = ScopeCatalogService.getInstance(project).listCatalog(project, args.includeInteractiveScopes)
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── scope_get_default_descriptor ─────────────────────────────────

@Description("Arguments for ScopeGetDefaultDescriptorArgs")
@Schema
@Serializable
internal data class ScopeGetDefaultDescriptorArgs(
    @Description("Preset scope to use. One of: PROJECT_FILES, ALL_PLACES, OPEN_FILES, PROJECT_AND_LIBRARIES, PROJECT_PRODUCTION_FILES, PROJECT_TEST_FILES.")
    val preset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
    @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeGetDefaultDescriptorSdkTool(): SdkToolDescriptor<ScopeGetDefaultDescriptorArgs> {
    return sdkToolDescriptor<ScopeGetDefaultDescriptorArgs>(
        name = "scope_get_default_descriptor",
        description = "Return a ready-to-use default scope descriptor by preset, avoiding catalog+program assembly on first call.",
        handler = { args -> scopeGetDefaultDescriptorHandler(this, args) },
    )
}

private suspend fun scopeGetDefaultDescriptorHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeGetDefaultDescriptorArgs,
): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        val descriptor = buildPresetScopeDescriptor(
            project = project,
            preset = args.preset,
            allowUiInteractiveScopes = args.allowUiInteractiveScopes,
        )
        val result = ScopeResolveResultDto(descriptor = descriptor)
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── scope_resolve_standard_descriptor ────────────────────────────

@Description("Arguments for ScopeResolveStandardDescriptorArgs")
@Schema
@Serializable
internal data class ScopeResolveStandardDescriptorArgs(
    @Description("Standard scope id, for example 'Project Files' or 'All Places'.")
    val standardScopeId: String,
    @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeResolveStandardDescriptorSdkTool(): SdkToolDescriptor<ScopeResolveStandardDescriptorArgs> {
    return sdkToolDescriptor<ScopeResolveStandardDescriptorArgs>(
        name = "scope_resolve_standard_descriptor",
        description = "Resolve a standard IDE scope id directly to a normalized reusable descriptor.",
        handler = { args -> scopeResolveStandardDescriptorHandler(this, args) },
    )
}

private suspend fun scopeResolveStandardDescriptorHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeResolveStandardDescriptorArgs,
): CallToolResult {
    if (args.standardScopeId.isBlank()) {
        return CallToolResult(
            content = listOf(TextContent(text = "standardScopeId must not be blank.")),
            isError = true,
        )
    }
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        val descriptor = buildStandardScopeDescriptor(
            project = project,
            standardScopeId = args.standardScopeId,
            allowUiInteractiveScopes = args.allowUiInteractiveScopes,
        )
        val result = ScopeResolveResultDto(descriptor = descriptor)
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── scope_catalog_find_by_intent ─────────────────────────────────

@Description("Arguments for ScopeCatalogFindByIntentArgs")
@Schema
@Serializable
internal data class ScopeCatalogFindByIntentArgs(
    @Description("Selection intent for reducing catalog candidates. One of: PROJECT_ONLY, WITH_LIBRARIES, CHANGED_FILES, OPEN_FILES, CURRENT_FILE.")
    val intent: ScopeSdkCatalogIntent,
    @Description("Maximum number of catalog items to return.")
    val maxResults: Int = 20,
    @Description("Whether to include scopes that depend on current UI context.")
    val includeInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeCatalogFindByIntentSdkTool(): SdkToolDescriptor<ScopeCatalogFindByIntentArgs> {
    return sdkToolDescriptor<ScopeCatalogFindByIntentArgs>(
        name = "scope_catalog_find_by_intent",
        description = "Find a compact scope catalog subset by intent " +
                "(project-only, with-libraries, changed/open/current file) " +
                "to reduce first-call catalog payload.",
        handler = { args -> scopeCatalogFindByIntentHandler(this, args) },
    )
}

private suspend fun scopeCatalogFindByIntentHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeCatalogFindByIntentArgs,
): CallToolResult {
    if (args.maxResults < 1) {
        return CallToolResult(
            content = listOf(TextContent(text = "maxResults must be >= 1.")),
            isError = true,
        )
    }
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        val catalog = ScopeCatalogService.getInstance(project).listCatalog(project, args.includeInteractiveScopes)
        val candidates = catalog.items.filter { item ->
            val serializationId = item.serializationId
            when (args.intent) {
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
        val truncated = sorted.size > args.maxResults
        val selected = sorted.take(args.maxResults)
        val recommended = selected.firstOrNull()?.scopeRefId
        val diagnostics = buildList {
            addAll(catalog.diagnostics)
            if (selected.isEmpty()) {
                add("No catalog items matched intent=${args.intent.name}. Consider includeInteractiveScopes=true or a different intent.")
            }
            if (truncated) {
                add("Matched catalog items exceed maxResults=${args.maxResults}; result was truncated.")
            }
        }.distinct()
        val result = ScopeSdkCatalogIntentResultDto(
            intent = args.intent,
            recommendedScopeRefId = recommended,
            items = selected,
            diagnostics = diagnostics,
        )
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── scope_validate_pattern ───────────────────────────────────────

@Description("Arguments for ScopeValidatePatternArgs")
@Schema
@Serializable
internal data class ScopeValidatePatternArgs(
    @Description("Scope pattern text in IntelliJ PackageSet syntax.")
    val patternText: String,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeValidatePatternSdkTool(): SdkToolDescriptor<ScopeValidatePatternArgs> {
    return sdkToolDescriptor<ScopeValidatePatternArgs>(
        name = "scope_validate_pattern",
        description = "Validate a PackageSet pattern text used by IntelliJ scopes.",
        handler = { args -> scopeValidatePatternHandler(this, args) },
    )
}

private suspend fun scopeValidatePatternHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeValidatePatternArgs,
): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        val result = ScopeResolverService.getInstance(project).validatePattern(args.patternText)
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── scope_resolve_program ────────────────────────────────────────

@Description("Arguments for ScopeResolveProgramArgs")
@Schema
@Serializable
internal data class ScopeResolveProgramArgs(
    @Description("Compile request with atoms and RPN tokens.")
    val request: ScopeResolveRequestDto,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeResolveProgramSdkTool(): SdkToolDescriptor<ScopeResolveProgramArgs> {
    return sdkToolDescriptor<ScopeResolveProgramArgs>(
        name = "scope_resolve_program",
        description = "Compile and normalize scope atoms and RPN tokens into a reusable scope descriptor.",
        handler = { args -> scopeResolveProgramHandler(this, args) },
    )
}

private suspend fun scopeResolveProgramHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeResolveProgramArgs,
): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        val descriptor = ScopeResolverService.getInstance(project).compileProgramDescriptor(project, args.request)
        val result = ScopeResolveResultDto(descriptor = descriptor)
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── scope_normalize_program_descriptor ───────────────────────────

@Description("Arguments for ScopeNormalizeDescriptorArgs")
@Schema
@Serializable
internal data class ScopeNormalizeDescriptorArgs(
    @Description("Existing scope descriptor to normalize.")
    val descriptor: ScopeProgramDescriptorDto,
    @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeNormalizeProgramDescriptorSdkTool(): SdkToolDescriptor<ScopeNormalizeDescriptorArgs> {
    return sdkToolDescriptor<ScopeNormalizeDescriptorArgs>(
        name = "scope_normalize_program_descriptor",
        description = "Normalize and recompile an existing scope descriptor, " +
                "useful for migration and compatibility upgrades.",
        handler = { args -> scopeNormalizeDescriptorHandler(this, args) },
    )
}

private suspend fun scopeNormalizeDescriptorHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeNormalizeDescriptorArgs,
): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        val request = ScopeResolveRequestDto(
            atoms = args.descriptor.atoms,
            tokens = args.descriptor.tokens,
            strict = true,
            allowUiInteractiveScopes = args.allowUiInteractiveScopes,
            nonStrictDefaultFailureMode = ScopeAtomFailureMode.EMPTY_SCOPE,
        )
        val normalized = ScopeResolverService.getInstance(project).compileProgramDescriptor(project, request)
        val result = ScopeDescribeProgramResultDto(
            descriptor = normalized.copy(
                diagnostics = (args.descriptor.diagnostics + normalized.diagnostics).distinct(),
            ),
        )
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── scope_contains_file ──────────────────────────────────────────

@Description("Arguments for ScopeContainsFileArgs")
@Schema
@Serializable
internal data class ScopeContainsFileArgs(
    @Description("VFS URL of the file to check.")
    val fileUrl: String,
    @Description("Scope program descriptor.")
    val `scope`: ScopeProgramDescriptorDto,
    @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeContainsFileSdkTool(): SdkToolDescriptor<ScopeContainsFileArgs> {
    return sdkToolDescriptor<ScopeContainsFileArgs>(
        name = "scope_contains_file",
        description = "Check whether a file URL belongs to a resolved scope descriptor.",
        handler = { args -> scopeContainsFileHandler(this, args) },
    )
}

private suspend fun scopeContainsFileHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeContainsFileArgs,
): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        val resolver = ScopeResolverService.getInstance(project)
        val resolved = resolver.resolveDescriptor(
            project = project,
            descriptor = args.scope,
            allowUiInteractiveScopes = args.allowUiInteractiveScopes,
        )
        val file = findFileByUrlWithRefresh(args.fileUrl)
        if (file == null) {
            return@callToolWithProject CallToolResult(
                content = listOf(TextContent(text = "File URL '${args.fileUrl}' not found.")),
                isError = true,
            )
        }
        if (file.isDirectory) {
            return@callToolWithProject CallToolResult(
                content = listOf(TextContent(text = "URL '${args.fileUrl}' points to a directory, not a file.")),
                isError = true,
            )
        }
        val matches = readAction { resolved.scope.contains(file) }
        val result = ScopeContainsFileResultDto(
            fileUrl = args.fileUrl,
            matches = matches,
            scopeDisplayName = resolved.displayName,
            scopeShape = resolved.scopeShape,
            diagnostics = (args.scope.diagnostics + resolved.diagnostics).distinct(),
        )
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── scope_filter_files ──────────────────────────────────────────

@Description("Arguments for ScopeFilterFilesArgs")
@Schema
@Serializable
internal data class ScopeFilterFilesArgs(
    @Description("Input file URLs to test against the scope.")
    val fileUrls: List<String>,
    @Description("Scope program descriptor.")
    val `scope`: ScopeProgramDescriptorDto,
    @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeFilterFilesSdkTool(): SdkToolDescriptor<ScopeFilterFilesArgs> {
    return sdkToolDescriptor<ScopeFilterFilesArgs>(
        name = "scope_filter_files",
        description = "Filter file URLs by whether they belong to a resolved scope descriptor.",
        handler = { args -> scopeFilterFilesHandler(this, args) },
    )
}

private suspend fun scopeFilterFilesHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeFilterFilesArgs,
): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        val resolver = ScopeResolverService.getInstance(project)
        val resolved = resolver.resolveDescriptor(
            project = project,
            descriptor = args.scope,
            allowUiInteractiveScopes = args.allowUiInteractiveScopes,
        )

        val diagnostics = mutableListOf<String>()
        val matched = mutableListOf<String>()
        val excluded = mutableListOf<String>()
        val missing = mutableListOf<String>()

        args.fileUrls.forEach { url ->
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
            diagnostics = (args.scope.diagnostics + resolved.diagnostics + diagnostics).distinct(),
        )
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}
