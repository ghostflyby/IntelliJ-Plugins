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

package dev.ghostflyby.mcp.vfs.tools

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mcp.resource.WorkspaceResourceException
import dev.ghostflyby.mcp.sdk.tools.*
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable
import java.nio.file.Path

// ---------------------------------------------------------------------------
// Serializable DTOs — matching old shapes from VfsMcpTools
// ---------------------------------------------------------------------------

@Schema
@Serializable
internal data class VfsBatchUrlResultItem(
    val input: String,
    val output: String? = null,
    val error: String? = null,
)

@Schema
@Serializable
internal data class VfsBatchUrlResult(
    val items: List<VfsBatchUrlResultItem>,
    val successCount: Int,
    val failureCount: Int,
)

@Schema
@Serializable
internal data class VfsBatchExistsResultItem(
    val url: String,
    val exists: Boolean,
)

@Schema
@Serializable
internal data class VfsBatchExistsResult(
    val items: List<VfsBatchExistsResultItem>,
)

// ---------------------------------------------------------------------------
// Tool argument DTOs
// ---------------------------------------------------------------------------

@Schema
@Serializable
internal data class VfsGetUrlArgs(
    val pathInProject: String,
    val refreshIfNeeded: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Schema
@Serializable
internal data class VfsGetUrlsArgs(
    val pathsInProject: List<String>,
    val refreshIfNeeded: Boolean = false,
    val continueOnError: Boolean = true,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Schema
@Serializable
internal data class VfsGetLocalPathArgs(
    val url: String,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Schema
@Serializable
internal data class VfsGetLocalPathsArgs(
    val urls: List<String>,
    val continueOnError: Boolean = true,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Schema
@Serializable
internal data class VfsExistsManyArgs(
    val urls: List<String>,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

// ---------------------------------------------------------------------------
// Tool descriptor factories
// ---------------------------------------------------------------------------

/**
 * Resolve a project-relative local path to a VFS URL.
 * Mirrors VfsMcpTools.vfs_get_url_from_local_path.
 */
internal fun vfsGetUrlFromLocalPathTool(): SdkToolDescriptor<VfsGetUrlArgs> {
    return sdkToolDescriptor<VfsGetUrlArgs>(
        name = "vfs_get_url_from_local_path",
        description = "Resolve a project-relative local path to a VFS URL. " +
            "This is a convenience helper: for local files, you can directly pass " +
            "a file:///absolute/path URL to tools that accept VFS URLs.",
        handler = { args -> vfsGetUrlHandler(this, args) },
    )
}

/**
 * Resolve multiple project-relative local paths to VFS URLs.
 * Mirrors VfsMcpTools.vfs_get_url_from_local_paths.
 */
internal fun vfsGetUrlsFromLocalPathsTool(): SdkToolDescriptor<VfsGetUrlsArgs> {
    return sdkToolDescriptor<VfsGetUrlsArgs>(
        name = "vfs_get_url_from_local_paths",
        description = "Resolve multiple project-relative local paths to VFS URLs. " +
            "This is a convenience helper: for local files, you can directly pass " +
            "file:///absolute/path URLs to tools that accept VFS URLs.",
        handler = { args -> vfsGetUrlsHandler(this, args) },
    )
}

/**
 * Resolve a VFS URL to a local filesystem path.
 * Mirrors VfsMcpTools.vfs_get_local_path_from_url.
 */
internal fun vfsGetLocalPathFromUrlTool(): SdkToolDescriptor<VfsGetLocalPathArgs> {
    return sdkToolDescriptor<VfsGetLocalPathArgs>(
        name = "vfs_get_local_path_from_url",
        description = "Resolve a VFS URL to a local file-system path.",
        handler = { args -> vfsGetLocalPathHandler(this, args) },
    )
}

/**
 * Resolve multiple VFS URLs to local filesystem paths.
 * Mirrors VfsMcpTools.vfs_get_local_paths_from_urls.
 */
internal fun vfsGetLocalPathsFromUrlsTool(): SdkToolDescriptor<VfsGetLocalPathsArgs> {
    return sdkToolDescriptor<VfsGetLocalPathsArgs>(
        name = "vfs_get_local_paths_from_urls",
        description = "Resolve multiple VFS URLs to local file-system paths.",
        handler = { args -> vfsGetLocalPathsHandler(this, args) },
    )
}

/**
 * Check whether multiple VFS URLs currently resolve to existing files or directories.
 * Mirrors VfsMcpTools.vfs_exists_many.
 */
internal fun vfsExistsManySdkTool(): SdkToolDescriptor<VfsExistsManyArgs> {
    return sdkToolDescriptor<VfsExistsManyArgs>(
        name = "vfs_exists_many",
        description = "Check whether multiple VFS URLs currently resolve to existing files or directories.",
        handler = { args -> vfsExistsManyHandler(this, args) },
    )
}

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

private suspend fun vfsGetUrlHandler(ctx: SdkToolHandlerContext, args: VfsGetUrlArgs): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        relativePath = args.pathInProject,
    ) { project ->
        val url = resolveUrlFromLocalPath(project, args.pathInProject, args.refreshIfNeeded)
        CallToolResult(content = listOf(TextContent(text = url)))
    }
}

private suspend fun vfsGetUrlsHandler(ctx: SdkToolHandlerContext, args: VfsGetUrlsArgs): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        relativePath = args.pathsInProject.firstOrNull(),
    ) { project ->
        val items = mutableListOf<VfsBatchUrlResultItem>()
        var successCount = 0
        var failureCount = 0
        for (pathInProject in args.pathsInProject) {
            try {
                val url = resolveUrlFromLocalPath(project, pathInProject, args.refreshIfNeeded)
                items += VfsBatchUrlResultItem(input = pathInProject, output = url, error = null)
                successCount++
            } catch (e: Exception) {
                if (!args.continueOnError) throw e
                items += VfsBatchUrlResultItem(input = pathInProject, output = null, error = e.message)
                failureCount++
            }
        }
        val result = VfsBatchUrlResult(items = items, successCount = successCount, failureCount = failureCount)
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun vfsGetLocalPathHandler(ctx: SdkToolHandlerContext, args: VfsGetLocalPathArgs): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.url,
    ) { _ ->
        val localPath = resolveLocalPathFromUrl(args.url)
        CallToolResult(content = listOf(TextContent(text = localPath)))
    }
}

private suspend fun vfsGetLocalPathsHandler(ctx: SdkToolHandlerContext, args: VfsGetLocalPathsArgs): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.urls.firstOrNull(),
    ) { _ ->
        val items = mutableListOf<VfsBatchUrlResultItem>()
        var successCount = 0
        var failureCount = 0
        for (url in args.urls) {
            try {
                val localPath = resolveLocalPathFromUrl(url)
                items += VfsBatchUrlResultItem(input = url, output = localPath, error = null)
                successCount++
            } catch (e: Exception) {
                if (!args.continueOnError) throw e
                items += VfsBatchUrlResultItem(input = url, output = null, error = e.message)
                failureCount++
            }
        }
        val result = VfsBatchUrlResult(items = items, successCount = successCount, failureCount = failureCount)
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

private suspend fun vfsExistsManyHandler(ctx: SdkToolHandlerContext, args: VfsExistsManyArgs): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.urls.firstOrNull(),
    ) { _ ->
        val result = checkExistsMany(args.urls)
        CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

private suspend fun resolveUrlFromLocalPath(
    project: Project,
    pathInProject: String,
    refreshIfNeeded: Boolean,
): String {
    val basePath = project.basePath
        ?: throw WorkspaceResourceException("Project has no base path, cannot resolve relative path: $pathInProject")
    val nioPath = Path.of(basePath, pathInProject)
    val directFile = if (refreshIfNeeded) {
        backgroundWriteAction { VfsUtil.findFile(nioPath, true) }
    } else {
        readAction { VfsUtil.findFile(nioPath, false) }
    }
    val file = directFile ?: if (!refreshIfNeeded) {
        backgroundWriteAction { VfsUtil.findFile(nioPath, true) }
    } else {
        null
    }
    return file?.url ?: throw WorkspaceResourceException("File '$pathInProject' cannot be found in project")
}

private suspend fun resolveLocalPathFromUrl(url: String): String {
    val vfsManager = service<VirtualFileManager>()
    return readAction {
        val file = vfsManager.findFileByUrl(url)
            ?: throw WorkspaceResourceException("File $url doesn't exist or can't be opened")
        if (file.fileSystem.protocol != "file") {
            throw WorkspaceResourceException("File $url is not a local file")
        }
        file.toNioPath().toString()
    }
}

private suspend fun checkExistsMany(urls: List<String>): VfsBatchExistsResult {
    val vfsManager = service<VirtualFileManager>()
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

