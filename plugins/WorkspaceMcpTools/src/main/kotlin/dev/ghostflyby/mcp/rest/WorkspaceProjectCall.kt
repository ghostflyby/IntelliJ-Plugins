/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.sdk.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

internal data class RestSessionProject(
    val record: RestSessionRecord,
    val project: Project,
)

internal suspend fun ApplicationCall.resolveWorkspaceProjectOrNull(
    projectKey: String? = null,
    projectPath: String? = null,
    rawVfsUrl: String? = null,
    relativePath: String? = null,
    rootsCandidates: List<String> = emptyList(),
): Project? {
    val hints = WorkspaceProjectHints(
        projectKey = projectKey ?: request.queryParameters["projectKey"],
        projectPath = projectPath ?: request.queryParameters["projectPath"],
        rawVfsUrl = rawVfsUrl ?: request.queryParameters["rawVfsUrl"],
        relativePath = relativePath,
        rootsCandidates = rootsCandidates,
    )
    val project = resolveWorkspaceProject(hints)
    if (project != null) {
        bindCallerToProject(project)
        return project
    }
    respond(
        HttpStatusCode.NotFound,
        RestError(
            error = unresolvedProjectMessage(),
            projectKey = hints.projectKey,
        ),
    )
    return null
}

internal suspend fun ApplicationCall.resolveWorkspaceSessionProjectOrNull(): RestSessionProject? {
    val sessions = service<RestSessionService>()
    val record = when (val result = sessions.resolveRecord(request.headers[RestSessionHeader])) {
        is RestSessionRecordResult.Resolved -> result.record
        is RestSessionRecordResult.NotFound -> {
            respond(HttpStatusCode.NotFound, RestError(result.message))
            return null
        }
    }
    val project = resolveWorkspaceProjectOrNull(projectKey = record.projectKey)
        ?: return null
    return RestSessionProject(record, project)
}

internal suspend fun ApplicationCall.resolveWorkspaceSessionTargetOrNull(
    relativePath: String,
): RestSessionRouteTarget? {
    val sessions = service<RestSessionService>()
    return when (val result = sessions.resolveTarget(request.headers[RestSessionHeader], relativePath)) {
        is RestSessionTargetResult.Resolved -> {
            val project = resolveWorkspaceProjectOrNull(projectKey = result.target.record.projectKey)
                ?: return null
            val rootTarget = rootRouteTarget(
                project,
                result.target.record.rootId,
                result.target.relativePathUnderRoot,
            )
            if (rootTarget == null) {
                respond(HttpStatusCode.NotFound, RestError("Root not found"))
                null
            } else {
                RestSessionRouteTarget(
                    project = project,
                    root = rootTarget.root,
                    relativePath = rootTarget.relativePath,
                    record = result.target.record,
                )
            }
        }

        is RestSessionTargetResult.NotFound -> {
            respond(HttpStatusCode.NotFound, RestError(result.message))
            null
        }

        is RestSessionTargetResult.Forbidden -> {
            respond(HttpStatusCode.Forbidden, RestError(result.message))
            null
        }
    }
}

private suspend fun bindCallerToProject(project: Project) {
    val callerJob = currentCoroutineContext().job
    val projectJob = project.scope.coroutineContext.job
    val projectCloseHandle = projectJob.invokeOnCompletion { cause ->
        callerJob.cancel(CancellationException("Project closed: ${project.name}", cause))
    }
    callerJob.invokeOnCompletion {
        projectCloseHandle.dispose()
    }
}

private fun unresolvedProjectMessage(): String {
    val projects = openWorkspaceProjects()
    return when (projects.size) {
        0 -> "No open IntelliJ projects are available."
        1 -> "Project not found."
        else -> {
            val candidates = projects.joinToString("; ") { project ->
                "'${workspaceProjectKey(project)}' (${project.name}, ${project.basePath ?: "?"})"
            }
            "Multiple IntelliJ projects are open. Provide an explicit project key or project path. Candidates: $candidates"
        }
    }
}
