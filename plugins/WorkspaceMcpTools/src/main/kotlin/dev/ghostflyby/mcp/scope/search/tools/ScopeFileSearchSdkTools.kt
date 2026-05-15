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

package dev.ghostflyby.mcp.scope.search.tools

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.common.AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX
import dev.ghostflyby.mcp.common.MCP_FIRST_LIBRARY_QUERY_POLICY_DESCRIPTION_SUFFIX
import dev.ghostflyby.mcp.common.findFileByUrlWithRefresh
import dev.ghostflyby.mcp.common.reportActivity
import dev.ghostflyby.mcp.scope.*
import dev.ghostflyby.mcp.sdk.tools.*
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.*
import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

private val WHITESPACE_REGEX = Regex("\\s+")

// ── Tool registration entrypoint ─────────────────────────────────

internal fun scopeFileSearchSdkTools(): List<SdkToolDescriptor<*>> {
    return listOf(
        scopeFileSearchTool(),
        scopeFileSearchQuickTool(),
        scopeFindFilesByNameTool(),
        scopeFindFilesByPathTool(),
        scopeFindInDirectoryGlobTool(),
        scopeFindSourceFileByClassNameTool(),
    )
}

// ── scope_search_files ────────────────────────────────────────────

@Description("Arguments for ScopeFileSearchArgs")
@Schema
@Serializable
internal data class ScopeFileSearchArgs(
    val query: String = "",
    @Description("Optional explicit ordered keywords.")
    val keywords: List<String> = emptyList(),
    @Description("Scope program descriptor.")
    val `scope`: ScopeProgramDescriptorDto,
    @Description("Search mode: NAME, PATH, NAME_OR_PATH, or GLOB.")
    val matchMode: ScopeFileSearchMode = ScopeFileSearchMode.NAME_OR_PATH,
    @Description("Whether matching is case-sensitive.")
    val caseSensitive: Boolean = false,
    @Description("VFS directory URL to search within.")
    val directoryUrl: String? = null,
    @Description("Maximum number of matched files to return.")
    val maxResults: Int = 1000,
    @Description("Timeout in milliseconds.")
    val timeoutMillis: Int = 30000,
    @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeFileSearchTool(): SdkToolDescriptor<ScopeFileSearchArgs> {
    return sdkToolDescriptor<ScopeFileSearchArgs>(
        name = "scope_search_files",
        description = "Search files by name/path text or glob within a scope descriptor. " +
                "Returns matching file URLs and search diagnostics. " +
                "When directoryUrl is provided, this tool traverses that VFS subtree directly " +
                "(including jar:// ZIP/JAR roots such as Gradle cache source archives). " +
                "Prefer this over shell commands in most cases.",
        handler = { args -> scopeFileSearchHandler(this, args) },
    )
}

private suspend fun scopeFileSearchHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeFileSearchArgs,
): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        if (args.maxResults < 1) {
            return@callToolWithProject CallToolResult(
                content = listOf(TextContent(text = "maxResults must be >= 1.")),
                isError = true,
            )
        }
        if (args.timeoutMillis < 1) {
            return@callToolWithProject CallToolResult(
                content = listOf(TextContent(text = "timeoutMillis must be >= 1.")),
                isError = true,
            )
        }
        val normalizedSearchInput = normalizeSearchInput(args.matchMode, args.query, args.keywords)

        reportActivity(
            Bundle.message(
                "tool.activity.scope.search.files.start",
                args.matchMode.name,
                normalizedSearchInput.textKeywords.size,
                args.maxResults,
                args.timeoutMillis,
            ),
        )

        val resolved = ScopeResolverService.getInstance(project).resolveDescriptor(
            project = project,
            descriptor = args.scope,
            allowUiInteractiveScopes = args.allowUiInteractiveScopes,
        )
        val projectRootPath = project.basePath?.let { Path.of(it) }
        val rootDirectory = args.directoryUrl?.let { resolveDirectory(it) }
        val (globMatcher, lowerCaseGlobMatcher) = buildGlobMatchers(
            args.matchMode,
            normalizedSearchInput.queryForDisplay,
            args.caseSensitive,
        )
        val normalizedKeywords = if (args.caseSensitive) {
            normalizedSearchInput.textKeywords
        } else {
            normalizedSearchInput.textKeywords.map { it.lowercase() }
        }

        val scannedCounter = AtomicInteger(0)
        val matchedCounter = AtomicInteger(0)
        val finished = AtomicBoolean(false)
        val matched = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        var timedOut = false
        var probablyHasMoreMatchingFiles = false
        val indexSearchScope = resolved.scope as? GlobalSearchScope
        val useFilenameIndexForName = rootDirectory == null &&
                args.matchMode == ScopeFileSearchMode.NAME &&
                resolved.scopeShape == ScopeShape.GLOBAL &&
                indexSearchScope != null

        coroutineScope {
            val progressJob = launch {
                while (isActive && !finished.get()) {
                    delay(800)
                    reportActivity(
                        Bundle.message(
                            "tool.activity.scope.search.files.progress",
                            scannedCounter.get(),
                            matchedCounter.get(),
                        ),
                    )
                }
            }
            try {
                timedOut = withTimeoutOrNull(args.timeoutMillis.milliseconds) {
                    withBackgroundProgress(
                        project,
                        Bundle.message("progress.title.scope.search.files", normalizedSearchInput.queryForDisplay),
                        cancellable = true,
                    ) {
                        if (useFilenameIndexForName) {
                            probablyHasMoreMatchingFiles = scanByFilenameIndex(
                                searchScope = indexSearchScope,
                                rootDirectory = rootDirectory,
                                normalizedKeywords = normalizedKeywords,
                                caseSensitive = args.caseSensitive,
                                maxResults = args.maxResults,
                                scannedCounter = scannedCounter,
                                matchedCounter = matchedCounter,
                                matched = matched,
                            )
                        } else {
                            if (args.matchMode == ScopeFileSearchMode.NAME && resolved.scopeShape != ScopeShape.GLOBAL) {
                                diagnostics += "NAME mode uses traversal fallback because resolved scope shape is ${resolved.scopeShape.name}."
                            }
                            probablyHasMoreMatchingFiles = scanByTraversal(
                                project = project,
                                resolvedScope = resolved.scope,
                                rootDirectory = rootDirectory,
                                mode = args.matchMode,
                                normalizedKeywords = normalizedKeywords,
                                caseSensitive = args.caseSensitive,
                                projectRootPath = projectRootPath,
                                globMatcher = globMatcher,
                                lowerCaseGlobMatcher = lowerCaseGlobMatcher,
                                maxResults = args.maxResults,
                                scannedCounter = scannedCounter,
                                matchedCounter = matchedCounter,
                                matched = matched,
                            )
                        }
                    }
                    false
                } ?: true
            } finally {
                finished.set(true)
                progressJob.cancel()
            }
        }

        val result = ScopeFileSearchResultDto(
            scopeDisplayName = resolved.displayName,
            scopeShape = resolved.scopeShape,
            mode = args.matchMode,
            query = args.query,
            keywords = normalizedSearchInput.textKeywords,
            directoryUrl = args.directoryUrl,
            matchedFileUrls = matched,
            scannedFileCount = scannedCounter.get(),
            probablyHasMoreMatchingFiles = probablyHasMoreMatchingFiles,
            timedOut = timedOut,
            canceled = false,
            diagnostics = (args.scope.diagnostics + resolved.diagnostics + diagnostics).distinct(),
        )
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── scope_search_files_quick ──────────────────────────────────────

