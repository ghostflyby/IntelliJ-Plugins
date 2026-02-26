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

package dev.ghostflyby.mcp.vfs

import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.common.VFS_URL_PARAM_DESCRIPTION
import dev.ghostflyby.mcp.common.batchTry
import dev.ghostflyby.mcp.common.reportActivity
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.isTooLargeForIntellijSense
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

@Suppress("FunctionName")
internal class VfsMcpTools : McpToolset {

    private val vfsManager get() = service<VirtualFileManager>()

    @Serializable
    enum class ReadMode {
        FULL,
        CHAR_RANGE,
        LINE_RANGE,
    }

    @Serializable
    class VfsReadResult(
        val mode: ReadMode,
        val url: String,
        val content: String,
        val totalChars: Int,
        val totalLines: Int,
        val startChar: Int? = null,
        val endCharExclusive: Int? = null,
        val startLine: Int? = null,
        val endLineInclusive: Int? = null,
        val clamped: Boolean = false,
    )

    @Serializable
    class VfsApiSignatureMatch(
        val line: Int,
        val text: String,
    )

    @Serializable
    class VfsApiSignatureResult(
        val url: String,
        val fileType: String,
        val totalLines: Int,
        val imports: List<String>,
        val typeDeclaration: String? = null,
        val matchedMembers: List<VfsApiSignatureMatch> = emptyList(),
        val previewStartLine: Int,
        val previewEndLineInclusive: Int,
        val preview: String,
        val diagnostics: List<String> = emptyList(),
    )

    @McpTool
    @McpDescription(
        "Resolve a project-relative local path to a VFS URL. " +
            "This is a convenience helper: for local files, you can directly pass " +
            "a file:///absolute/path URL to tools that accept VFS URLs.",
    )
    suspend fun vfs_get_url_from_local_path(
        @McpDescription("Project-relative local path to resolve (convenience input).")
        pathInProject: String,
        @McpDescription("Refresh the file system before resolving the path.")
        refreshIfNeeded: Boolean = false,
    ): String {
        reportActivity(Bundle.message("tool.activity.vfs.get.url.from.local.path", pathInProject, refreshIfNeeded))
        return resolveUrlFromLocalPath(pathInProject, refreshIfNeeded)
    }

    @McpTool
    @McpDescription(
        "Resolve multiple project-relative local paths to VFS URLs. " +
            "This is a convenience helper: for local files, you can directly pass " +
            "file:///absolute/path URLs to tools that accept VFS URLs.",
    )
    suspend fun vfs_get_url_from_local_paths(
        @McpDescription("Project-relative local paths to resolve (convenience input).")
        pathsInProject: List<String>,
        @McpDescription("Refresh the file system before resolving each path.")
        refreshIfNeeded: Boolean = false,
        @McpDescription("Whether to continue collecting results after a single path fails.")
        continueOnError: Boolean = true,
    ): VfsBatchUrlResult {
        reportActivity(Bundle.message("tool.activity.vfs.get.url.from.local.paths", pathsInProject.size, refreshIfNeeded, continueOnError))
        val items = mutableListOf<VfsBatchUrlResultItem>()
        var successCount = 0
        var failureCount = 0
        for (pathInProject in pathsInProject) {
            val output = batchTry(continueOnError) {
                resolveUrlFromLocalPath(pathInProject, refreshIfNeeded)
            }
            if (output.error == null) {
                successCount++
            } else {
                failureCount++
            }
            items += VfsBatchUrlResultItem(
                input = pathInProject,
                output = output.value,
                error = output.error,
            )
        }
        return VfsBatchUrlResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
    }

    @McpTool
    @McpDescription("Resolve a VFS URL to a local file-system path.")
    suspend fun vfs_get_local_path_from_url(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): String {
        reportActivity(Bundle.message("tool.activity.vfs.get.local.path.from.url", url))
        return resolveLocalPathFromUrl(url)
    }

