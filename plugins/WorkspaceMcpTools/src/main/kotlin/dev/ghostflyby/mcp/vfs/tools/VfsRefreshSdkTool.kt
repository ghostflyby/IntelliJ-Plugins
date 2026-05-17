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
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mcp.sdk.callToolWithProject
import dev.ghostflyby.mcp.sdk.tools.WorkspaceMcpProjectToolArguments
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Request
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable

/**
 * Typed arguments for the vfs_refresh tool.
 */
@Schema
@Serializable
internal data class VfsRefreshArgs(
    @Description("VFS URL to refresh")
    val url: String,
    @Description("Whether to run refresh asynchronously")
    val async: Boolean = false,
    @Description("Refresh children recursively")
    val recursive: Boolean = false,
    @Description("Explicit project key for multi-project workspaces")
    override val projectKey: String? = null,
    @Description("Explicit project base path for resolution")
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

/**
 * Typed arguments for the vfs_exists tool.
 */
@Schema
@Serializable
internal data class VfsExistsArgs(
    @Description("VFS URL or project-relative path to check")
    val url: String,
    @Description("Explicit project key for multi-project workspaces")
    override val projectKey: String? = null,
    @Description("Explicit project base path for resolution")
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Schema
@Serializable
internal data class VfsExistsResult(
    @Description("VFS URL that was checked")
    val url: String,
    @Description("Whether the file or directory exists")
    val exists: Boolean,
)

internal suspend fun vfsRefreshHandler(args: VfsRefreshArgs, request: Request?): CallToolResult {
    val url = args.url
    val async = args.async
    val recursive = args.recursive

    val isRawVfs = url.contains("://")

    return callToolWithProject(
        projectArgs = args,
        vfsUrl = if (isRawVfs) url else null,
        relativePath = if (!isRawVfs) url else null,
    ) { project ->
        val resolvedUrl = if (isRawVfs) {
            url
        } else {
            resolvedRelativeFileUrl(project, url)
                ?: return@callToolWithProject errorResult("Project has no base path, cannot resolve relative path: $url")
        }

        val vfsManager = service<VirtualFileManager>()
        val file = readAction { vfsManager.findFileByUrl(resolvedUrl) }
        if (file == null) {
            return@callToolWithProject errorResult("File not found: $resolvedUrl")
        }

        if (!async) {
            backgroundWriteAction { file.refresh(false, recursive) {} }
        } else {
            CompletableDeferred<Unit>().also { deferred ->
                backgroundWriteAction { file.refresh(true, recursive) { deferred.complete(Unit) } }
            }.await()
        }

        CallToolResult(
            content = listOf(TextContent(text = "Refreshed: $resolvedUrl (async=$async, recursive=$recursive)")),
        )
    }
}

internal suspend fun vfsExistsHandler(args: VfsExistsArgs, request: Request?): CallToolResult {
    val url = args.url
    val isRawVfs = url.contains("://")

    return callToolWithProject(
        projectArgs = args,
        vfsUrl = if (isRawVfs) url else null,
        relativePath = if (!isRawVfs) url else null,
    ) { project ->
        val resolvedUrl = if (isRawVfs) {
            url
        } else {
            resolvedRelativeFileUrl(project, url)
                ?: return@callToolWithProject errorResult("Project has no base path, cannot resolve relative path: $url")
        }

        val vfsManager = service<VirtualFileManager>()
        val exists = readAction { vfsManager.findFileByUrl(resolvedUrl)?.exists() ?: false }
        CallToolResult(
            content = listOf(TextContent(text = encodeVfsExistsResult(VfsExistsResult(resolvedUrl, exists)))),
        )
    }
}

private fun resolvedRelativeFileUrl(project: Project, relativePath: String): String? {
    val basePath = project.basePath ?: return null
    return "file://$basePath/$relativePath"
}

internal fun encodeVfsExistsResult(result: VfsExistsResult): String =
    toolArgsJson.encodeToString(result)

private fun errorResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = message)), isError = true)