@Description("Arguments for ScopeFileSearchQuickArgs")
@Schema
@Serializable
internal data class ScopeFileSearchQuickArgs(
    val query: String = "",
    @Description("Optional explicit ordered keywords.")
    val keywords: List<String> = emptyList(),
    @Description("Preset scope identifier.")
    val scopePreset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
    @Description("Search mode: NAME, PATH, NAME_OR_PATH, or GLOB.")
    val matchMode: ScopeFileSearchMode = ScopeFileSearchMode.NAME_OR_PATH,
    @Description("Whether matching is case-sensitive.")
    val caseSensitive: Boolean = false,
    @Description("VFS directory URL to search within.")
    val directoryUrl: String? = null,
    @Description("Maximum number of matched files to return.")
    val maxResults: Int = 500,
    @Description("Timeout in milliseconds.")
    val timeoutMillis: Int = 30000,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeFileSearchQuickTool(): SdkToolDescriptor<ScopeFileSearchQuickArgs> {
    return sdkToolDescriptor<ScopeFileSearchQuickArgs>(
        name = "scope_search_files_quick",
        description = "First-call friendly file search shortcut with preset scope and low-parameter defaults." +
                AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX,
        handler = { args -> scopeFileSearchQuickHandler(this, args) },
    )
}

private suspend fun scopeFileSearchQuickHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeFileSearchQuickArgs,
): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        reportActivity(
            Bundle.message(
                "tool.activity.scope.search.files.quick",
                args.scopePreset.name,
                args.matchMode.name,
                args.maxResults,
                args.timeoutMillis,
            ),
        )
        val descriptor = buildPresetScopeDescriptor(
            project = project,
            preset = args.scopePreset,
            allowUiInteractiveScopes = false,
        )
        val innerArgs = ScopeFileSearchArgs(
            query = args.query,
            keywords = args.keywords,
            scope = descriptor,
            matchMode = args.matchMode,
            caseSensitive = args.caseSensitive,
            directoryUrl = args.directoryUrl,
            maxResults = args.maxResults,
            timeoutMillis = args.timeoutMillis,
            allowUiInteractiveScopes = false,
        )
        return@callToolWithProject scopeFileSearchHandler(ctx, innerArgs)
    }
}

