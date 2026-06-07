/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.components.service
import dev.ghostflyby.mcp.filecontent.exposedWorkspaceRoots
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.ktor.http.*
import io.ktor.server.resources.*
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
    get<Api.Projects> {
        val projects = resolver.openProjects().map { project ->
            ProjectListEntry(
                projectKey = workspaceProjectKey(project),
                name = project.name,
                basePath = project.basePath,
            )
        }
        call.respondNegotiated(negotiatedMarkdown(projects, renderProjectList(projects)))
    }

    get<Api.Project.Roots> {
        val projectKey = it.parent.projectKey
        when (val r = resolver.resolve(projectKey = projectKey)) {
            is WorkspaceProjectResolution.Resolved -> {
                val roots = exposedWorkspaceRoots(r.project).map { it.toDto() }
                call.respondNegotiated(negotiatedMarkdown(roots, renderRootList(roots)))
            }

            is WorkspaceProjectResolution.Unresolved -> call.respondNegotiated(
                negotiatedError(ProjectErrorResponse(error = r.message, projectKey = projectKey), r.message),
                HttpStatusCode.NotFound,
            )
        }
    }

    // Project detail — typed @Resource handler
    get<Api.Project> { project ->
        when (val r = resolver.resolve(projectKey = project.projectKey)) {
            is WorkspaceProjectResolution.Resolved -> {
                val entry = ProjectListEntry(
                    projectKey = workspaceProjectKey(r.project),
                    name = r.project.name,
                    basePath = r.project.basePath,
                )
                call.respondNegotiated(negotiatedMarkdown(entry, renderProject(entry)))
            }

            is WorkspaceProjectResolution.Unresolved -> {
                call.respondNegotiated(
                    negotiatedError(
                        ProjectErrorResponse(error = r.message, projectKey = project.projectKey),
                        r.message,
                    ),
                    HttpStatusCode.NotFound,
                )
            }
        }
    }
}

private fun renderProjectList(projects: List<ProjectListEntry>): String = buildString {
    appendLine("| projectKey | name | basePath |")
    appendLine("| --- | --- | --- |")
    projects.forEach { project ->
        appendLine("| ${project.projectKey} | ${project.name} | ${project.basePath.orEmpty()} |")
    }
}

private fun renderProject(project: ProjectListEntry): String = yamlFrontMatter(
    linkedMapOf(
        "projectKey" to project.projectKey,
        "name" to project.name,
        "basePath" to project.basePath,
    ),
)

private fun renderRootList(roots: List<dev.ghostflyby.mcp.filecontent.ExposedRootDto>): String = buildString {
    appendLine("| id | displayName | kind | readable | writable | url |")
    appendLine("| --- | --- | --- | --- | --- | --- |")
    roots.forEach { root ->
        appendLine("| ${root.id} | ${root.displayName} | ${root.kind} | ${root.readable} | ${root.writable} | ${root.url} |")
    }
}
