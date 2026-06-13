/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mcp.filecontent.ExposedRoot
import dev.ghostflyby.mcp.filecontent.findContainingExposedRoot
import dev.ghostflyby.mcp.filecontent.resolveFileByRawUrlOrNull
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

internal sealed class RestFileRouteTarget {
    data class ProjectFile(val target: RestSessionRouteTarget) : RestFileRouteTarget()
    data class VirtualFileReadOnly(val file: VirtualFile) : RestFileRouteTarget()
}

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

internal suspend fun ApplicationCall.resolveFileRouteTarget(
    sessions: RestSessionService,
    resolver: WorkspaceProjectResolver,
    path: String,
): RestFileRouteTarget? {
    if (!path.isFullVfsUrl()) {
        return resolveSessionRouteTarget(sessions, resolver, path)?.let(RestFileRouteTarget::ProjectFile)
    }
    val record = when (val result = sessions.resolveRecord(request.headers[RestSessionHeader])) {
        is RestSessionRecordResult.Resolved -> result.record
        is RestSessionRecordResult.NotFound -> {
            respond(HttpStatusCode.NotFound, RestError(result.message))
            return null
        }
    }
    val file = resolveFileByRawUrlOrNull(path)
    if (file == null) {
        respond(HttpStatusCode.NotFound, RestError("File not found"))
        return null
    }
    val sessionProject = when (val resolved = resolver.resolve(projectKey = record.projectKey)) {
        is WorkspaceProjectResolution.Resolved -> resolved.project
        is WorkspaceProjectResolution.Unresolved -> {
            respond(HttpStatusCode.NotFound, RestError(resolved.message))
            return null
        }
    }
    return sessionProject.sessionProjectTargetFor(file, record)
        ?.let(RestFileRouteTarget::ProjectFile)
        ?: RestFileRouteTarget.VirtualFileReadOnly(file)
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

private suspend fun Project.sessionProjectTargetFor(
    file: VirtualFile,
    record: RestSessionRecord,
): RestSessionRouteTarget? {
    val root = findContainingExposedRoot(this, file) ?: return null
    val relativePath = readAction {
        VfsUtilCore.getRelativePath(file, root.base, '/')
    } ?: return null
    return RestSessionRouteTarget(
        project = this,
        root = root,
        relativePath = relativePath,
        record = record,
    )
}

private fun String.isFullVfsUrl(): Boolean = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(this)

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
