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
import dev.ghostflyby.mcp.VFS_URL_PARAM_DESCRIPTION
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.isTooLargeForIntellijSense
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties

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
    )

    @McpTool
    @McpDescription("Resolve a project-relative local path to a VFS URL.")
    suspend fun vfs_get_url_from_local_path(
        @McpDescription("Project-relative local path to resolve.")
        pathInProject: String,
        @McpDescription("Refresh the file system before resolving the path.")
        refreshIfNeeded: Boolean = false,
    ): String {
        reportActivity(Bundle.message("tool.activity.vfs.get.url.from.local.path", pathInProject, refreshIfNeeded))
        val project = currentCoroutineContext().project
        val path = project.resolveInProject(pathInProject)
        val file = if (refreshIfNeeded) {
            backgroundWriteAction { VfsUtil.findFile(path, true) }
        } else {
            readAction { VfsUtil.findFile(path, false) }
        }
        return file?.url ?: mcpFail("File '$pathInProject' cannot be found in project")
    }

    @McpTool
    @McpDescription("Resolve a VFS URL to a local file-system path.")
    suspend fun vfs_get_local_path_from_url(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): String {
        reportActivity(Bundle.message("tool.activity.vfs.get.local.path.from.url", url))
        return readAction {
            val file = vfsManager.findFileByUrl(url) ?: mcpFail("File $url doesn't exist or can't be opened")
            if (file.fileSystem.protocol != "file") {
                mcpFail("File $url is not a local file")
            }
            file.toNioPath().toString()
        }
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

    suspend fun vfs_exists(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): Boolean {
        return readAction { vfsManager.findFileByUrl(url)?.exists() ?: false }
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
    ) {
        @Serializable
        class PropertyList(val names: List<String>)
    }

    @Serializable
    class VfsFileNamesResult(
        val names: List<String>,
    )


    @McpTool
    @McpDescription("Return file metadata for a VFS URL.")
    suspend fun vfs_file_stat(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Optional property names to include. Null or empty means all properties.")
        properties: VirtualFileStat.PropertyList?,
    ): VirtualFileStat {
        val propertyInfo = if (properties?.names.isNullOrEmpty()) "all" else properties?.names?.joinToString(",")
        reportActivity(Bundle.message("tool.activity.vfs.file.stat", url, propertyInfo ?: "all"))
        val stat = readAction {
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
        if (properties?.names.isNullOrEmpty())
            return stat
        val empty = VirtualFileStat()
        VirtualFileStat::class.memberProperties.forEach {
            if (it.name in properties.names && it is KMutableProperty1<VirtualFileStat, *>) {
                @Suppress("UNCHECKED_CAST")
                (it as KMutableProperty1<VirtualFileStat, Any?>).set(empty, it.get(stat))
            }
        }
        return empty
    }

    @McpTool
    @McpDescription("List direct child file names of a VFS directory.")
    suspend fun vfs_list_files(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): VfsFileNamesResult {
        reportActivity(Bundle.message("tool.activity.vfs.list.files", url))
        val names = readAction {
            val file = vfsManager.findFileByUrl(url) ?: mcpFail("File not found for URL: $url")
            if (!file.isDirectory) mcpFail("File at URL: $url is not a directory")
            file.children.map { it.name }
        }
        return VfsFileNamesResult(names = names)
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
            ),
        )
        return readFile(
            url = url,
            mode = mode,
            startChar = startChar,
            endCharExclusive = endCharExclusive,
            startLine = startLine,
            endLineInclusive = endLineInclusive,
        )
    }

    private suspend fun readFile(
        url: String,
        mode: ReadMode,
        startChar: Int = 0,
        endCharExclusive: Int? = null,
        startLine: Int = 1,
        endLineInclusive: Int? = null,
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
            )

            ReadMode.CHAR_RANGE -> {
                if (startChar < 0) mcpFail("startChar must be >= 0")
                val effectiveEnd = endCharExclusive ?: text.length
                if (effectiveEnd < startChar) {
                    mcpFail("endCharExclusive must be >= startChar")
                }
                if (startChar > text.length || effectiveEnd > text.length) {
                    mcpFail("Character range [$startChar, $effectiveEnd) is out of bounds for file length ${text.length}")
                }
                VfsReadResult(
                    mode = ReadMode.CHAR_RANGE,
                    url = url,
                    content = text.substring(startChar, effectiveEnd),
                    totalChars = text.length,
                    totalLines = lineStarts.size,
                    startChar = startChar,
                    endCharExclusive = effectiveEnd,
                )
            }

            ReadMode.LINE_RANGE -> {
                if (startLine < 1) mcpFail("startLine must be >= 1")
                val totalLines = lineStarts.size
                val effectiveEndLine = endLineInclusive ?: totalLines
                if (effectiveEndLine < startLine) {
                    mcpFail("endLineInclusive must be >= startLine")
                }
                if (startLine > totalLines || effectiveEndLine > totalLines) {
                    mcpFail("Line range [$startLine, $effectiveEndLine] is out of bounds for total lines $totalLines")
                }

                val startOffset = lineStarts[startLine - 1]
                val endOffset = if (effectiveEndLine == totalLines) text.length else lineStarts[effectiveEndLine]
                VfsReadResult(
                    mode = ReadMode.LINE_RANGE,
                    url = url,
                    content = text.substring(startOffset, endOffset),
                    totalChars = text.length,
                    totalLines = totalLines,
                    startLine = startLine,
                    endLineInclusive = effectiveEndLine,
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
    ): VfsReadResult {
        reportActivity(
            Bundle.message(
                "tool.activity.vfs.read.file.char.range",
                startChar,
                endCharExclusive?.toString() ?: "null",
                url,
            ),
        )
        return readFile(
            url = url,
            mode = ReadMode.CHAR_RANGE,
            startChar = startChar,
            endCharExclusive = endCharExclusive,
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
    ): VfsReadResult {
        reportActivity(
            Bundle.message(
                "tool.activity.vfs.read.file.line.range",
                startLine,
                endLineInclusive?.toString() ?: "null",
                url,
            ),
        )
        return readFile(
            url = url,
            mode = ReadMode.LINE_RANGE,
            startLine = startLine,
            endLineInclusive = endLineInclusive,
        )
    }

    private suspend fun getRegularFile(url: String): VirtualFile = readAction {
        vfsManager.findFileByUrl(url)?.takeUnless { it.isDirectory }
            ?: mcpFail("File not found or not a regular file for URL: $url")
    }

    private suspend fun loadText(file: VirtualFile): String {
        return readAction { VfsUtil.loadText(file).toString() }
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

    private suspend fun reportActivity(@NlsContexts.Label description: String) {
        currentCoroutineContext().reportToolActivity(description)
    }
}