// ── scope_find_files_by_name_keyword ──────────────────────────────

@Description("Arguments for ScopeFindFilesByNameArgs")
@Schema
@Serializable
internal data class ScopeFindFilesByNameArgs(
    @Description("Filename keyword text.")
    val nameKeyword: String = "",
    @Description("Optional explicit ordered keywords.")
    val keywords: List<String> = emptyList(),
    @Description("Scope program descriptor.")
    val `scope`: ScopeProgramDescriptorDto,
    @Description("Whether matching is case-sensitive.")
    val caseSensitive: Boolean = false,
    @Description("Maximum number of matched files to return.")
    val maxResults: Int = 1000,
    @Description("Timeout in milliseconds.")
    val timeoutMillis: Int = 30000,
    @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeFindFilesByNameTool(): SdkToolDescriptor<ScopeFindFilesByNameArgs> {
    return sdkToolDescriptor<ScopeFindFilesByNameArgs>(
        name = "scope_find_files_by_name_keyword",
        description = "Shortcut: search files by filename keyword within a scope. " +
                "For GLOBAL scopes without directoryUrl, this uses indexed name lookup where possible.",
        handler = { args -> scopeFindFilesByNameHandler(this, args) },
    )
}

private suspend fun scopeFindFilesByNameHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeFindFilesByNameArgs,
): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        reportActivity(
            Bundle.message(
                "tool.activity.scope.search.files.by.name",
                previewKeywordCount(args.nameKeyword, args.keywords),
            ),
        )
        val innerArgs = ScopeFileSearchArgs(
            query = args.nameKeyword,
            keywords = args.keywords,
            scope = args.scope,
            matchMode = ScopeFileSearchMode.NAME,
            caseSensitive = args.caseSensitive,
            directoryUrl = null,
            maxResults = args.maxResults,
            timeoutMillis = args.timeoutMillis,
            allowUiInteractiveScopes = args.allowUiInteractiveScopes,
        )
        return@callToolWithProject scopeFileSearchHandler(ctx, innerArgs)
    }
}

// ── scope_find_files_by_path_keyword ──────────────────────────────

