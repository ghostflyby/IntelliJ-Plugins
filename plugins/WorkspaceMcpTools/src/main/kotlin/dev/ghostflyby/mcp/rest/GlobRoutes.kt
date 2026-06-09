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
import dev.ghostflyby.mcp.rest.markdown.TextBody
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Glob result: a bare JSON array of paths (via [GlobResultSerializer]) and a prefix block for text.
 */
@Serializable(with = GlobResultSerializer::class)
internal data class GlobResult(val paths: List<String>) : TextBody {
    override fun renderTextBody(): String = renderPrefixBlock(paths)
}

private object GlobResultSerializer : KSerializer<GlobResult> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: GlobResult) = delegate.serialize(encoder, value.paths)
    override fun deserialize(decoder: Decoder): GlobResult = GlobResult(delegate.deserialize(decoder))
}

// ── Route registration ────────────────────────────────

internal fun Route.globRoutes() {
    val resolver: WorkspaceProjectResolver = service()

    get<Api.Project.GlobEntry.Glob> { resource ->
        val patterns = resource.parent.glob
        if (patterns.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                RestError("glob requires a pattern query parameter"),
            )
            return@get
        }
        respondGlob(call, resolver, resource.parent, resource.relativePath.toRoutePath(), patterns)
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
                call.respond(HttpStatusCode.NotFound, RestError("Root not found"))
                return
            }
            val access = resolveProjectFileAccess(resolved.project, target.root, target.relativePath)
            val policy = access.policy
            if (access.file == null) {
                call.respond(HttpStatusCode.NotFound, RestError("File not found"))
                return
            }
            if (!policy.canRead(FileContentKind.GLOB)) {
                call.respond(HttpStatusCode.Forbidden, RestError(policy.reason))
                return
            }
            val paths = try {
                readGlobResult(access.file, patterns, resolved.project)
            } catch (error: ContentReadException) {
                call.respond(HttpStatusCode.BadRequest, RestError(error.message.orEmpty()))
                return
            }
            call.respond(GlobResult(paths))
        }

        is WorkspaceProjectResolution.Unresolved -> {
            call.respond(HttpStatusCode.NotFound, RestError(resolved.message, projectKey))
        }
    }
}
