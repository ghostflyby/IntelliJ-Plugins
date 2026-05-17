/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.vfs.tools

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mcp.common.WorkspaceResourceException
import dev.ghostflyby.mcp.sdk.callToolWithProject
import dev.ghostflyby.mcp.sdk.tools.WorkspaceMcpProjectToolArguments
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Request
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
// Handlers
// ---------------------------------------------------------------------------

internal suspend fun ClientConnection.vfsGetUrlHandler(args: VfsGetUrlArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        sessionId = this.sessionId,
        projectArgs = args,
        relativePath = args.pathInProject,
    ) { project ->
        val url = resolveUrlFromLocalPath(project, args.pathInProject, args.refreshIfNeeded)
        CallToolResult(content = listOf(TextContent(text = url)))
    }
}

internal suspend fun ClientConnection.vfsGetUrlsHandler(args: VfsGetUrlsArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        sessionId = this.sessionId,
        projectArgs = args,
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

internal suspend fun ClientConnection.vfsGetLocalPathHandler(args: VfsGetLocalPathArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        sessionId = this.sessionId,
        projectArgs = args,
        vfsUrl = args.url,
    ) { _ ->
        val localPath = resolveLocalPathFromUrl(args.url)
        CallToolResult(content = listOf(TextContent(text = localPath)))
    }
}

internal suspend fun ClientConnection.vfsGetLocalPathsHandler(args: VfsGetLocalPathsArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        sessionId = this.sessionId,
        projectArgs = args,
        vfsUrl = args.urls.firstOrNull(),
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

internal suspend fun ClientConnection.vfsExistsManyHandler(args: VfsExistsManyArgs, request: Request?): CallToolResult {
    return callToolWithProject(
        sessionId = this.sessionId,
        projectArgs = args,
        vfsUrl = args.urls.firstOrNull(),
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