@Description("Arguments for ScopeFindFilesByPathArgs")
@Schema
@Serializable
internal data class ScopeFindFilesByPathArgs(
    @Description("Path keyword text.")
    val pathKeyword: String = "",
    @Description("Optional explicit ordered keywords.")
    val keywords: List<String> = emptyList(),
    @Description("Scope program descriptor.")
    val `scope`: ScopeProgramDescriptorDto,
    @Description("Whether matching is case-sensitive.")
    val caseSensitive: Boolean = false,
    @Description("Maximum number of matched files to return.")
    val maxResults: Int = 1000,
    @Description("Timeout in milliseconds.")
    val timeoutMillis: Int = 30000,
    @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeFindFilesByPathTool(): SdkToolDescriptor<ScopeFindFilesByPathArgs> {
    return sdkToolDescriptor<ScopeFindFilesByPathArgs>(
        name = "scope_find_files_by_path_keyword",
        description = "Shortcut: search files by path keyword within a scope. " +
                "When directoryUrl points to jar:// roots, path keywords match archive-internal paths.",
        handler = { args -> scopeFindFilesByPathHandler(this, args) },
    )
}

private suspend fun scopeFindFilesByPathHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeFindFilesByPathArgs,
): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        reportActivity(
            Bundle.message(
                "tool.activity.scope.search.files.by.path",
                previewKeywordCount(args.pathKeyword, args.keywords),
            ),
        )
        val innerArgs = ScopeFileSearchArgs(
            query = args.pathKeyword,
            keywords = args.keywords,
            scope = args.scope,
            matchMode = ScopeFileSearchMode.PATH,
            caseSensitive = args.caseSensitive,
            directoryUrl = null,
            maxResults = args.maxResults,
            timeoutMillis = args.timeoutMillis,
            allowUiInteractiveScopes = args.allowUiInteractiveScopes,
        )
        return@callToolWithProject scopeFileSearchHandler(ctx, innerArgs)
    }
}

// ── find_in_directory_using_glob ─────────────────────────────────

@Description("Arguments for ScopeFindInDirectoryGlobArgs")
@Schema
@Serializable
internal data class ScopeFindInDirectoryGlobArgs(
    @Description("VFS directory URL to search within.")
    val directoryUrl: String,
    @Description("Glob pattern to match against file path.")
    val globPattern: String,
    @Description("Scope program descriptor.")
    val `scope`: ScopeProgramDescriptorDto,
    @Description("Maximum number of matched files to return.")
    val maxResults: Int = 1000,
    @Description("Timeout in milliseconds.")
    val timeoutMillis: Int = 30000,
    @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeFindInDirectoryGlobTool(): SdkToolDescriptor<ScopeFindInDirectoryGlobArgs> {
    return sdkToolDescriptor<ScopeFindInDirectoryGlobArgs>(
        name = "find_in_directory_using_glob",
        description = "Shortcut: find files in a directory by glob pattern and scope. " +
                "Works with arbitrary VFS directories, including jar:// URLs in Gradle caches. " +
                "Example: directoryUrl='jar:///Users/<you>/.gradle/caches/.../idea-253.x-sources.jar!/', " +
                "globPattern='**/FindSymbolParameters.java'.",
        handler = { args -> scopeFindInDirectoryGlobHandler(this, args) },
    )
}

private suspend fun scopeFindInDirectoryGlobHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeFindInDirectoryGlobArgs,
): CallToolResult {
    if (args.directoryUrl.isBlank()) {
        return CallToolResult(
            content = listOf(TextContent(text = "directoryUrl must not be blank.")),
            isError = true,
        )
    }
    if (args.globPattern.isBlank()) {
        return CallToolResult(
            content = listOf(TextContent(text = "globPattern must not be blank.")),
            isError = true,
        )
    }
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        reportActivity(Bundle.message("tool.activity.scope.search.files.by.glob", args.globPattern.length))
        val innerArgs = ScopeFileSearchArgs(
            query = args.globPattern,
            keywords = emptyList(),
            scope = args.scope,
            matchMode = ScopeFileSearchMode.GLOB,
            caseSensitive = true,
            directoryUrl = args.directoryUrl,
            maxResults = args.maxResults,
            timeoutMillis = args.timeoutMillis,
            allowUiInteractiveScopes = args.allowUiInteractiveScopes,
        )
        return@callToolWithProject scopeFileSearchHandler(ctx, innerArgs)
    }
}

