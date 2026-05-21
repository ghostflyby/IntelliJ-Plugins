/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.vfs.tools

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mcp.common.WorkspaceResourceException
import dev.ghostflyby.mcp.route.McpCallContext
import dev.ghostflyby.mcp.route.project
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable

internal class VfsSdkTools {

    @Serializable
    data class VfsExistsResult(val url: String, val exists: Boolean)

    @Serializable
    data class VfsRefreshResult(val url: String)

    @Serializable
    data class VfsBatchUrlResultItem(
        val input: String,
        val output: String? = null,
        val error: String? = null,
    )

    @Serializable
    data class VfsBatchUrlResult(
        val items: List<VfsBatchUrlResultItem>,
        val successCount: Int,
        val failureCount: Int,
    )

    @Serializable
    data class VfsBatchExistsResultItem(val url: String, val exists: Boolean)

    @Serializable
    data class VfsBatchExistsResult(val items: List<VfsBatchExistsResultItem>)

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.vfs_exists(
        @Description("VFS URL or project-relative path to check")
        url: String,
    ): CallToolResult {
        val resolvedUrl = resolveVfsUrl(url)
        val exists = readAction {
            service<VirtualFileManager>().findFileByUrl(resolvedUrl)?.exists() ?: false
        }
        val result = VfsExistsResult(resolvedUrl, exists)
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.vfs_refresh(
        @Description("VFS URL or project-relative path to refresh")
        url: String,
        @Description("Whether to run refresh asynchronously")
        async: Boolean = false,
        @Description("Refresh children recursively")
        recursive: Boolean = false,
    ): CallToolResult {
        val resolvedUrl = resolveVfsUrl(url)
        val file = readAction { service<VirtualFileManager>().findFileByUrl(resolvedUrl) }
            ?: return CallToolResult(
                content = listOf(TextContent(text = "File not found: $resolvedUrl")),
                isError = true,
            )
        if (!async) {
            backgroundWriteAction { file.refresh(false, recursive) {} }
        } else {
            CompletableDeferred<Unit>().also { deferred ->
                backgroundWriteAction { file.refresh(true, recursive) { deferred.complete(Unit) } }
            }.await()
        }
        return CallToolResult(
            content = listOf(TextContent(text = toolArgsJson.encodeToString(VfsRefreshResult(resolvedUrl)))),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.vfs_get_url_from_local_path(
        @Description("Project-relative local path to resolve")
        pathInProject: String,
        @Description("Refresh the file system before resolving the path")
        refreshIfNeeded: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        val url = resolveUrlFromProject(project, pathInProject, refreshIfNeeded)
        return CallToolResult(content = listOf(TextContent(text = url)))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.vfs_get_url_from_local_paths(
        @Description("Project-relative local paths to resolve")
        pathsInProject: List<String>,
        @Description("Refresh the file system before resolving the paths")
        refreshIfNeeded: Boolean = false,
        @Description("Whether to continue collecting results when individual paths fail")
        continueOnError: Boolean = true,
    ): CallToolResult {
        val project = call.project()
        val items = mutableListOf<VfsBatchUrlResultItem>()
        var successCount = 0
        var failureCount = 0
        for (p in pathsInProject) {
            try {
                val resolved = resolveUrlFromProject(project, p, refreshIfNeeded)
                items += VfsBatchUrlResultItem(input = p, output = resolved)
                successCount++
            } catch (e: Exception) {
                if (!continueOnError) throw e
                items += VfsBatchUrlResultItem(input = p, error = e.message)
                failureCount++
            }
        }
        val result = VfsBatchUrlResult(items = items, successCount = successCount, failureCount = failureCount)
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.vfs_get_local_path_from_url(
        @Description("VFS URL to resolve")
        url: String,
    ): CallToolResult {
        val localPath = resolveLocalPathFromUrl(url)
        return CallToolResult(content = listOf(TextContent(text = localPath)))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.vfs_get_local_paths_from_urls(
        @Description("VFS URLs to resolve")
        urls: List<String>,
        @Description("Whether to continue collecting results when individual URLs fail")
        continueOnError: Boolean = true,
    ): CallToolResult {
        val items = mutableListOf<VfsBatchUrlResultItem>()
        var successCount = 0
        var failureCount = 0
        for (u in urls) {
            try {
                val localPath = resolveLocalPathFromUrl(u)
                items += VfsBatchUrlResultItem(input = u, output = localPath)
                successCount++
            } catch (e: Exception) {
                if (!continueOnError) throw e
                items += VfsBatchUrlResultItem(input = u, error = e.message)
                failureCount++
            }
        }
        val result = VfsBatchUrlResult(items = items, successCount = successCount, failureCount = failureCount)
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.vfs_exists_many(
        @Description("VFS URLs to check")
        urls: List<String>,
    ): CallToolResult {
        val items = readAction {
            urls.map { u ->
                VfsBatchExistsResultItem(
                    url = u,
                    exists = service<VirtualFileManager>().findFileByUrl(u)?.exists() ?: false,
                )
            }
        }
        val result = VfsBatchExistsResult(items = items)
        return CallToolResult(content = listOf(TextContent(text = toolArgsJson.encodeToString(result))))
    }

    private suspend fun McpCallContext<CallToolRequest>.resolveVfsUrl(url: String): String {
        if (url.contains("://")) return url
        val project = call.project()
        return project.basePath?.let { "file://$it/$url" }
            ?: throw WorkspaceResourceException("Project has no base path, cannot resolve relative path: $url")
    }
}

private suspend fun resolveUrlFromProject(
    project: com.intellij.openapi.project.Project,
    pathInProject: String,
    refreshIfNeeded: Boolean,
): String {
    val basePath = project.basePath
        ?: throw WorkspaceResourceException("Project has no base path, cannot resolve relative path: $pathInProject")
    val nioPath = java.nio.file.Path.of(basePath, pathInProject)
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