    @McpTool
    @McpDescription("Resolve multiple VFS URLs to local file-system paths.")
    suspend fun vfs_get_local_paths_from_urls(
        @McpDescription("VFS URLs to resolve.")
        urls: List<String>,
        @McpDescription("Whether to continue collecting results after a single URL fails.")
        continueOnError: Boolean = true,
    ): VfsBatchUrlResult {
        reportActivity(Bundle.message("tool.activity.vfs.get.local.path.from.urls", urls.size, continueOnError))
        val items = mutableListOf<VfsBatchUrlResultItem>()
        var successCount = 0
        var failureCount = 0
        for (url in urls) {
            val output = batchTry(continueOnError) {
                resolveLocalPathFromUrl(url)
            }
            if (output.error == null) {
                successCount++
            } else {
                failureCount++
            }
            items += VfsBatchUrlResultItem(
                input = url,
                output = output.value,
                error = output.error,
            )
        }
        return VfsBatchUrlResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
    }

    @McpTool
    @McpDescription("Refresh a VFS file or directory.")
    suspend fun vfs_refresh(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Run refresh asynchronously.")
        async: Boolean = false,
        @McpDescription("Refresh children recursively (effective for directories).")
        recursive: Boolean = false,
    ) {
        reportActivity(Bundle.message("tool.activity.vfs.refresh", url, async, recursive))
        val file = readAction { vfsManager.findFileByUrl(url) }
            ?: mcpFail("File not found for URL: $url")
        if (!async) {
            backgroundWriteAction {
                file.refresh(false, recursive) { }
            }
            return
        }

        val deferred = CompletableDeferred<Unit>()
        backgroundWriteAction {
            file.refresh(true, recursive) {
                deferred.complete(Unit)
            }
        }
        deferred.await()
    }

    @McpTool
    @McpDescription("Check whether a VFS URL currently resolves to an existing file or directory.")
    suspend fun vfs_exists(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): Boolean {
        reportActivity(Bundle.message("tool.activity.vfs.exists", url))
        return readAction { vfsManager.findFileByUrl(url)?.exists() ?: false }
    }

    @McpTool
    @McpDescription("Check whether multiple VFS URLs currently resolve to existing files or directories.")
    suspend fun vfs_exists_many(
        @McpDescription("VFS URLs to check.")
        urls: List<String>,
    ): VfsBatchExistsResult {
        reportActivity(Bundle.message("tool.activity.vfs.exists.many", urls.size))
        val items = readAction {
            urls.map { url ->
                VfsBatchExistsResultItem(
                    url = url,
                    exists = vfsManager.findFileByUrl(url)?.exists() ?: false,
                )
            }
        }
        return VfsBatchExistsResult(items = items)
    }

    @Serializable
    class VirtualFileStat(
        val name: String? = null,
        val path: String? = null,
        val isDirectory: Boolean? = null,
        val length: Long? = null,
        val lastModified: Long? = null,
        val isToolLargeForIntellijSense: Boolean? = null,
        val isValid: Boolean? = null,
        val isWritable: Boolean? = null,
        val fileType: String? = null,
    )

    @Serializable
    class VfsFileNamesResult(
        val names: List<String>,
    )

    @Serializable
    class VfsReadRequest(
        val url: String,
        val mode: ReadMode = ReadMode.FULL,
        val startChar: Int = 0,
        val endCharExclusive: Int? = null,
        val startLine: Int = 1,
        val endLineInclusive: Int? = null,
        val clampOutOfBounds: Boolean = false,
    )

    @Serializable
    class VfsBatchUrlResultItem(
        val input: String,
        val output: String? = null,
        val error: String? = null,
    )

    @Serializable
    class VfsBatchExistsResultItem(
        val url: String,
        val exists: Boolean,
    )

    @Serializable
    class VfsBatchFileStatResultItem(
        val url: String,
        val stat: VirtualFileStat? = null,
        val error: String? = null,
    )

    @Serializable
    class VfsBatchListFilesResultItem(
        val url: String,
        val names: List<String> = emptyList(),
        val error: String? = null,
    )

    @Serializable
    class VfsBatchReadResultItem(
        val index: Int,
        val request: VfsReadRequest,
        val result: VfsReadResult? = null,
        val error: String? = null,
    )

    @Serializable
    class VfsBatchUrlResult(
        val items: List<VfsBatchUrlResultItem>,
        val successCount: Int,
        val failureCount: Int,
    )

    @Serializable
    class VfsBatchExistsResult(
        val items: List<VfsBatchExistsResultItem>,
    )

    @Serializable
    class VfsBatchFileStatResult(
        val items: List<VfsBatchFileStatResultItem>,
        val successCount: Int,
        val failureCount: Int,
    )

