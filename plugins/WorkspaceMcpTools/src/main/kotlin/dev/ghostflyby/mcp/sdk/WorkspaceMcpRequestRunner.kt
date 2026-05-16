/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.resource.WorkspaceResourceException
import dev.ghostflyby.mcp.resource.WorkspaceResourceUri
import dev.ghostflyby.mcp.sdk.tools.WorkspaceMcpProjectToolArguments
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope


/**
 * Centralized request runner that handles project resolution,
 * and tool/resource error mapping.
 *
 * Tool and resource handlers should use the appropriate `run*` method
 * rather than open-coding project resolution.
 */
internal class WorkspaceMcpRequestRunner(
    val projectResolver: WorkspaceProjectResolver = WorkspaceProjectResolver(),
) {
    /**
     * Resolve a project and execute [block] under a scope that
     * is cancelled when the project lifecycle ends (e.g. on
     * project close or plugin unload for that project).
     *
     * Resolution hints ([rawVfsUrl], [relativePath]) are tried after explicit
     * [projectKey]/[projectPath] but before single-project fallback.
     *
     * @throws WorkspaceResourceException if the project cannot be resolved.
     */
    suspend fun <T> runWithProjectResolution(
        projectKey: String? = null,
        projectPath: String? = null,
        rawVfsUrl: String? = null,
        relativePath: String? = null,
        block: suspend (Project) -> T,
    ): T {
        val resolved = projectResolver.resolve(
            projectKey = projectKey,
            projectPath = projectPath,
            rawVfsUrl = rawVfsUrl,
            relativePath = relativePath,
        )
        val project = when (resolved) {
            is WorkspaceProjectResolution.Resolved -> resolved.project
            is WorkspaceProjectResolution.Unresolved -> throw WorkspaceResourceException(resolved.message)
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
     * Resolve a project from typed [WorkspaceMcpProjectToolArguments] and run [block] with it.
     * This is the preferred overload for SDK tools with type-safe argument DTOs.
     *
     * Project hints are read from the DTO's [WorkspaceMcpProjectToolArguments.projectKey]
     * and [WorkspaceMcpProjectToolArguments.projectPath].
     */
    suspend fun callToolWithProject(
        projectArgs: WorkspaceMcpProjectToolArguments,
        rawVfsUrl: String? = null,
        relativePath: String? = null,
        block: suspend (Project) -> CallToolResult,
    ): CallToolResult {
        return try {
            runWithProjectResolution(
                projectKey = projectArgs.projectKey,
                projectPath = projectArgs.projectPath,
                rawVfsUrl = rawVfsUrl,
                relativePath = relativePath,
            ) { project -> block(project) }
        } catch (e: WorkspaceResourceException) {
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

    /**
     * Run a resource read under a scope that is cancelled when
     * the project lifecycle ends.
     *
     * @throws WorkspaceResourceException if the project cannot be resolved.
     */
    suspend fun runResourceRead(
        sessionId: String? = null,
        decoded: WorkspaceResourceUri,
        instanceKey: String = workspaceInstanceKey(),
        block: suspend () -> ReadResourceResult,
    ): ReadResourceResult {
        return when (val resolved = projectResolver.resolve(projectKey = decoded.projectKey)) {
            is WorkspaceProjectResolution.Resolved -> {
                coroutineScope {
                    val projectJob = resolved.project.scope.coroutineContext[Job]
                    if (projectJob != null) {
                        projectJob.invokeOnCompletion { cause ->
                            if (cause != null) cancel(CancellationException("Project closed", cause))
                        }
                    }
                    block()
                }
            }

            is WorkspaceProjectResolution.Unresolved -> throw WorkspaceResourceException(resolved.message)
        }
    }
}
