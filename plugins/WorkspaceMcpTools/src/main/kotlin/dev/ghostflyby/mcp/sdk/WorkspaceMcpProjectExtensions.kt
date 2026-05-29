/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.server.WorkspaceProjectProvider
import dev.ghostflyby.mcp.server.WorkspaceProjectResolution
import dev.ghostflyby.mcp.server.workspaceProjectKey
import dev.ghostflyby.mcp.server.route.Keys
import dev.ghostflyby.mcp.server.route.WorkspaceMcpCall
import dev.ghostflyby.mcp.server.route.WorkspaceMcpListProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

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