    @Serializable
    class VfsBatchListFilesResult(
        val items: List<VfsBatchListFilesResultItem>,
        val successCount: Int,
        val failureCount: Int,
    )

    @Serializable
    class VfsBatchReadResult(
        val items: List<VfsBatchReadResultItem>,
        val successCount: Int,
        val failureCount: Int,
    )


    @McpTool
    @McpDescription("Return file metadata for a VFS URL.")
    suspend fun vfs_file_stat(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): VirtualFileStat {
        reportActivity(Bundle.message("tool.activity.vfs.file.stat", url))
        return readFileStat(url)
    }

    @McpTool
    @McpDescription("Return file metadata for multiple VFS URLs.")
    suspend fun vfs_file_stats(
        @McpDescription("VFS URLs to read metadata from.")
        urls: List<String>,
        @McpDescription("Whether to continue collecting results after a single URL fails.")
        continueOnError: Boolean = true,
    ): VfsBatchFileStatResult {
        reportActivity(Bundle.message("tool.activity.vfs.file.stats", urls.size, continueOnError))
        val items = mutableListOf<VfsBatchFileStatResultItem>()
        var successCount = 0
        var failureCount = 0
        for (url in urls) {
            val output = batchTry(continueOnError) {
                readFileStat(url)
            }
            if (output.error == null) {
                successCount++
            } else {
                failureCount++
            }
            items += VfsBatchFileStatResultItem(
                url = url,
                stat = output.value,
                error = output.error,
            )
        }
        return VfsBatchFileStatResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
    }

    @McpTool
    @McpDescription("List direct child file names of a VFS directory.")
    suspend fun vfs_list_files(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): VfsFileNamesResult {
        reportActivity(Bundle.message("tool.activity.vfs.list.files", url))
        return VfsFileNamesResult(names = listFileNames(url))
    }

    @McpTool
    @McpDescription("List direct child file names for multiple VFS directory URLs.")
    suspend fun vfs_list_files_many(
        @McpDescription("VFS directory URLs to list.")
        urls: List<String>,
        @McpDescription("Whether to continue collecting results after a single URL fails.")
        continueOnError: Boolean = true,
    ): VfsBatchListFilesResult {
        reportActivity(Bundle.message("tool.activity.vfs.list.files.many", urls.size, continueOnError))
        val items = mutableListOf<VfsBatchListFilesResultItem>()
        var successCount = 0
        var failureCount = 0
        for (url in urls) {
            val output = batchTry(continueOnError) {
                listFileNames(url)
            }
            if (output.error == null) {
                successCount++
            } else {
                failureCount++
            }
            items += VfsBatchListFilesResultItem(
                url = url,
                names = output.value ?: emptyList(),
                error = output.error,
            )
        }
        return VfsBatchListFilesResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
    }

    @McpTool
    @McpDescription("Read file content from VFS using full content, character range, or line range strategy. This only reads persisted VFS content; for unsaved editor text, use document_* tools.")
    suspend fun vfs_read_file(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Read strategy: FULL, CHAR_RANGE, or LINE_RANGE.")
        mode: ReadMode = ReadMode.FULL,
        @McpDescription("Character range start offset (inclusive) for CHAR_RANGE mode.")
        startChar: Int = 0,
        @McpDescription("Character range end offset (exclusive) for CHAR_RANGE mode. Null means end of file.")
        endCharExclusive: Int? = null,
        @McpDescription("Line range start (1-based, inclusive) for LINE_RANGE mode.")
        startLine: Int = 1,
        @McpDescription("Line range end (1-based, inclusive) for LINE_RANGE mode. Null means last line.")
        endLineInclusive: Int? = null,
        @McpDescription("Clamp out-of-bounds char/line range inputs to valid file bounds instead of failing.")
        clampOutOfBounds: Boolean = false,
    ): VfsReadResult {
        reportActivity(
            Bundle.message(
                "tool.activity.vfs.read.file",
                mode.name,
                url,
                startChar,
                endCharExclusive?.toString() ?: "null",
                startLine,
                endLineInclusive?.toString() ?: "null",
                clampOutOfBounds,
            ),
        )
        return readFile(
            url = url,
            mode = mode,
            startChar = startChar,
            endCharExclusive = endCharExclusive,
            startLine = startLine,
            endLineInclusive = endLineInclusive,
            clampOutOfBounds = clampOutOfBounds,
        )
    }

