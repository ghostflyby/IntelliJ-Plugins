/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.filecontent.ExposedRoot
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

internal const val RestSessionHeader: String = "X-Ghostflyby-Workspace-Session-Id"

@Serializable
private data class SessionCreateRequest(
    val pathPrefix: String,
)

@Serializable
private data class SessionCreateResponse(
    val sessionId: String,
    val pathPrefix: String,
    val project: SessionProjectResponse,
    val exposedRoot: SessionRootResponse,
    val expiresAt: String,
)

@Serializable
private data class SessionProjectResponse(
    val name: String,
    val basePath: String?,
)

@Serializable
private data class SessionRootResponse(
    val path: String,
)

internal data class RestSessionRouteTarget(
    val project: Project,
    val root: ExposedRoot,
    val relativePath: String,
    val record: RestSessionRecord,
)

internal fun Route.sessionRoutes() {
    val resolver: WorkspaceProjectResolver = service()
    val sessions: RestSessionService = service()

    post<Api.Sessions> {
        val request = call.receive<SessionCreateRequest>()
        when (val result = sessions.create(request.pathPrefix, resolver)) {
            is RestSessionCreateResult.Created -> call.respond(HttpStatusCode.Created, result.record.toResponse())
            is RestSessionCreateResult.Failed -> call.respond(HttpStatusCode.NotFound, RestError(result.message))
            is RestSessionCreateResult.Ambiguous -> call.respond(HttpStatusCode.Conflict, RestError(result.message))
        }
    }

    delete<Api.Sessions.Id> { resource ->
        sessions.delete(resource.sessionId)
        call.respondText("true", ContentType.Text.Plain)
    }
}

internal suspend fun ApplicationCall.resolveSessionRouteTarget(
    sessions: RestSessionService,
    resolver: WorkspaceProjectResolver,
    relativePath: String,
): RestSessionRouteTarget? {
    return when (val result = sessions.resolveTarget(request.headers[RestSessionHeader], relativePath)) {
        is RestSessionTargetResult.Resolved -> {
            when (val resolved = resolver.resolve(projectKey = result.target.record.projectKey)) {
                is WorkspaceProjectResolution.Resolved -> {
                    val rootTarget = rootRouteTarget(
                        resolved.project,
                        result.target.record.rootId,
                        result.target.relativePathUnderRoot,
                    )
                    if (rootTarget == null) {
                        respond(HttpStatusCode.NotFound, RestError("Root not found"))
                        null
                    } else {
                        RestSessionRouteTarget(
                            project = resolved.project,
                            root = rootTarget.root,
                            relativePath = rootTarget.relativePath,
                            record = result.target.record,
                        )
                    }
                }

                is WorkspaceProjectResolution.Unresolved -> {
                    respond(HttpStatusCode.NotFound, RestError(resolved.message))
                    null
                }
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

private fun RestSessionRecord.toResponse(): SessionCreateResponse {
    return SessionCreateResponse(
        sessionId = sessionId,
        pathPrefix = pathPrefix.toString(),
        project = SessionProjectResponse(
            name = projectName,
            basePath = projectBasePath,
        ),
        exposedRoot = SessionRootResponse(
            path = rootPath.toString(),
        ),
        expiresAt = expiresAt.toString(),
    )
}
