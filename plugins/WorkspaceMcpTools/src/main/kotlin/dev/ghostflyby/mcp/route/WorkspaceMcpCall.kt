/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.sdk.*
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
    val parameters: AncestorContext,
    private val projectResolver: WorkspaceProjectResolver,
) {
    val sessionId: String get() = connection.sessionId
    val instanceKey: String get() = workspaceInstanceKey()

    suspend fun roots(): List<String> {
        return connection.listRoots().roots.map { it.uri.removePrefix("file://") }
    }

    suspend fun project(): Project {
        val project = when (val r = projectResolver.resolve(projectKey = parameters["projectKey"])) {
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

    suspend fun visibleProjects(): List<WorkspaceMcpListProject> {
        return visibleProjectInstances(projectResolver).map { project ->
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
