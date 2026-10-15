/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import dev.ghostflyby.mcp.filecontent.exposedWorkspaceRoots
import dev.ghostflyby.mcp.sdk.openWorkspaceProjects
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class ProjectListEntry(
    val projectKey: String,
    val name: String,
    val basePath: String?,
)

internal fun Route.projectRoutes() {
    // Project list
    get<Api.Projects> {
        val projects = openWorkspaceProjects().map { project ->
            ProjectListEntry(
                projectKey = workspaceProjectKey(project),
                name = project.name,
                basePath = project.basePath,
            )
        }
        call.respond(projects)
    }

    get<Api.Project.Roots> {
        val projectKey = it.parent.projectKey
        val project = call.resolveWorkspaceProjectOrNull(projectKey = projectKey)
            ?: return@get
        val roots = exposedWorkspaceRoots(project).map { root -> root.toDto() }
        call.respond(roots)
    }

    // Project detail — typed @Resource handler
    get<Api.Project> { project ->
        val resolvedProject = call.resolveWorkspaceProjectOrNull(projectKey = project.projectKey)
            ?: return@get
        val entry = ProjectListEntry(
            projectKey = workspaceProjectKey(resolvedProject),
            name = resolvedProject.name,
            basePath = resolvedProject.basePath,
        )
        call.respond(entry)
    }
}
