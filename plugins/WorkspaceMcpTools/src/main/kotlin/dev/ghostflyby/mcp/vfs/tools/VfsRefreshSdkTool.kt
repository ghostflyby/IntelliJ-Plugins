/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.vfs.tools

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mcp.sdk.tools.SdkToolDescriptor
import dev.ghostflyby.mcp.sdk.tools.SdkToolHandlerContext
import dev.ghostflyby.mcp.sdk.tools.toolSchema
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred

/**
 * vfs_refresh as an SDK tool — proof-of-concept migration from the old
 * [dev.ghostflyby.mcp.vfs.VfsMcpTools] annotation-based toolset.
 *
 * Runs through [SdkToolHandlerContext.runner] so context installation and
 * project resolution happen before the refresh. Resolution uses the full
 * hint surface:
 * - URLs containing `://` are passed as [rawVfsUrl][ProjectResolver.rawVfsUrl],
 *   enabling VFS-URL-based project inference in multi-project workspaces.
 * - Strings without `://` are passed as [relativePath] and resolved against the
 *   project's base path after resolution.
 * - Explicit `projectKey` / `projectPath` always take precedence.
 *
 * The old annotation-based VfsMcpTools.vfs_refresh remains in place.
 */
internal fun vfsRefreshSdkTool(): SdkToolDescriptor {
    return SdkToolDescriptor(
        name = "vfs_refresh",
        description = "Refresh a VFS file or directory. Supports project-scoped URL resolution via optional projectKey/projectPath.",
        inputSchema = toolSchema(
            properties = mapOf(
                "url" to "VFS URL or project-relative path to refresh.",
                "projectKey" to "Stable project key for project-scoped resolution (optional).",
                "projectPath" to "Absolute project base path for project-scoped resolution (optional).",
                "async" to "Run refresh asynchronously (true/false string, default 'false').",
                "recursive" to "Refresh children recursively (true/false string, default 'false'). Effective for directories.",
            ),
            required = listOf("url"),
        ),
        handler = { args -> vfsRefreshHandler(this, args) },
    )
}

private suspend fun vfsRefreshHandler(ctx: SdkToolHandlerContext, args: Map<String, String>): CallToolResult {
    val url = args["url"] ?: return errorResult("Missing required parameter 'url'.")
    val async = args["async"]?.toBooleanStrictOrNull() ?: false
    val recursive = args["recursive"]?.toBooleanStrictOrNull() ?: false

    val isRawVfs = url.contains("://")

    return ctx.runner.callToolWithProject(
        arguments = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = if (isRawVfs) url else null,
        relativePath = if (!isRawVfs) url else null,
    ) { project ->
        val resolvedUrl = if (isRawVfs) {
            url
        } else {
            val basePath = project.basePath
            if (basePath == null) {
                return@callToolWithProject errorResult("Project has no base path, cannot resolve relative path: $url")
            }
            "file://$basePath/$url"
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

private fun errorResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = message)), isError = true)
