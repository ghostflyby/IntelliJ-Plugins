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

package dev.ghostflyby.mcp.scope.search

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.VFS_URL_PARAM_DESCRIPTION
import dev.ghostflyby.mcp.reportActivity
import dev.ghostflyby.mcp.scope.*
import kotlinx.coroutines.*
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

private val WHITESPACE_REGEX = Regex("\\s+")

@Suppress("FunctionName")
internal class ScopeFileSearchMcpTools : McpToolset {

    @McpTool
    @McpDescription(
        "Search files by name/path text or glob within a scope descriptor. " +
            "Returns matching file URLs and search diagnostics.",
    )
    suspend fun scope_search_files(
        @McpDescription("Search text or glob pattern depending on matchMode. For text modes, whitespace splits ordered keywords.")
        query: String = "",
        @McpDescription("Optional explicit ordered keywords for text modes; all keywords must match in order.")
        keywords: List<String> = emptyList(),
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Search mode: NAME, PATH, NAME_OR_PATH, or GLOB.")
        matchMode: ScopeFileSearchMode = ScopeFileSearchMode.NAME_OR_PATH,
        @McpDescription("Whether matching is case-sensitive.")
        caseSensitive: Boolean = false,
        @McpDescription("Optional VFS directory URL to limit scan range.")
        directoryUrl: String? = null,
        @McpDescription("Maximum number of matched files to return.")
        maxResults: Int = 1000,
        @McpDescription("Timeout in milliseconds for this search.")
        timeoutMillis: Int = 30000,
        @McpDescription("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): ScopeFileSearchResultDto {
        if (maxResults < 1) mcpFail("maxResults must be >= 1.")
        if (timeoutMillis < 1) mcpFail("timeoutMillis must be >= 1.")
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

        val project = currentCoroutineContext().project
        val resolved = ScopeResolverService.getInstance(project).resolveDescriptor(
            project = project,
            descriptor = scope,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
        val projectRootPath = project.basePath?.let { Path.of(it) }
        val rootDirectory = directoryUrl?.let { resolveDirectory(it) }
        val (globMatcher, lowerCaseGlobMatcher) = buildGlobMatchers(
            matchMode,
            normalizedSearchInput.queryForDisplay,
            caseSensitive,
        )
        val normalizedKeywords = if (caseSensitive) {
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
        val useFilenameIndexForName = matchMode == ScopeFileSearchMode.NAME &&
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

        return ScopeFileSearchResultDto(
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
            diagnostics = (scope.diagnostics + resolved.diagnostics + diagnostics).distinct(),
        )
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
            if (rootDirectory != null) {
                fileIndex.iterateContentUnderDirectory(rootDirectory, iterator)
            } else {
                fileIndex.iterateContent(iterator)
            }
        }
        return hasMore
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
            FilenameIndex.processAllFileNames({ fileName ->
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
            }, searchScope, null)
        }
        return hasMore
    }

    @McpTool
    @McpDescription("Shortcut: search files by filename keyword within a scope.")
    suspend fun scope_find_files_by_name_keyword(
        @McpDescription("Filename keyword text. Whitespace splits ordered keywords when keywords is empty.")
        nameKeyword: String = "",
        @McpDescription("Optional explicit ordered keywords; all keywords must match in order.")
        keywords: List<String> = emptyList(),
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Whether matching is case-sensitive.")
        caseSensitive: Boolean = false,
        @McpDescription("Maximum number of matched files to return.")
        maxResults: Int = 1000,
        @McpDescription("Timeout in milliseconds for this search.")
        timeoutMillis: Int = 30000,
        @McpDescription("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): ScopeFileSearchResultDto {
        reportActivity(Bundle.message("tool.activity.scope.search.files.by.name", previewKeywordCount(nameKeyword, keywords)))
        return scope_search_files(
            query = nameKeyword,
            keywords = keywords,
            scope = scope,
            matchMode = ScopeFileSearchMode.NAME,
            caseSensitive = caseSensitive,
            directoryUrl = null,
            maxResults = maxResults,
            timeoutMillis = timeoutMillis,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
    }

    @McpTool
    @McpDescription("Shortcut: search files by path keyword within a scope.")
    suspend fun scope_find_files_by_path_keyword(
        @McpDescription("Path keyword text. Whitespace splits ordered keywords when keywords is empty.")
        pathKeyword: String = "",
        @McpDescription("Optional explicit ordered keywords; all keywords must match in order.")
        keywords: List<String> = emptyList(),
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Whether matching is case-sensitive.")
        caseSensitive: Boolean = false,
        @McpDescription("Maximum number of matched files to return.")
        maxResults: Int = 1000,
        @McpDescription("Timeout in milliseconds for this search.")
        timeoutMillis: Int = 30000,
        @McpDescription("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): ScopeFileSearchResultDto {
        reportActivity(Bundle.message("tool.activity.scope.search.files.by.path", previewKeywordCount(pathKeyword, keywords)))
        return scope_search_files(
            query = pathKeyword,
            keywords = keywords,
            scope = scope,
            matchMode = ScopeFileSearchMode.PATH,
            caseSensitive = caseSensitive,
            directoryUrl = null,
            maxResults = maxResults,
            timeoutMillis = timeoutMillis,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
    }

    @McpTool
    @McpDescription("Shortcut: find files in a directory by glob pattern and scope.")
    suspend fun find_in_directory_using_glob(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        directoryUrl: String,
        @McpDescription("Glob pattern to match against file path.")
        globPattern: String,
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Maximum number of matched files to return.")
        maxResults: Int = 1000,
        @McpDescription("Timeout in milliseconds for this search.")
        timeoutMillis: Int = 30000,
        @McpDescription("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
    ): ScopeFileSearchResultDto {
        reportActivity(Bundle.message("tool.activity.scope.search.files.by.glob", globPattern.length))
        return scope_search_files(
            query = globPattern,
            scope = scope,
            matchMode = ScopeFileSearchMode.GLOB,
            caseSensitive = true,
            directoryUrl = directoryUrl,
            maxResults = maxResults,
            timeoutMillis = timeoutMillis,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )
    }

    private suspend fun resolveDirectory(directoryUrl: String): VirtualFile {
        val file = readAction { VirtualFileManager.getInstance().findFileByUrl(directoryUrl) }
            ?: mcpFail("Directory URL '$directoryUrl' not found.")
        if (!file.isDirectory) mcpFail("URL '$directoryUrl' is not a directory.")
        return file
    }

    private fun buildGlobMatchers(
        mode: ScopeFileSearchMode,
        query: String,
        caseSensitive: Boolean,
    ): Pair<PathMatcher?, PathMatcher?> {
        if (mode != ScopeFileSearchMode.GLOB) return null to null
        val matcher = runCatching { FileSystems.getDefault().getPathMatcher("glob:$query") }
            .getOrElse { error -> mcpFail("Invalid glob pattern '$query': ${error.message}") }
        val lowerCaseMatcher = if (caseSensitive) {
            null
        } else {
            runCatching { FileSystems.getDefault().getPathMatcher("glob:${query.lowercase()}") }.getOrNull()
        }
        return matcher to lowerCaseMatcher
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
                if (query.isBlank()) mcpFail("query must not be blank for GLOB mode.")
                if (explicitKeywords.isNotEmpty()) mcpFail("keywords are not supported for GLOB mode.")
                NormalizedSearchInput(queryForDisplay = query, textKeywords = emptyList())
            }

            ScopeFileSearchMode.NAME,
            ScopeFileSearchMode.PATH,
            ScopeFileSearchMode.NAME_OR_PATH,
            -> {
                val orderedKeywords = explicitKeywords.ifEmpty { splitQueryKeywords(query) }
                if (orderedKeywords.isEmpty()) {
                    mcpFail("Provide non-blank query or keywords for text match modes.")
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
}
