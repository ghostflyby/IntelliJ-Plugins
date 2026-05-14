/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.descriptor.tools

import com.intellij.openapi.application.readAction
import dev.ghostflyby.mcp.common.findFileByUrlWithRefresh
import dev.ghostflyby.mcp.scope.ModuleScopeFlavor
import dev.ghostflyby.mcp.scope.ScopeAtomFailureMode
import dev.ghostflyby.mcp.scope.ScopeAtomKind
import dev.ghostflyby.mcp.scope.ScopeCatalogItemDto
import dev.ghostflyby.mcp.scope.ScopeCatalogResultDto
import dev.ghostflyby.mcp.scope.ScopeCatalogService
import dev.ghostflyby.mcp.scope.ScopeDescribeProgramResultDto
import dev.ghostflyby.mcp.scope.ScopeProgramDescriptorDto
import dev.ghostflyby.mcp.scope.ScopeProgramOp
import dev.ghostflyby.mcp.scope.ScopeProgramTokenDto
import dev.ghostflyby.mcp.scope.ScopeQuickPreset
import dev.ghostflyby.mcp.scope.ScopeResolveRequestDto
import dev.ghostflyby.mcp.scope.ScopeResolveResultDto
import dev.ghostflyby.mcp.scope.ScopeResolverService
import dev.ghostflyby.mcp.scope.ScopeContainsFileResultDto
import dev.ghostflyby.mcp.scope.buildPresetScopeDescriptor
import dev.ghostflyby.mcp.scope.buildStandardScopeDescriptor

import dev.ghostflyby.mcp.sdk.tools.SdkToolDescriptor
import dev.ghostflyby.mcp.sdk.tools.SdkToolHandlerContext
import dev.ghostflyby.mcp.sdk.tools.WorkspaceMcpProjectToolArguments
import dev.ghostflyby.mcp.sdk.tools.sdkBooleanProperty
import dev.ghostflyby.mcp.sdk.tools.sdkIntegerProperty
import dev.ghostflyby.mcp.sdk.tools.sdkObjectProperty
    import dev.ghostflyby.mcp.sdk.tools.sdkStringProperty
    import dev.ghostflyby.mcp.sdk.tools.sdkToolDescriptor
    import dev.ghostflyby.mcp.sdk.tools.toolSchema
    import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Local serializable enum for catalog intent; duplicates the shape from
 * [ScopeDescriptorMcpTools.ScopeCatalogIntent] to avoid depending on annotation-tool internals.
 */
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
@Serializable
internal data class ScopeSdkCatalogIntentResultDto(
    val intent: ScopeSdkCatalogIntent,
    val recommendedScopeRefId: String? = null,
    val items: List<ScopeCatalogItemDto>,
    val diagnostics: List<String> = emptyList(),
)

// ── scope_list_catalog ──────────────────────────────────────────

