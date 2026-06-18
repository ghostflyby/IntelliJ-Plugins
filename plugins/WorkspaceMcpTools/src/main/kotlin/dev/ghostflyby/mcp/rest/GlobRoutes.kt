/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mcp.filecontent.ContentReadException
import dev.ghostflyby.mcp.filecontent.FileContentKind
import dev.ghostflyby.mcp.filecontent.readGlobResult
import dev.ghostflyby.mcp.filecontent.resolveProjectFileAccess
import dev.ghostflyby.mcp.rest.markdown.TextBody
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

internal object GlobResultSerializer : KSerializer<GlobResult> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: GlobResult) = delegate.serialize(encoder, value.paths)
    override fun deserialize(decoder: Decoder): GlobResult = GlobResult(delegate.deserialize(decoder))
}

// ── Route registration ────────────────────────────────

internal fun Route.globRoutes() {
    get<Api.GlobEntry.Glob> { resource ->
        val patterns = resource.parent.glob
        if (patterns.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                RestError("glob requires a pattern query parameter"),
            )
            return@get
        }
        val target = call.resolveFileRouteTarget(resource.path.toRoutePath())
            ?: return@get
        respondGlob(call, target, resource.parent.limit, patterns)
    }
}

internal suspend fun respondGlob(
    call: ApplicationCall,
    target: RestFileRouteTarget,
    limit: Int,
    patterns: List<String>,
) {
    when (target) {
        is RestFileRouteTarget.ProjectFile -> respondProjectGlob(call, target.target, limit, patterns)
        is RestFileRouteTarget.VirtualFileReadOnly -> respondGlobFile(
            call,
            target.file,
            project = target.project,
            limit,
            patterns,
        )
    }
}

private suspend fun respondProjectGlob(
    call: ApplicationCall,
    target: RestSessionRouteTarget,
    limit: Int,
    patterns: List<String>,
) {
    val access = resolveProjectFileAccess(target.project, target.root, target.relativePath)
    val policy = access.policy
    if (access.file == null) {
        call.respond(HttpStatusCode.NotFound, RestError("File not found"))
        return
    }
    if (!policy.canRead(FileContentKind.GLOB)) {
        call.respond(HttpStatusCode.Forbidden, RestError(policy.reason))
        return
    }
    respondGlobFile(call, access.file, target.project, limit, patterns)
}

private suspend fun respondGlobFile(
    call: ApplicationCall,
    file: VirtualFile,
    project: Project?,
    limit: Int,
    patterns: List<String>,
) {
    val paths = try {
        readGlobResult(file, patterns, project)
    } catch (error: ContentReadException) {
        call.respond(HttpStatusCode.BadRequest, RestError(error.message.orEmpty()))
        return
    }
    val limited = if (limit > 0) paths.take(limit) else paths
    call.respond(GlobResult(limited))
}