    @McpTool
    @McpDescription("Read multiple files from VFS with per-item mode/range settings.")
    suspend fun vfs_read_files(
        @McpDescription("Read requests; each request maps to vfs_read_file parameters.")
        requests: List<VfsReadRequest>,
        @McpDescription("Whether to continue collecting results after a single request fails.")
        continueOnError: Boolean = true,
    ): VfsBatchReadResult {
        reportActivity(Bundle.message("tool.activity.vfs.read.files", requests.size, continueOnError))
        val items = mutableListOf<VfsBatchReadResultItem>()
        var successCount = 0
        var failureCount = 0
        requests.forEachIndexed { index, request ->
            val output = batchTry(continueOnError) {
                readFile(
                    url = request.url,
                    mode = request.mode,
                    startChar = request.startChar,
                    endCharExclusive = request.endCharExclusive,
                    startLine = request.startLine,
                    endLineInclusive = request.endLineInclusive,
                    clampOutOfBounds = request.clampOutOfBounds,
                )
            }
            if (output.error == null) {
                successCount++
            } else {
                failureCount++
            }
            items += VfsBatchReadResultItem(
                index = index,
                request = request,
                result = output.value,
                error = output.error,
            )
        }
        return VfsBatchReadResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
    }

    private suspend fun readFile(
        url: String,
        mode: ReadMode,
        startChar: Int = 0,
        endCharExclusive: Int? = null,
        startLine: Int = 1,
        endLineInclusive: Int? = null,
        clampOutOfBounds: Boolean = false,
    ): VfsReadResult {
        val file = getRegularFile(url)
        val text = loadText(file)
        val lineStarts = computeLineStarts(text)

        return when (mode) {
            ReadMode.FULL -> VfsReadResult(
                mode = ReadMode.FULL,
                url = url,
                content = text,
                totalChars = text.length,
                totalLines = lineStarts.size,
                startChar = 0,
                endCharExclusive = text.length,
                startLine = 1,
                endLineInclusive = lineStarts.size,
                clamped = false,
            )

            ReadMode.CHAR_RANGE -> {
                val requestedEnd = endCharExclusive ?: text.length
                val clampedStart = if (clampOutOfBounds) startChar.coerceIn(0, text.length) else startChar
                val rawEnd = if (clampOutOfBounds) requestedEnd.coerceIn(0, text.length) else requestedEnd
                val clampedEnd = if (clampOutOfBounds) maxOf(clampedStart, rawEnd) else rawEnd
                val wasClamped = clampedStart != startChar || clampedEnd != requestedEnd

                if (!clampOutOfBounds) {
                    if (startChar < 0) mcpFail("startChar must be >= 0")
                    if (requestedEnd < startChar) {
                        mcpFail("endCharExclusive must be >= startChar")
                    }
                    if (startChar > text.length || requestedEnd > text.length) {
                        mcpFail("Character range [$startChar, $requestedEnd) is out of bounds for file length ${text.length}")
                    }
                }
                VfsReadResult(
                    mode = ReadMode.CHAR_RANGE,
                    url = url,
                    content = text.substring(clampedStart, clampedEnd),
                    totalChars = text.length,
                    totalLines = lineStarts.size,
                    startChar = clampedStart,
                    endCharExclusive = clampedEnd,
                    clamped = wasClamped,
                )
            }

            ReadMode.LINE_RANGE -> {
                val totalLines = lineStarts.size
                val requestedEndLine = endLineInclusive ?: totalLines
                val clampedStartLine = if (clampOutOfBounds) startLine.coerceIn(1, totalLines) else startLine
                val rawEndLine = if (clampOutOfBounds) requestedEndLine.coerceIn(1, totalLines) else requestedEndLine
                val clampedEndLine = if (clampOutOfBounds) maxOf(clampedStartLine, rawEndLine) else rawEndLine
                val wasClamped = clampedStartLine != startLine || clampedEndLine != requestedEndLine

                if (!clampOutOfBounds) {
                    if (startLine < 1) mcpFail("startLine must be >= 1")
                    if (requestedEndLine < startLine) {
                        mcpFail("endLineInclusive must be >= startLine")
                    }
                    if (startLine > totalLines || requestedEndLine > totalLines) {
                        mcpFail("Line range [$startLine, $requestedEndLine] is out of bounds for total lines $totalLines")
                    }
                }

                val startOffset = lineStarts[clampedStartLine - 1]
                val endOffset = if (clampedEndLine == totalLines) text.length else lineStarts[clampedEndLine]
                VfsReadResult(
                    mode = ReadMode.LINE_RANGE,
                    url = url,
                    content = text.substring(startOffset, endOffset),
                    totalChars = text.length,
                    totalLines = totalLines,
                    startLine = clampedStartLine,
                    endLineInclusive = clampedEndLine,
                    clamped = wasClamped,
                )
            }
        }
    }

