/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.search.tools

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.common.findFileByUrlWithRefresh
import dev.ghostflyby.mcp.common.reportActivity
import dev.ghostflyby.mcp.scope.*
import dev.ghostflyby.mcp.sdk.project
import dev.ghostflyby.mcp.server.route.McpCallContext
import dev.ghostflyby.mcp.server.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.*
import kotlinx.schema.Description
import kotlinx.schema.Schema
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

private val WHITESPACE_REGEX = Regex("\\s+")

internal class ScopeFileSearchTools {
    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeSearchFiles(
        @Description("Query string")
        query: String = "",
        @Description("Optional explicit ordered keywords.")
        keywords: List<String> = emptyList(),
        @Description("Scope program descriptor.")
        scope: ScopeProgramDescriptorDto,
        @Description("Search mode: NAME, PATH, NAME_OR_PATH, or GLOB.")
        matchMode: ScopeFileSearchMode = ScopeFileSearchMode.NAME_OR_PATH,
        @Description("Whether matching is case-sensitive.")
        caseSensitive: Boolean = false,
        @Description("VFS directory URL to search within.")
        directoryUrl: String? = null,
        @Description("Maximum number of matched files to return.")
        maxResults: Int = 1000,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        if (maxResults < 1) return CallToolResult(
            content = listOf(TextContent(text = "maxResults must be >= 1.")),
            isError = true,
        )
        if (timeoutMillis < 1) return CallToolResult(
            content = listOf(TextContent(text = "timeoutMillis must be >= 1.")),
            isError = true,
        )
        return runFileSearch(
            project,
            query,
            keywords,
            scope,
            matchMode,
            caseSensitive,
            directoryUrl,
            maxResults,
            timeoutMillis,
            allowUiInteractiveScopes,
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeSearchFilesQuick(
        @Description("Query string")
        query: String = "",
        @Description("Optional explicit ordered keywords.")
        keywords: List<String> = emptyList(),
        @Description("Preset scope identifier.")
        scopePreset: ScopeQuickPreset = ScopeQuickPreset.PROJECT_FILES,
        @Description("Search mode: NAME, PATH, NAME_OR_PATH, or GLOB.")
        matchMode: ScopeFileSearchMode = ScopeFileSearchMode.NAME_OR_PATH,
        @Description("Whether matching is case-sensitive.")
        caseSensitive: Boolean = false,
        @Description("VFS directory URL to search within.")
        directoryUrl: String? = null,
        @Description("Maximum number of matched files to return.")
        maxResults: Int = 500,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
    ): CallToolResult {
        val project = call.project()
        reportActivity(
            Bundle.message(
                "tool.activity.scope.search.files.quick",
                scopePreset.name,
                matchMode.name,
                maxResults,
                timeoutMillis,
            ),
        )
        val descriptor =
            buildPresetScopeDescriptor(project = project, preset = scopePreset, allowUiInteractiveScopes = false)
        return runFileSearch(
            project,
            query,
            keywords,
            descriptor,
            matchMode,
            caseSensitive,
            directoryUrl,
            maxResults,
            timeoutMillis,
            allowUiInteractiveScopes = false,
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeFindFilesByNameKeyword(
        @Description("Filename keyword text.")
        nameKeyword: String = "",
        @Description("Optional explicit ordered keywords.")
        keywords: List<String> = emptyList(),
        @Description("Scope program descriptor.")
        scope: ScopeProgramDescriptorDto,
        @Description("Whether matching is case-sensitive.")
        caseSensitive: Boolean = false,
        @Description("Maximum number of matched files to return.")
        maxResults: Int = 1000,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        reportActivity(
            Bundle.message(
                "tool.activity.scope.search.files.by.name",
                previewKeywordCount(nameKeyword, keywords),
            ),
        )
        return runFileSearch(
            project,
            nameKeyword,
            keywords,
            scope,
            ScopeFileSearchMode.NAME,
            caseSensitive,
            null,
            maxResults,
            timeoutMillis,
            allowUiInteractiveScopes,
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeFindFilesByPathKeyword(
        @Description("Path keyword text.")
        pathKeyword: String = "",
        @Description("Optional explicit ordered keywords.")
        keywords: List<String> = emptyList(),
        @Description("Scope program descriptor.")
        scope: ScopeProgramDescriptorDto,
        @Description("Whether matching is case-sensitive.")
        caseSensitive: Boolean = false,
        @Description("Maximum number of matched files to return.")
        maxResults: Int = 1000,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        reportActivity(
            Bundle.message(
                "tool.activity.scope.search.files.by.path",
                previewKeywordCount(pathKeyword, keywords),
            ),
        )
        return runFileSearch(
            project,
            pathKeyword,
            keywords,
            scope,
            ScopeFileSearchMode.PATH,
            caseSensitive,
            null,
            maxResults,
            timeoutMillis,
            allowUiInteractiveScopes,
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.findInDirectoryUsingGlob(
        @Description("VFS directory URL to search within.")
        directoryUrl: String,
        @Description("Glob pattern to match against file path.")
        globPattern: String,
        @Description("Scope program descriptor.")
        scope: ScopeProgramDescriptorDto,
        @Description("Maximum number of matched files to return.")
        maxResults: Int = 1000,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        if (directoryUrl.isBlank()) return CallToolResult(
            content = listOf(TextContent(text = "directoryUrl must not be blank.")),
            isError = true,
        )
        if (globPattern.isBlank()) return CallToolResult(
            content = listOf(TextContent(text = "globPattern must not be blank.")),
            isError = true,
        )
        val project = call.project()
        reportActivity(Bundle.message("tool.activity.scope.search.files.by.glob", globPattern.length))
        return runFileSearch(
            project,
            globPattern,
            emptyList(),
            scope,
            ScopeFileSearchMode.GLOB,
            caseSensitive = true,
            directoryUrl,
            maxResults,
            timeoutMillis,
            allowUiInteractiveScopes,
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scopeFindSourceFileByClassName(
        @Description("Class name, simple or qualified.")
        className: String,
        @Description("Scope program descriptor. Defaults to All Places if omitted.")
        scope: ScopeProgramDescriptorDto? = null,
        @Description("Whether to prioritize source-like paths over binary/artifact paths.")
        preferSources: Boolean = true,
        @Description("Whether matching is case-sensitive.")
        caseSensitive: Boolean = false,
        @Description("Maximum number of matched files to return.")
        maxResults: Int = 100,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @Description("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        if (className.isBlank()) return CallToolResult(
            content = listOf(TextContent(text = "className must not be blank.")),
            isError = true,
        )
        val project = call.project()
        reportActivity(
            Bundle.message(
                "tool.activity.scope.search.files.by.class.name",
                className,
                preferSources,
                maxResults,
            ),
        )

        val effectiveScope = scope ?: buildStandardScopeDescriptor(
            project = project,
            standardScopeId = "All Places",
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        val simpleName = className.substringAfterLast('.').substringAfterLast('$').trim()
        if (simpleName.isEmpty()) return CallToolResult(
            content = listOf(TextContent(text = "className '$className' does not contain a usable simple name.")),
            isError = true,
        )

        val searchResult = runFileSearch(
            project,
            simpleName,
            listOf(simpleName),
            effectiveScope,
            ScopeFileSearchMode.NAME,
            caseSensitive,
            null,
            maxResults,
            timeoutMillis,
            allowUiInteractiveScopes,
        )
        if (searchResult.isError == true) return searchResult

        val searchDto = toolArgsJson.decodeFromString<ScopeFileSearchResultDto>(
            (searchResult.content.firstOrNull() as? TextContent)?.text ?: return searchResult,
        )
        val ranked = if (preferSources) {
            searchDto.matchedFileUrls.sortedWith(compareBy({ sourceRank(it) }, { it.lowercase() }))
        } else {
            searchDto.matchedFileUrls
        }
        val result = searchDto.copy(
            query = className,
            matchedFileUrls = ranked,
            diagnostics = (searchDto.diagnostics + buildList {
                add("Heuristic class-name lookup used simpleName='$simpleName'.")
                if (preferSources) add("Results were ranked to prefer source-like paths.")
            }).distinct(),
        )
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    // ── Shared search logic ───────────────────────────────────────

    private suspend fun runFileSearch(
        project: Project,
        query: String,
        keywords: List<String>,
        scopeDescriptor: ScopeProgramDescriptorDto,
        matchMode: ScopeFileSearchMode,
        caseSensitive: Boolean,
        directoryUrl: String?,
        maxResults: Int,
        timeoutMillis: Int,
        allowUiInteractiveScopes: Boolean,
    ): CallToolResult {
        if (maxResults < 1) return CallToolResult(
            content = listOf(TextContent(text = "maxResults must be >= 1.")),
            isError = true,
        )
        if (timeoutMillis < 1) return CallToolResult(
            content = listOf(TextContent(text = "timeoutMillis must be >= 1.")),
            isError = true,
        )
        val normalizedSearchInput = normalizeSearchInput(matchMode, query, keywords)

        reportActivity(
            Bundle.message(
                "tool.activity.scope.search.files.start",
                matchMode.name,
                normalizedSearchInput.textKeywords.size,
                maxResults,
                timeoutMillis,
            ),
        )

        val resolved = ScopeResolverService.getInstance(project).resolveDescriptor(
            project = project,
            descriptor = scopeDescriptor,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        val projectRootPath = project.basePath?.let { Path.of(it) }
        val rootDirectory = directoryUrl?.let { resolveDirectory(it) }
        val (globMatcher, lowerCaseGlobMatcher) = buildGlobMatchers(
            matchMode,
            normalizedSearchInput.queryForDisplay,
            caseSensitive,
        )
        val normalizedKeywords =
            if (caseSensitive) normalizedSearchInput.textKeywords else normalizedSearchInput.textKeywords.map { it.lowercase() }

        val scannedCounter = AtomicInteger(0)
        val matchedCounter = AtomicInteger(0)
        val finished = AtomicBoolean(false)
        val matched = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        var timedOut = false
        var probablyHasMoreMatchingFiles = false
        val indexSearchScope = resolved.scope as? GlobalSearchScope
        val useFilenameIndexForName = rootDirectory == null &&
                matchMode == ScopeFileSearchMode.NAME &&
                resolved.scopeShape == ScopeShape.GLOBAL &&
                indexSearchScope != null

        coroutineScope {
            val progressJob = launch {
                while (isActive && !finished.get()) {
                    delay(800.milliseconds)
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
                timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
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
                                caseSensitive = caseSensitive,
                                maxResults = maxResults,
                                scannedCounter = scannedCounter,
                                matchedCounter = matchedCounter,
                                matched = matched,
                            )
                        } else {
                            if (matchMode == ScopeFileSearchMode.NAME && resolved.scopeShape != ScopeShape.GLOBAL) {
                                diagnostics += "NAME mode uses traversal fallback because resolved scope shape is ${resolved.scopeShape.name}."
                            }
                            probablyHasMoreMatchingFiles = scanByTraversal(
                                project = project,
                                resolvedScope = resolved.scope,
                                rootDirectory = rootDirectory,
                                mode = matchMode,
                                normalizedKeywords = normalizedKeywords,
                                caseSensitive = caseSensitive,
                                projectRootPath = projectRootPath,
                                globMatcher = globMatcher,
                                lowerCaseGlobMatcher = lowerCaseGlobMatcher,
                                maxResults = maxResults,
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
            mode = matchMode,
            query = query,
            keywords = normalizedSearchInput.textKeywords,
            directoryUrl = directoryUrl,
            matchedFileUrls = matched,
            scannedFileCount = scannedCounter.get(),
            probablyHasMoreMatchingFiles = probablyHasMoreMatchingFiles,
            timedOut = timedOut,
            canceled = false,
            diagnostics = (scopeDescriptor.diagnostics + resolved.diagnostics + diagnostics).distinct(),
        )
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
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
    project: Project,
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
