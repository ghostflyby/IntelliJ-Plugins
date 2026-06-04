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
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// ── Route registration ────────────────────────────────

internal fun Route.globRoutes() {
    val resolver: WorkspaceProjectResolver = service()

    get<Api.Project.GlobEntry.Glob> { resource ->
        val q = FileQuery(call)
        val patterns = q.glob
        if (patterns.isEmpty()) {
            call.respondNegotiatedError(
                HttpStatusCode.BadRequest,
                mapOf("error" to "glob requires a pattern query parameter"),
                "glob requires a pattern query parameter",
            )
            return@get
        }
        respondGlob(call, resolver, resource.parent, resource.relativePath, patterns)
    }
}

private suspend fun respondGlob(
    call: ApplicationCall,
    resolver: WorkspaceProjectResolver,
    entry: Api.Project.GlobEntry,
    relativePath: String,
    patterns: List<String>,
) {
    val projectKey = entry.parent.projectKey
    val rootId = entry.rootId
    when (val resolved = resolver.resolve(projectKey = projectKey)) {
        is WorkspaceProjectResolution.Resolved -> {
            val target = rootRouteTarget(resolved.project, rootId, relativePath)
            if (target == null) {
                call.respondNegotiatedError(
                    HttpStatusCode.NotFound, mapOf("error" to "Root not found"), "Root not found",
                )
                return
            }
            val access = resolveProjectFileAccess(resolved.project, target.root, target.relativePath)
            val policy = access.policy
            if (access.file == null) {
                call.respondNegotiatedError(
                    HttpStatusCode.NotFound, mapOf("error" to "File not found"), "File not found",
                )
                return
            }
            if (!policy.canRead(FileContentKind.GLOB)) {
                call.respondNegotiatedError(
                    HttpStatusCode.Forbidden, mapOf("error" to policy.reason), policy.reason,
                )
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
                textBody = renderPrefixBlock(paths),
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
