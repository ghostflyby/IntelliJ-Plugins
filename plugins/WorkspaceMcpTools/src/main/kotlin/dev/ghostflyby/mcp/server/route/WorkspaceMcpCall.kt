/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.route

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.server.*
import io.ktor.util.Attributes
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

internal class WorkspaceMcpCall<out R : Request>(
    val connection: ClientConnection,
    val request: R,
    val attributes: Attributes = Attributes(),
) {
    val sessionId: String get() = connection.sessionId

    suspend fun roots(): List<String> {
        return connection.listRoots().roots.map { it.uri.removePrefix("file://") }
    }
}

// -- project resolution --

internal suspend fun WorkspaceMcpCall<*>.project(): Project {
    val resolver = attributes[Keys.ProjectProvider]
        ?: error("WorkspaceProjectProvider not available")
    val routeParams = attributes[Keys.RouteParameters]
    val project = when (val r = resolver.resolve(projectKey = routeParams?.get("projectKey"))) {
        is WorkspaceProjectResolution.Resolved -> r.project
        is WorkspaceProjectResolution.Unresolved -> error(r.message)
    }
    coroutineScope {
        val projectJob = project.scope.coroutineContext[Job]
        projectJob?.invokeOnCompletion { cause ->
            if (cause != null) cancel(CancellationException("Project closed", cause))
        }
    }
    return project
}

// -- visible projects --

internal val WorkspaceMcpCall<*>.instanceKey: String
    get() = attributes[Keys.InstanceKey] ?: error("InstanceKey not available")

internal suspend fun WorkspaceMcpCall<*>.visibleProjects(
    resolver: WorkspaceProjectProvider,
): List<WorkspaceMcpListProject> {
    return visibleProjectInstances(resolver).map { project ->
        WorkspaceMcpListProject(
            projectKey = workspaceProjectKey(project),
            name = project.name,
            basePath = project.basePath,
        )
    }
}

// -- impl --

private suspend fun WorkspaceMcpCall<*>.visibleProjectInstances(resolver: WorkspaceProjectProvider): List<Project> {
    val projects = resolver.openProjects()
    val roots = roots()
    if (roots.isEmpty()) return projects
    val normalizedRoots = roots.map { normalizePath(it) }
    return projects.filter { project ->
        val basePath = project.basePath?.let { normalizePath(it) } ?: return@filter false
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
