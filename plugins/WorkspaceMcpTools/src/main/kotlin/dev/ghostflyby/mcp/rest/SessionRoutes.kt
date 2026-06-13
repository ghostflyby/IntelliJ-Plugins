/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.components.service
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.delete
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

private const val SessionHeader: String = "X-Session-Id"

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
    val projectKey: String,
    val name: String,
    val basePath: String?,
)

@Serializable
private data class SessionRootResponse(
    val rootId: String,
    val path: String,
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

    get<Api.Session.FilesEntry.File> { resource ->
        val target = call.resolveSessionTarget(sessions, resource.relativePath.toRoutePath())
            ?: return@get
        val entry = Api.Project.FilesEntry(
            parent = Api.Project(target.record.projectKey),
            rootId = target.record.rootId,
            meta = resource.parent.meta,
            content = resource.parent.content,
            exists = resource.parent.exists,
            structure = resource.parent.structure,
            force = resource.parent.force,
            startLine = resource.parent.startLine,
            endLine = resource.parent.endLine,
            maxLines = resource.parent.maxLines,
            aroundLine = resource.parent.aroundLine,
            radius = resource.parent.radius,
        )
        respondProjectRootFile(
            call = call,
            resolver = resolver,
            root = entry,
            relativePath = target.relativePathUnderRoot,
            meta = entry.meta,
            content = entry.content,
            exists = entry.exists,
            structure = entry.structure,
            rangeQuery = entry,
        )
    }

    get<Api.Session.GlobEntry.Glob> { resource ->
        val patterns = resource.parent.glob
        if (patterns.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, RestError("glob requires a pattern query parameter"))
            return@get
        }
        val target = call.resolveSessionTarget(sessions, resource.relativePath.toRoutePath())
            ?: return@get
        val entry = Api.Project.GlobEntry(
            parent = Api.Project(target.record.projectKey),
            rootId = target.record.rootId,
            limit = resource.parent.limit,
            glob = patterns,
        )
        respondGlob(call, resolver, entry, target.relativePathUnderRoot, patterns)
    }
}

private suspend fun ApplicationCall.resolveSessionTarget(
    sessions: RestSessionService,
    relativePath: String,
): RestSessionResolvedTarget? {
    return when (val result = sessions.resolveTarget(request.headers[SessionHeader], relativePath)) {
        is RestSessionTargetResult.Resolved -> result.target
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
            projectKey = projectKey,
            name = projectName,
            basePath = projectBasePath,
        ),
        exposedRoot = SessionRootResponse(
            rootId = rootId,
            path = rootPath.toString(),
        ),
        expiresAt = expiresAt.toString(),
    )
}