    @McpTool
    @McpDescription("Read the whole file content from VFS. For unsaved editor text, use document_* tools.")
    suspend fun vfs_read_file_full(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): VfsReadResult {
        reportActivity(Bundle.message("tool.activity.vfs.read.file.full", url))
        return readFile(
            url = url,
            mode = ReadMode.FULL,
        )
    }

    @McpTool
    @McpDescription("Read file content from VFS by character range [startChar, endCharExclusive). For unsaved editor text, use document_* tools.")
    suspend fun vfs_read_file_by_char_range(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Character range start offset (inclusive).")
        startChar: Int,
        @McpDescription("Character range end offset (exclusive). Null means end of file.")
        endCharExclusive: Int? = null,
        @McpDescription("Clamp out-of-bounds range to file bounds instead of failing.")
        clampOutOfBounds: Boolean = false,
    ): VfsReadResult {
        reportActivity(
            Bundle.message(
                "tool.activity.vfs.read.file.char.range",
                startChar,
                endCharExclusive?.toString() ?: "null",
                url,
                clampOutOfBounds,
            ),
        )
        return readFile(
            url = url,
            mode = ReadMode.CHAR_RANGE,
            startChar = startChar,
            endCharExclusive = endCharExclusive,
            clampOutOfBounds = clampOutOfBounds,
        )
    }

    @McpTool
    @McpDescription("Read file content from VFS by line range [startLine, endLineInclusive] with 1-based line numbers. For unsaved editor text, use document_* tools.")
    suspend fun vfs_read_file_by_line_range(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Line range start (1-based, inclusive).")
        startLine: Int,
        @McpDescription("Line range end (1-based, inclusive). Null means last line.")
        endLineInclusive: Int? = null,
        @McpDescription("Clamp out-of-bounds range to file bounds instead of failing.")
        clampOutOfBounds: Boolean = false,
    ): VfsReadResult {
        reportActivity(
            Bundle.message(
                "tool.activity.vfs.read.file.line.range",
                startLine,
                endLineInclusive?.toString() ?: "null",
                url,
                clampOutOfBounds,
            ),
        )
        return readFile(
            url = url,
            mode = ReadMode.LINE_RANGE,
            startLine = startLine,
            endLineInclusive = endLineInclusive,
            clampOutOfBounds = clampOutOfBounds,
        )
    }

    @McpTool
    @McpDescription(
        "Read a structured API signature snapshot from a source file, including imports, main type declaration, and matched members.",
    )
    suspend fun vfs_read_api_signature(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        fileUrl: String,
        @McpDescription("Optional symbol/member name to focus on.")
        symbolName: String? = null,
        @McpDescription("Maximum number of preview lines to return.")
        maxLines: Int = 120,
    ): VfsApiSignatureResult {
        if (maxLines < 1) {
            mcpFail("maxLines must be >= 1")
        }
        reportActivity(
            Bundle.message(
                "tool.activity.vfs.read.api.signature",
                fileUrl,
                symbolName ?: "<none>",
                maxLines,
            ),
        )
        val file = getRegularFile(fileUrl)
        val fileType = readAction { file.fileType.name }
        val full = readFile(
            url = fileUrl,
            mode = ReadMode.FULL,
        )
        val lines = full.content.split('\n')
        val totalLines = lines.size
        val importRegex = Regex("^\\s*import\\s+.+")
        val typeRegex = Regex("\\b(class|interface|enum|record|object|trait|struct)\\b")
        val memberRegex = Regex(
            "^\\s*(public|private|protected|internal|static|final|abstract|suspend|fun|def|func|val|var)\\b.*",
        )
        val normalizedSymbol = symbolName?.trim()?.takeIf { it.isNotEmpty() }
        val symbolRegex = normalizedSymbol?.let { Regex("\\b${Regex.escape(it)}\\b") }

        val imports = mutableListOf<String>()
        var typeDeclaration: String? = null
        val memberMatches = mutableListOf<VfsApiSignatureMatch>()
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd()
            if (imports.size < 200 && importRegex.containsMatchIn(line)) {
                imports += line
            }
            if (typeDeclaration == null && typeRegex.containsMatchIn(line)) {
                typeDeclaration = line
            }
            if (normalizedSymbol != null) {
                if (symbolRegex?.containsMatchIn(line) == true && memberRegex.containsMatchIn(line)) {
                    memberMatches += VfsApiSignatureMatch(line = index + 1, text = line)
                }
            } else if (memberMatches.size < 20 && memberRegex.containsMatchIn(line)) {
                memberMatches += VfsApiSignatureMatch(line = index + 1, text = line)
            }
        }