// ── scope_find_source_file_by_class_name ─────────────────────────

@Description("Arguments for ScopeFindSourceFileByClassNameArgs")
@Schema
@Serializable
internal data class ScopeFindSourceFileByClassNameArgs(
    @Description("Class name, simple or qualified.")
    val className: String,
    @Description("Scope program descriptor.")
    val `scope`: ScopeProgramDescriptorDto? = null,
    @Description("Whether to prioritize source-like paths over binary/artifact paths.")
    val preferSources: Boolean = true,
    @Description("Whether matching is case-sensitive.")
    val caseSensitive: Boolean = false,
    @Description("Maximum number of matched files to return.")
    val maxResults: Int = 100,
    @Description("Timeout in milliseconds.")
    val timeoutMillis: Int = 30000,
    @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
    val allowUiInteractiveScopes: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal fun scopeFindSourceFileByClassNameTool(): SdkToolDescriptor<ScopeFindSourceFileByClassNameArgs> {
    return sdkToolDescriptor<ScopeFindSourceFileByClassNameArgs>(
        name = "scope_find_source_file_by_class_name",
        description = "Find likely source files by class name across project and libraries, with source-preferred ranking." +
                MCP_FIRST_LIBRARY_QUERY_POLICY_DESCRIPTION_SUFFIX,
        handler = { args -> scopeFindSourceFileByClassNameHandler(this, args) },
    )
}

private suspend fun scopeFindSourceFileByClassNameHandler(
    ctx: SdkToolHandlerContext,
    args: ScopeFindSourceFileByClassNameArgs,
): CallToolResult {
    if (args.className.isBlank()) {
        return CallToolResult(
            content = listOf(TextContent(text = "className must not be blank.")),
            isError = true,
        )
    }
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
    ) { project ->
        reportActivity(
            Bundle.message(
                "tool.activity.scope.search.files.by.class.name",
                args.className,
                args.preferSources,
                args.maxResults,
            ),
        )
        val effectiveScope = args.scope ?: buildStandardScopeDescriptor(
            project = project,
            standardScopeId = "All Places",
            allowUiInteractiveScopes = args.allowUiInteractiveScopes,
        )
        val simpleName = args.className.substringAfterLast('.').substringAfterLast('$').trim()
        if (simpleName.isEmpty()) {
            return@callToolWithProject CallToolResult(
                content = listOf(TextContent(text = "className '${args.className}' does not contain a usable simple name.")),
                isError = true,
            )
        }
        val innerArgs = ScopeFileSearchArgs(
            query = simpleName,
            keywords = listOf(simpleName),
            scope = effectiveScope,
            matchMode = ScopeFileSearchMode.NAME,
            caseSensitive = args.caseSensitive,
            directoryUrl = null,
            maxResults = args.maxResults,
            timeoutMillis = args.timeoutMillis,
            allowUiInteractiveScopes = args.allowUiInteractiveScopes,
        )
        val searchResult = scopeFileSearchHandler(ctx, innerArgs)

        if (searchResult.isError == true) return@callToolWithProject searchResult

        val searchDto = toolArgsJson.decodeFromString<ScopeFileSearchResultDto>(
            (searchResult.content.firstOrNull() as? TextContent)?.text ?: return@callToolWithProject searchResult,
        )
        val ranked = if (args.preferSources) {
            searchDto.matchedFileUrls.sortedWith(
                compareBy(
                    { sourceRank(it) },
                    { it.lowercase() },
                ),
            )
        } else {
            searchDto.matchedFileUrls
        }
        val result = searchDto.copy(
            query = args.className,
            matchedFileUrls = ranked,
            diagnostics = (searchDto.diagnostics + buildList {
                add("Heuristic class-name lookup used simpleName='$simpleName'.")
                if (args.preferSources) add("Results were ranked to prefer source-like paths.")
            }).distinct(),
        )
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ── Helper functions (migrated from old McpToolset) ──────────────

private suspend fun resolveDirectory(directoryUrl: String): VirtualFile {
    val file = findFileByUrlWithRefresh(directoryUrl)
        ?: throw IllegalArgumentException("Directory URL '$directoryUrl' not found.")
    if (!file.isDirectory) throw IllegalArgumentException("URL '$directoryUrl' is not a directory.")
    return file
}

private fun buildGlobMatchers(
    mode: ScopeFileSearchMode,
    query: String,
    caseSensitive: Boolean,
): Pair<PathMatcher?, PathMatcher?> {
    if (mode != ScopeFileSearchMode.GLOB) return null to null
    val matcher = runCatching { FileSystems.getDefault().getPathMatcher("glob:$query") }
        .getOrElse { error -> throw IllegalArgumentException("Invalid glob pattern '$query': ${error.message}") }
    val lowerCaseMatcher = if (caseSensitive) {
        null
    } else {
        runCatching { FileSystems.getDefault().getPathMatcher("glob:${query.lowercase()}") }.getOrNull()
    }
    return matcher to lowerCaseMatcher
}

private suspend fun scanByTraversal(
    project: com.intellij.openapi.project.Project,
    resolvedScope: com.intellij.psi.search.SearchScope,
    rootDirectory: VirtualFile?,
    mode: ScopeFileSearchMode,
    normalizedKeywords: List<String>,
    caseSensitive: Boolean,
    projectRootPath: Path?,
    globMatcher: PathMatcher?,
    lowerCaseGlobMatcher: PathMatcher?,
    maxResults: Int,
    scannedCounter: AtomicInteger,
    matchedCounter: AtomicInteger,
    matched: MutableList<String>,
): Boolean {
    var hasMore = false
    readAction {
        if (rootDirectory != null) {
            hasMore = scanByVfsDirectoryTraversal(
                rootDirectory = rootDirectory,
                resolvedScope = resolvedScope,
                mode = mode,
                normalizedKeywords = normalizedKeywords,
                caseSensitive = caseSensitive,
                globMatcher = globMatcher,
                lowerCaseGlobMatcher = lowerCaseGlobMatcher,
                maxResults = maxResults,
                scannedCounter = scannedCounter,
                matchedCounter = matchedCounter,
                matched = matched,
            )
        } else {
            val fileIndex = ProjectRootManager.getInstance(project).fileIndex
            val iterator = ContentIterator { file ->
                ProgressManager.checkCanceled()
                if (file.isDirectory) return@ContentIterator true

                scannedCounter.incrementAndGet()
                if (!resolvedScope.contains(file)) return@ContentIterator true

                val relativePath = projectRelativePath(projectRootPath, file)
                val absolutePath = file.path
                val matchedCurrent = matchesQuery(
                    mode = mode,
                    keywords = normalizedKeywords,
                    caseSensitive = caseSensitive,
                    file = file,
                    relativePath = relativePath,
                    absolutePath = absolutePath,
                    globMatcher = globMatcher,
                    lowerCaseGlobMatcher = lowerCaseGlobMatcher,
                )
                if (!matchedCurrent) return@ContentIterator true

                matched += file.url
                matchedCounter.incrementAndGet()
                if (matched.size >= maxResults) {
                    hasMore = true
                    return@ContentIterator false
                }
                true
            }
            fileIndex.iterateContent(iterator)
        }
    }
    return hasMore
}

private fun scanByVfsDirectoryTraversal(
    rootDirectory: VirtualFile,
    resolvedScope: com.intellij.psi.search.SearchScope,
    mode: ScopeFileSearchMode,
    normalizedKeywords: List<String>,
    caseSensitive: Boolean,
    globMatcher: PathMatcher?,
    lowerCaseGlobMatcher: PathMatcher?,
    maxResults: Int,
    scannedCounter: AtomicInteger,
    matchedCounter: AtomicInteger,
    matched: MutableList<String>,
): Boolean {
    val stack = ArrayDeque<VirtualFile>()
    stack.add(rootDirectory)

    while (stack.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val file = stack.removeLast()
        if (file.isDirectory) {
            val children = file.children
            for (index in children.indices.reversed()) {
                stack.add(children[index])
            }
            continue
        }

        scannedCounter.incrementAndGet()
        if (!resolvedScope.contains(file)) {
            continue
        }

        val relativePath = relativePathFromAncestor(rootDirectory, file)
        val absolutePath = file.path
        val matchedCurrent = matchesQuery(
            mode = mode,
            keywords = normalizedKeywords,
            caseSensitive = caseSensitive,
            file = file,
            relativePath = relativePath,
            absolutePath = absolutePath,
            globMatcher = globMatcher,
            lowerCaseGlobMatcher = lowerCaseGlobMatcher,
        )
        if (!matchedCurrent) {
            continue
        }

        matched += file.url
        matchedCounter.incrementAndGet()
        if (matched.size >= maxResults) {
            return true
        }
    }
    return false
}

private suspend fun scanByFilenameIndex(
    searchScope: GlobalSearchScope,
    rootDirectory: VirtualFile?,
    normalizedKeywords: List<String>,
    caseSensitive: Boolean,
    maxResults: Int,
    scannedCounter: AtomicInteger,
    matchedCounter: AtomicInteger,
    matched: MutableList<String>,
): Boolean {
    var hasMore = false
    readAction {
        FilenameIndex.processAllFileNames(
            { fileName ->
                ProgressManager.checkCanceled()
                if (!containsOrderedKeywords(fileName, normalizedKeywords, caseSensitive)) {
                    return@processAllFileNames true
                }
                val files = FilenameIndex.getVirtualFilesByName(fileName, searchScope)
                for (file in files) {
                    ProgressManager.checkCanceled()
                    if (file.isDirectory) continue
                    if (!searchScope.contains(file)) continue
                    if (rootDirectory != null && !isInsideDirectory(file, rootDirectory)) continue

                    scannedCounter.incrementAndGet()
                    matched += file.url
                    matchedCounter.incrementAndGet()
                    if (matched.size >= maxResults) {
                        hasMore = true
                        return@processAllFileNames false
                    }
                }
                true
            },
            searchScope, null,
        )
    }
    return hasMore
}

private fun matchesQuery(
    mode: ScopeFileSearchMode,
    keywords: List<String>,
    caseSensitive: Boolean,
    file: VirtualFile,
    relativePath: String?,
    absolutePath: String,
    globMatcher: PathMatcher?,
    lowerCaseGlobMatcher: PathMatcher?,
): Boolean {
    return when (mode) {
        ScopeFileSearchMode.NAME -> containsOrderedKeywords(file.name, keywords, caseSensitive)
        ScopeFileSearchMode.PATH -> {
            val pathText = relativePath ?: absolutePath
            containsOrderedKeywords(pathText, keywords, caseSensitive)
        }

        ScopeFileSearchMode.NAME_OR_PATH -> {
            containsOrderedKeywords(file.name, keywords, caseSensitive) ||
                    containsOrderedKeywords(relativePath ?: absolutePath, keywords, caseSensitive)
        }

        ScopeFileSearchMode.GLOB -> matchesGlob(
            globMatcher = globMatcher,
            lowerCaseGlobMatcher = lowerCaseGlobMatcher,
            relativePath = relativePath,
            absolutePath = absolutePath,
        )
    }
}

private fun matchesGlob(
    globMatcher: PathMatcher?,
    lowerCaseGlobMatcher: PathMatcher?,
    relativePath: String?,
    absolutePath: String,
): Boolean {
    if (globMatcher == null) return false
    val candidates = buildList {
        if (relativePath != null) add(relativePath)
        add(absolutePath)
    }
    return candidates.any { candidate ->
        runCatching { globMatcher.matches(Path.of(candidate)) }.getOrDefault(false) ||
                (lowerCaseGlobMatcher != null && runCatching {
                    lowerCaseGlobMatcher.matches(Path.of(candidate.lowercase()))
                }.getOrDefault(false))
    }
}

private fun containsOrderedKeywords(
    value: String,
    keywords: List<String>,
    caseSensitive: Boolean,
): Boolean {
    if (keywords.isEmpty()) return false
    val haystack = if (caseSensitive) value else value.lowercase()
    var fromIndex = 0
    for (keyword in keywords) {
        val keywordIndex = haystack.indexOf(keyword, fromIndex)
        if (keywordIndex < 0) return false
        fromIndex = keywordIndex + keyword.length
    }
    return true
}

private fun projectRelativePath(projectRootPath: Path?, file: VirtualFile): String? {
    val rootPath = projectRootPath ?: return null
    val filePath = runCatching { file.toNioPath().toString() }.getOrNull() ?: return null
    return runCatching {
        rootPath.relativize(Path.of(filePath)).toString().replace('\\', '/')
    }.getOrNull()
}

private fun relativePathFromAncestor(ancestor: VirtualFile, file: VirtualFile): String? {
    if (ancestor == file) return ""
    val names = mutableListOf<String>()
    var current: VirtualFile? = file
    while (current != null && current != ancestor) {
        names += current.name
        current = current.parent
    }
    if (current != ancestor) return null
    return names.asReversed().joinToString("/")
}

private fun isInsideDirectory(file: VirtualFile, directory: VirtualFile): Boolean {
    var current: VirtualFile? = file.parent
    while (current != null) {
        if (current == directory) return true
        current = current.parent
    }
    return false
}

private fun normalizeSearchInput(
    mode: ScopeFileSearchMode,
    query: String,
    keywords: List<String>,
): NormalizedSearchInput {
    val explicitKeywords = keywords.map { it.trim() }.filter { it.isNotEmpty() }
    return when (mode) {
        ScopeFileSearchMode.GLOB -> {
            if (query.isBlank()) throw IllegalArgumentException("query must not be blank for GLOB mode.")
            if (explicitKeywords.isNotEmpty()) throw IllegalArgumentException("keywords are not supported for GLOB mode.")
            NormalizedSearchInput(queryForDisplay = query, textKeywords = emptyList())
        }

        ScopeFileSearchMode.NAME,
        ScopeFileSearchMode.PATH,
        ScopeFileSearchMode.NAME_OR_PATH,
            -> {
            val orderedKeywords = explicitKeywords.ifEmpty { splitQueryKeywords(query) }
            if (orderedKeywords.isEmpty()) {
                throw IllegalArgumentException("Provide non-blank query or keywords for text match modes.")
            }
            val queryForDisplay = query.ifBlank { orderedKeywords.joinToString(" ") }
            NormalizedSearchInput(queryForDisplay = queryForDisplay, textKeywords = orderedKeywords)
        }
    }
}

private fun previewKeywordCount(query: String, keywords: List<String>): Int {
    val explicitKeywords = keywords.count { it.isNotBlank() }
    if (explicitKeywords > 0) return explicitKeywords
    return splitQueryKeywords(query).size
}

private fun splitQueryKeywords(query: String): List<String> {
    return query.trim()
        .split(WHITESPACE_REGEX)
        .filter { it.isNotBlank() }
}

private data class NormalizedSearchInput(
    val queryForDisplay: String,
    val textKeywords: List<String>,
)

private fun sourceRank(url: String): Int {
    val lower = url.lowercase()
    return when {
        lower.contains("/src/") -> 0
        lower.contains("/sources/") -> 1
        lower.contains("-sources.jar!/") -> 2
        lower.endsWith(".kt") || lower.endsWith(".java") || lower.endsWith(".groovy") || lower.endsWith(".scala") -> 3
        lower.endsWith(".class") -> 8
        lower.contains("/build/") || lower.contains("/out/") -> 9
        else -> 5
    }
}
