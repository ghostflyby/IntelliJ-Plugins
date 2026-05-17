/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.sdk.tools.WorkspaceMcpProjectToolArguments
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope

/**
 * Lightweight project resolution helpers for SDK tool handlers.
 * Replaces the removed WorkspaceMcpRequestRunner class.
 */

/**
 * Resolve a project from typed [WorkspaceMcpProjectToolArguments] and run [block] with it.
 * Sets up project lifecycle cancellation scope.
 */
internal suspend fun <T> withResolvedProject(
    projectArgs: WorkspaceMcpProjectToolArguments,
    vfsUrl: String? = null,
    relativePath: String? = null,
    sessionId: String? = null,
    block: suspend (Project) -> T,
): T {
    val resolver = service<WorkspaceProjectResolver>()
    val roots = if (sessionId != null) {
        service<WorkspaceMcpSdkServerService>().getSessionRoots(sessionId).ifEmpty { null }
    } else null
    val resolution = resolver.resolve(
        projectKey = projectArgs.projectKey,
        projectPath = projectArgs.projectPath,
        rawVfsUrl = vfsUrl,
        relativePath = relativePath,
        rootsCandidates = roots,
    )
    val project = when (resolution) {
        is WorkspaceProjectResolution.Resolved -> resolution.project
        is WorkspaceProjectResolution.Unresolved -> error(resolution.message)
    }
    return coroutineScope {
        val projectJob = project.scope.coroutineContext[Job]
        projectJob?.invokeOnCompletion { cause ->
            if (cause != null) cancel(CancellationException("Project closed", cause))
        }
        block(project)
    }
}

/**
 * Lightweight version of [withResolvedProject] that catches resolution errors
 * and wraps them into error [CallToolResult].
 */
internal suspend fun callToolWithProject(
    projectArgs: WorkspaceMcpProjectToolArguments,
    vfsUrl: String? = null,
    relativePath: String? = null,
    sessionId: String? = null,
    block: suspend (Project) -> CallToolResult,
): CallToolResult {
    return try {
        withResolvedProject(
            projectArgs = projectArgs,
            vfsUrl = vfsUrl,
            relativePath = relativePath,
            sessionId = sessionId,
        ) { project -> block(project) }
    } catch (e: IllegalStateException) {
        CallToolResult(
            content = listOf(TextContent(text = e.message ?: "Unknown error")),
            isError = true,
        )
    } catch (e: Exception) {
        CallToolResult(
            content = listOf(
                TextContent(
                    text = "Tool call failed: ${e.message ?: e::class.simpleName}",
                ),
            ),
            isError = true,
        )
    }
}
