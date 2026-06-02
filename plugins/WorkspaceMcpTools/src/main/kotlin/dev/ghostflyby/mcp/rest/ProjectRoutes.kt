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
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get as routingGet
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
    routingGet("/projects") {
        val projects = resolver.openProjects().map { project ->
            ProjectListEntry(
                projectKey = workspaceProjectKey(project),
                name = project.name,
                basePath = project.basePath,
            )
        }
        call.respond(projects)
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