        val previewStart = when {
            memberMatches.isNotEmpty() -> maxOf(1, memberMatches.first().line - 10)
            else -> 1
        }
        val previewEnd = minOf(totalLines, previewStart + maxLines - 1)
        val preview = lines.subList(previewStart - 1, previewEnd).joinToString("\n")
        val diagnostics = buildList {
            if (imports.isEmpty()) add("No import statements found in previewed content.")
            if (typeDeclaration == null) add("No primary type declaration detected by heuristic parser.")
            if (normalizedSymbol != null && memberMatches.isEmpty()) {
                add("No member signature matched symbolName='$normalizedSymbol'.")
            }
        }
        return VfsApiSignatureResult(
            url = fileUrl,
            fileType = fileType,
            totalLines = totalLines,
            imports = imports,
            typeDeclaration = typeDeclaration,
            matchedMembers = memberMatches,
            previewStartLine = previewStart,
            previewEndLineInclusive = previewEnd,
            preview = preview,
            diagnostics = diagnostics,
        )
    }

    private suspend fun resolveUrlFromLocalPath(pathInProject: String, refreshIfNeeded: Boolean): String {
        val project = currentCoroutineContext().project
        val path = project.resolveInProject(pathInProject)
        val directFile = if (refreshIfNeeded) {
            backgroundWriteAction { VfsUtil.findFile(path, true) }
        } else {
            readAction { VfsUtil.findFile(path, false) }
        }
        val file = directFile ?: if (!refreshIfNeeded) {
            backgroundWriteAction { VfsUtil.findFile(path, true) }
        } else {
            null
        }
        return file?.url ?: mcpFail("File '$pathInProject' cannot be found in project")
    }

    private suspend fun resolveLocalPathFromUrl(url: String): String {
        return readAction {
            val file = vfsManager.findFileByUrl(url) ?: mcpFail("File $url doesn't exist or can't be opened")
            if (file.fileSystem.protocol != "file") {
                mcpFail("File $url is not a local file")
            }
            file.toNioPath().toString()
        }
    }

    private suspend fun readFileStat(url: String): VirtualFileStat {
        return readAction {
            val file = vfsManager.findFileByUrl(url) ?: mcpFail("File not found for URL: $url")
            VirtualFileStat(
                name = file.name,
                path = file.path,
                isDirectory = file.isDirectory,
                length = file.length,
                lastModified = file.timeStamp,
                isToolLargeForIntellijSense = file.isTooLargeForIntellijSense(),
                isValid = file.isValid,
                isWritable = file.isWritable,
                fileType = file.fileType.name,
            )
        }
    }

    private suspend fun listFileNames(url: String): List<String> {
        return readAction {
            val file = vfsManager.findFileByUrl(url) ?: mcpFail("File not found for URL: $url")
            if (!file.isDirectory) mcpFail("File at URL: $url is not a directory")
            file.children.map { it.name }
        }
    }

    private suspend fun getRegularFile(url: String): VirtualFile = readAction {
        vfsManager.findFileByUrl(url)?.takeUnless { it.isDirectory }
            ?: mcpFail("File not found or not a regular file for URL: $url")
    }

    private suspend fun loadText(file: VirtualFile): String {
        return readAction { VfsUtil.loadText(file) }
    }

    private fun computeLineStarts(text: CharSequence): List<Int> {
        val starts = mutableListOf(0)
        text.forEachIndexed { index, c ->
            if (c == '\n') {
                starts += index + 1
            }
        }
        return starts
    }
}
