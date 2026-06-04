/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.components.service
import dev.ghostflyby.mcp.filecontent.ContentReadException
import dev.ghostflyby.mcp.filecontent.FileContentKind
import dev.ghostflyby.mcp.filecontent.readGlobResult
import dev.ghostflyby.mcp.filecontent.resolveProjectFileAccess
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
@Resource("/projects/{projectKey}/glob")
private data class ProjectGlobResource(val projectKey: String)

@Serializable
@Resource("/{rootId}")
private data class RootGlobResource(
    val parent: ProjectGlobResource,
    val rootId: String,
)

@Serializable
@Resource("/{relativePath...}")
private data class RootGlobFileResource(
    val parent: RootGlobResource,
    val relativePath: String,
)

internal fun Route.globRoutes() {
    val resolver: WorkspaceProjectResolver = service()

    get<RootGlobResource> { resource ->
        respondGlob(call, resolver, resource.parent.projectKey, resource.rootId, "")
    }

    get<RootGlobFileResource> { resource ->
        respondGlob(
            call,
            resolver,
            resource.parent.parent.projectKey,
            resource.parent.rootId,
            resource.relativePath,
        )
    }
}

private suspend fun respondGlob(
    call: io.ktor.server.application.ApplicationCall,
    resolver: WorkspaceProjectResolver,
    projectKey: String,
    rootId: String,
    relativePath: String,
) {
    val patterns = call.request.queryParameters.getAll("glob").orEmpty()
    if (patterns.isEmpty()) {
        call.respondNegotiatedError(
            HttpStatusCode.BadRequest,
            mapOf("error" to "glob requires at least one pattern"),
            "glob requires at least one pattern",
        )
        return
    }
    when (val resolved = resolver.resolve(projectKey = projectKey)) {
        is WorkspaceProjectResolution.Resolved -> {
            val target = rootRouteTarget(resolved.project, rootId, relativePath)
            if (target == null) {
                call.respondNegotiatedError(HttpStatusCode.NotFound, mapOf("error" to "Root not found"), "Root not found")
                return
            }
            val access = resolveProjectFileAccess(
                resolved.project,
                target.root,
                target.relativePath,
            )
            val policy = access.policy
            if (access.file == null) {
                call.respondNegotiatedError(HttpStatusCode.NotFound, mapOf("error" to "File not found"), "File not found")
                return
            }
            if (!policy.canRead(FileContentKind.GLOB)) {
                call.respondNegotiatedError(HttpStatusCode.Forbidden, mapOf("error" to policy.reason), policy.reason)
                return
            }
            val json = try {
                readGlobResult(access.file, patterns, resolved.project).payload
            } catch (error: ContentReadException) {
                call.respondNegotiatedError(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to error.message.orEmpty()),
                    error.message.orEmpty(),
                )
                return
            }
            val paths = Json.decodeFromString(ListSerializer(String.serializer()), json)
            call.respondNegotiatedText(
                jsonText = json,
                textBody = renderGlobText(paths),
                textContentType = ContentType.Text.Plain,
            )
        }

        is WorkspaceProjectResolution.Unresolved -> {
            call.respondNegotiatedError(
                HttpStatusCode.NotFound,
                mapOf("error" to resolved.message, "projectKey" to projectKey),
                resolved.message,
            )
        }
    }
}
