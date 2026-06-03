/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.components.service
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import dev.ghostflyby.mcp.server.route.resources.ProjectResource
import dev.ghostflyby.mcp.filecontent.exposedWorkspaceRoots
import io.ktor.http.*
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

@Serializable
private data class ProjectErrorResponse(
    val error: String,
    val projectKey: String,
)

internal fun Route.projectRoutes() {
    val resolver: WorkspaceProjectResolver = service<WorkspaceProjectResolver>()

    // Project list
    get("/projects") {
        val projects = resolver.openProjects().map { project ->
            ProjectListEntry(
                projectKey = workspaceProjectKey(project),
                name = project.name,
                basePath = project.basePath,
            )
        }
        call.respond(projects)
    }

    get("/projects/{projectKey}/file-roots") {
        val projectKey = call.parameters["projectKey"] ?: return@get
        when (val r = resolver.resolve(projectKey = projectKey)) {
            is WorkspaceProjectResolution.Resolved -> call.respond(exposedWorkspaceRoots(r.project).map { it.toDto() })
            is WorkspaceProjectResolution.Unresolved -> call.respond(
                HttpStatusCode.NotFound,
                ProjectErrorResponse(error = r.message, projectKey = projectKey),
            )
        }
    }

    // Project detail — typed @Resource handler
    get<ProjectResource> { project ->
        when (val r = resolver.resolve(projectKey = project.projectKey)) {
            is WorkspaceProjectResolution.Resolved -> {
                call.respond(
                    ProjectListEntry(
                        projectKey = workspaceProjectKey(r.project),
                        name = r.project.name,
                        basePath = r.project.basePath,
                    ),
                )
            }

            is WorkspaceProjectResolution.Unresolved -> {
                call.respond(
                    HttpStatusCode.NotFound,
                    ProjectErrorResponse(error = r.message, projectKey = project.projectKey),
                )
            }
        }
    }
}
