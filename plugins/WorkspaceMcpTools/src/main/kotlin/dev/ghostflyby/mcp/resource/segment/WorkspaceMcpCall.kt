/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.resource.segment

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.sdk.WorkspaceMcpSessionState
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import dev.ghostflyby.mcp.sdk.workspaceInstanceKey
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.Request
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

internal class WorkspaceMcpCall<out R : Request>(
    val connection: ClientConnection?,
    val request: R,
    val ancestors: AncestorContext,
    private val sessionState: WorkspaceMcpSessionState,
    private val sessionIdOverride: String? = null,
    private val projectResolver: WorkspaceProjectResolver? = null,
) {
    val sessionId: String get() = sessionIdOverride ?: connection?.sessionId.orEmpty()
    val instanceKey: String get() = workspaceInstanceKey()

    suspend fun roots(): List<String> {
        return sessionState.getRoots(sessionId)
    }

    suspend fun visibleProjects(): List<WorkspaceMcpListProject> {
        val resolver = projectResolver ?: return emptyList()
        return visibleProjectInstances(resolver).map { project ->
            WorkspaceMcpListProject(
                projectKey = workspaceProjectKey(project),
                name = project.name,
                basePath = project.basePath,
            )
        }
    }

    private suspend fun visibleProjectInstances(resolver: WorkspaceProjectResolver): List<Project> {
        val projects = resolver.openProjects()
        val roots = roots()
        if (roots.isEmpty()) return projects
        val normalizedRoots = roots.map(::normalizePath)
        return projects.filter { project ->
            val basePath = project.basePath?.let(::normalizePath) ?: return@filter false
            normalizedRoots.any { root ->
                basePath == root || basePath.startsWith("$root/") || root.startsWith("$basePath/")
            }
        }
    }

    private fun normalizePath(path: String): String {
        return runCatching {
            val nioPath = Path.of(path)
            if (nioPath.exists()) {
                nioPath.toRealPath().absolutePathString()
            } else {
                nioPath.toAbsolutePath().normalize().absolutePathString()
            }
        }.getOrElse {
            path.trim().trimEnd('/')
        }
    }
}
