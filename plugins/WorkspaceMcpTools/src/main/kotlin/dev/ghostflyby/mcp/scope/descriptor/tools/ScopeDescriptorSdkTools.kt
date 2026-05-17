/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.descriptor.tools

import com.intellij.openapi.application.readAction
import dev.ghostflyby.mcp.common.findFileByUrlWithRefresh
import dev.ghostflyby.mcp.scope.*
import dev.ghostflyby.mcp.sdk.callToolWithProject
import dev.ghostflyby.mcp.sdk.tools.WorkspaceMcpProjectToolArguments
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Request
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
internal data class ScopeListCatalogArgs(
    @Description("Whether to include scopes that depend on current UI context.")
    val includeInteractiveScopes: Boolean = true,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal suspend fun ClientConnection.scopeListCatalogHandler(args: ScopeListCatalogArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        projectArgs = args,
    ) { project ->
        val result = ScopeCatalogService.getInstance(project).listCatalog(project, args.includeInteractiveScopes)
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

@Schema
@Serializable
internal data class ScopeGetDefaultDescriptorArgs(
    @Description("Preset scope to use.")
    val preset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
    @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal suspend fun ClientConnection.scopeGetDefaultDescriptorHandler(args: ScopeGetDefaultDescriptorArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        projectArgs = args,
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

internal suspend fun ClientConnection.scopeResolveStandardDescriptorHandler(args: ScopeResolveStandardDescriptorArgs, request: Request?): CallToolResult {
    if (args.standardScopeId.isBlank()) {
        return CallToolResult(
            content = listOf(TextContent(text = "standardScopeId must not be blank.")),
            isError = true,
        )
    }
    return callToolWithProject(
        projectArgs = args,
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

@Schema
@Serializable
internal data class ScopeCatalogFindByIntentArgs(
    @Description("Selection intent for reducing catalog candidates.")
    val intent: ScopeSdkCatalogIntent,
    @Description("Maximum number of catalog items to return.")
    val maxResults: Int = 20,
    @Description("Whether to include scopes that depend on current UI context.")
    val includeInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal suspend fun ClientConnection.scopeCatalogFindByIntentHandler(args: ScopeCatalogFindByIntentArgs, request: Request?): CallToolResult {
    if (args.maxResults < 1) {
        return CallToolResult(
            content = listOf(TextContent(text = "maxResults must be >= 1.")),
            isError = true,
        )
    }
    return callToolWithProject(
        projectArgs = args,
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
                add("No catalog items matched intent=${args.intent.name}.")
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

@Schema
@Serializable
internal data class ScopeValidatePatternArgs(
    @Description("Scope pattern text in IntelliJ PackageSet syntax.")
    val patternText: String,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal suspend fun ClientConnection.scopeValidatePatternHandler(args: ScopeValidatePatternArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        projectArgs = args,
    ) { project ->
        val result = ScopeResolverService.getInstance(project).validatePattern(args.patternText)
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

@Schema
@Serializable
internal data class ScopeResolveProgramArgs(
    @Description("Compile request with atoms and RPN tokens.")
    val request: ScopeResolveRequestDto,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal suspend fun ClientConnection.scopeResolveProgramHandler(args: ScopeResolveProgramArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        projectArgs = args,
    ) { project ->
        val descriptor = ScopeResolverService.getInstance(project).compileProgramDescriptor(project, args.request)
        val result = ScopeResolveResultDto(descriptor = descriptor)
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

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

internal suspend fun ClientConnection.scopeNormalizeDescriptorHandler(args: ScopeNormalizeDescriptorArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        projectArgs = args,
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

internal suspend fun ClientConnection.scopeContainsFileHandler(args: ScopeContainsFileArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        projectArgs = args,
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

internal suspend fun ClientConnection.scopeFilterFilesHandler(args: ScopeFilterFilesArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        projectArgs = args,
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