@Serializable
internal data class ScopeListCatalogArgs(
    val includeInteractiveScopes: Boolean = true,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeListCatalogSdkTool(): SdkToolDescriptor<ScopeListCatalogArgs> {
    return sdkToolDescriptor<ScopeListCatalogArgs>(
        name = "scope_list_catalog",
        description = "List available search scopes (Find-like catalog) with stable scopeRefId and metadata.",
        inputSchema = toolSchema(
            properties = mapOf(
                "includeInteractiveScopes" to sdkBooleanProperty(
                    "Whether to include scopes that depend on current UI context (for example Current File).",
                ),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty(
                    "Absolute project base path for project-scoped resolution (optional).",
                ),
            ),
        ),
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

@Serializable
internal data class ScopeGetDefaultDescriptorArgs(
    val preset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeGetDefaultDescriptorSdkTool(): SdkToolDescriptor<ScopeGetDefaultDescriptorArgs> {
    return sdkToolDescriptor<ScopeGetDefaultDescriptorArgs>(
        name = "scope_get_default_descriptor",
        description = "Return a ready-to-use default scope descriptor by preset, avoiding catalog+program assembly on first call.",
        inputSchema = toolSchema(
            properties = mapOf(
                "preset" to sdkStringProperty(
                    "Preset scope to use. One of: PROJECT_FILES, ALL_PLACES, OPEN_FILES, " +
                        "PROJECT_AND_LIBRARIES, PROJECT_PRODUCTION_FILES, PROJECT_TEST_FILES.",
                ),
                "allowUiInteractiveScopes" to sdkBooleanProperty(
                    "Whether UI-interactive scopes are allowed during descriptor resolution.",
                ),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty(
                    "Absolute project base path for project-scoped resolution (optional).",
                ),
            ),
        ),
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

@Serializable
internal data class ScopeResolveStandardDescriptorArgs(
    val standardScopeId: String,
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeResolveStandardDescriptorSdkTool(): SdkToolDescriptor<ScopeResolveStandardDescriptorArgs> {
    return sdkToolDescriptor<ScopeResolveStandardDescriptorArgs>(
        name = "scope_resolve_standard_descriptor",
        description = "Resolve a standard IDE scope id directly to a normalized reusable descriptor.",
        inputSchema = toolSchema(
            properties = mapOf(
                "standardScopeId" to sdkStringProperty(
                    "Standard scope id, for example 'Project Files' or 'All Places'.",
                ),
                "allowUiInteractiveScopes" to sdkBooleanProperty(
                    "Whether UI-interactive scopes are allowed during descriptor resolution.",
                ),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty(
                    "Absolute project base path for project-scoped resolution (optional).",
                ),
            ),
            required = listOf("standardScopeId"),
        ),
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

@Serializable
internal data class ScopeCatalogFindByIntentArgs(
    val intent: ScopeSdkCatalogIntent,
    val maxResults: Int = 20,
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
        inputSchema = toolSchema(
            properties = mapOf(
                "intent" to sdkStringProperty(
                    "Selection intent for reducing catalog candidates. " +
                        "One of: PROJECT_ONLY, WITH_LIBRARIES, CHANGED_FILES, OPEN_FILES, CURRENT_FILE.",
                ),
                "maxResults" to sdkIntegerProperty("Maximum number of catalog items to return."),
                "includeInteractiveScopes" to sdkBooleanProperty(
                    "Whether to include scopes that depend on current UI context.",
                ),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty(
                    "Absolute project base path for project-scoped resolution (optional).",
                ),
            ),
            required = listOf("intent"),
        ),
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

@Serializable
internal data class ScopeValidatePatternArgs(
    val patternText: String,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeValidatePatternSdkTool(): SdkToolDescriptor<ScopeValidatePatternArgs> {
    return sdkToolDescriptor<ScopeValidatePatternArgs>(
        name = "scope_validate_pattern",
        description = "Validate a PackageSet pattern text used by IntelliJ scopes.",
        inputSchema = toolSchema(
            properties = mapOf(
                "patternText" to sdkStringProperty("Scope pattern text in IntelliJ PackageSet syntax."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty(
                    "Absolute project base path for project-scoped resolution (optional).",
                ),
            ),
            required = listOf("patternText"),
        ),
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

@Serializable
internal data class ScopeResolveProgramArgs(
    val request: ScopeResolveRequestDto,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeResolveProgramSdkTool(): SdkToolDescriptor<ScopeResolveProgramArgs> {
    return sdkToolDescriptor<ScopeResolveProgramArgs>(
        name = "scope_resolve_program",
        description = "Compile and normalize scope atoms and RPN tokens into a reusable scope descriptor.",
        inputSchema = toolSchema(
            properties = mapOf(
                "request" to sdkObjectProperty(
                    "Scope resolve request with atoms, RPN tokens, strict mode, and failure configuration.",
                ),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty(
                    "Absolute project base path for project-scoped resolution (optional).",
                ),
            ),
            required = listOf("request"),
        ),
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

@Serializable
internal data class ScopeNormalizeDescriptorArgs(
    val descriptor: ScopeProgramDescriptorDto,
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeNormalizeProgramDescriptorSdkTool(): SdkToolDescriptor<ScopeNormalizeDescriptorArgs> {
    return sdkToolDescriptor<ScopeNormalizeDescriptorArgs>(
        name = "scope_normalize_program_descriptor",
        description = "Normalize and recompile an existing scope descriptor, " +
            "useful for migration and compatibility upgrades.",
        inputSchema = toolSchema(
            properties = mapOf(
                "descriptor" to sdkObjectProperty("The scope program descriptor to normalize."),
                "allowUiInteractiveScopes" to sdkBooleanProperty(
                    "Whether UI-interactive scopes are allowed during descriptor resolution.",
                ),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty(
                    "Absolute project base path for project-scoped resolution (optional).",
                ),
            ),
            required = listOf("descriptor"),
        ),
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

@Serializable
internal data class ScopeContainsFileArgs(
    val fileUrl: String,
    val `scope`: ScopeProgramDescriptorDto,
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeContainsFileSdkTool(): SdkToolDescriptor<ScopeContainsFileArgs> {
    return sdkToolDescriptor<ScopeContainsFileArgs>(
        name = "scope_contains_file",
        description = "Check whether a file URL belongs to a resolved scope descriptor.",
        inputSchema = toolSchema(
            properties = mapOf(
                "fileUrl" to sdkStringProperty(
                    "Target VFS URL. returned from other mcp calls, or constructed by the caller.",
                ),
                "scope" to sdkObjectProperty("The scope program descriptor to test membership against."),
                "allowUiInteractiveScopes" to sdkBooleanProperty(
                    "Whether UI-interactive scopes are allowed during descriptor resolution.",
                ),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty(
                    "Absolute project base path for project-scoped resolution (optional).",
                ),
            ),
            required = listOf("fileUrl", "scope"),
        ),
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
