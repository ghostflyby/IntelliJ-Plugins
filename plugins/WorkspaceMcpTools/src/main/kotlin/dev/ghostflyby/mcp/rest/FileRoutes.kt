/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mcp.filecontent.*
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import dev.ghostflyby.mcp.server.route.resources.FileContentQuery
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * Compound file response. Query parameters (meta, content, exists, structure, glob)
 * mirror the [FileContentQuery] interface used by the @Resource classes in modules/.
 */
@Serializable
private data class FileContentResponse(
    val content: String? = null,
    val contentFormat: String? = null,
    val meta: FileMeta? = null,
    val exists: Boolean? = null,
    val structure: FileStructure? = null,
)

/** Manual DTO bridging URL query params to [FileContentQuery]. */
private data class SimpleFileQuery(
    override val meta: String?,
    override val content: String?,
    override val exists: Boolean,
    override val structure: Boolean,
    override val glob: String?,
) : FileContentQuery

private fun ApplicationCall.fileQuery(): SimpleFileQuery {
    val params = request.queryParameters
    val hasContent = params["content"] != null
    val hasExists = params["exists"] != null
    val hasStructure = params["structure"] != null
    return SimpleFileQuery(
        meta = params["meta"],
        content = if (hasContent) "" else null,
        exists = hasExists,
        structure = hasStructure,
        glob = params["glob"],
    )
}

// -- Route registrations --

internal fun Route.fileRoutes() {
    val resolver: WorkspaceProjectResolver = service()

    get("/vfs/{rawVfsUrl...}") {
        val rawVfsUrl = call.parameters.getAll("rawVfsUrl")?.joinToString("/") ?: return@get
        val q = call.fileQuery()
        val file = resolveFileByRawUrlOrNull(rawVfsUrl)
        val project = if (file != null && (q.structure || q.glob != null))
            projectForRawVfsUrl(rawVfsUrl, resolver) else null
        respondFileContent(call, file, q, project)
    }

    get("/projects/{projectKey}/roots/{rootId}") {
        respondProjectRootFile(call, resolver)
    }

    get("/projects/{projectKey}/roots/{rootId}/{relativePath...}") {
        respondProjectRootFile(call, resolver)
    }
}

private suspend fun respondProjectRootFile(call: ApplicationCall, resolver: WorkspaceProjectResolver) {
    val projectKey = call.parameters["projectKey"] ?: return
    val q = call.fileQuery()
    when (val resolved = resolver.resolve(projectKey = projectKey)) {
        is WorkspaceProjectResolution.Resolved -> {
            val target = call.rootRouteTargetOrNotFound(resolved.project) ?: return
            val access = resolveProjectFileAccess(resolved.project, target.root, target.relativePath)
            respondFileContent(call, access.file, q, resolved.project, access.policy)
        }

        is WorkspaceProjectResolution.Unresolved -> {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf(
                    "error" to resolved.message, "projectKey" to projectKey,
                ),
            )
        }
    }
}

private suspend fun projectForRawVfsUrl(
    rawVfsUrl: String, resolver: WorkspaceProjectResolver,
): Project? = when (val r = resolver.resolve(rawVfsUrl = rawVfsUrl)) {
    is WorkspaceProjectResolution.Resolved -> r.project
    is WorkspaceProjectResolution.Unresolved -> null
}

// -- Response dispatch --

private suspend fun respondFileContent(
    call: ApplicationCall,
    file: VirtualFile?,
    query: FileContentQuery,
    project: Project?,
    policy: FileAccessPolicy? = null,
) {
    val hasGlob = query.glob != null
    val wantsContent = !hasGlob && (query.content != null || (query.meta == null && !query.exists && !query.structure))
    val wantsMeta = query.meta != null
    val wantsExists = query.exists
    val wantsStructure = query.structure
    val needsFile = wantsContent || wantsMeta || wantsStructure || hasGlob

    // exists-only: no file required
    if (!wantsContent && !wantsMeta && wantsExists && !wantsStructure && !hasGlob) {
        call.respondText("${file != null}", ContentType.Text.Plain)
        return
    }
    if (needsFile && file == null) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
        return
    }
    val effectivePolicy = policy ?: file?.let { f ->
        project?.let { p -> classifyExistingProjectFile(p, f) } ?: fileMetaPolicyFallback(f)
    } ?: FileAccessPolicy(
        classification = FileContentClassification.MISSING,
        readableKinds = emptySet(),
        writableKinds = emptySet(),
        requiresForceForWrite = false,
        reason = "File not found",
    )
    if (needsFile && effectivePolicy.classification in setOf(
            FileContentClassification.EXCLUDED,
            FileContentClassification.OUTSIDE_PROJECT,
        )
    ) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
        return
    }
    if (hasGlob && !effectivePolicy.canRead(FileContentKind.GLOB)) {
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to effectivePolicy.reason))
        return
    }
    if (wantsStructure && !effectivePolicy.canRead(FileContentKind.STRUCTURE)) {
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to effectivePolicy.reason))
        return
    }
    if (wantsContent && !effectivePolicy.canRead(FileContentKind.CONTENT) && !effectivePolicy.canRead(FileContentKind.BYTES)
    ) {
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to effectivePolicy.reason))
        return
    }

    // Single-responsibility paths
    if (hasGlob && !wantsMeta && !wantsExists && !wantsStructure)
        return globOnly(call, file!!, query.glob!!, project)
    if (wantsStructure && !wantsMeta && !wantsContent && !wantsExists)
        return structureOnly(call, file!!, project)
    if (wantsMeta && !wantsContent && !wantsExists)
        return metaOnly(call, file!!, effectivePolicy)
    if (!wantsMeta && !wantsExists && !wantsStructure)
        return contentOnly(call, file!!)

    // Compound: at least two of meta/content/exists/structure/glob
    compoundResult(call, file!!, wantsMeta, wantsContent, wantsExists, wantsStructure, project, effectivePolicy)
}

// -- Single response modes --

private suspend fun contentOnly(call: ApplicationCall, file: VirtualFile) {
    val r = readContentResult(file)
    if (r.isBinary) {
        call.respondBytes(Base64.decode(r.payload), ContentType.parse(r.mimeType))
    } else {
        call.respondText(r.payload, ContentType.parse(r.mimeType))
    }
}

private suspend fun metaOnly(call: ApplicationCall, file: VirtualFile, policy: FileAccessPolicy) {
    call.respondText(readMetaResult(file, "", policy).payload, ContentType.Application.Json)
}

private suspend fun structureOnly(call: ApplicationCall, file: VirtualFile, project: Project?) {
    val json = if (project != null) readStructureResult(project, file) else """{"elements":[]}"""
    call.respondText(json, ContentType.Application.Json)
}

private suspend fun globOnly(call: ApplicationCall, file: VirtualFile, pattern: String, project: Project?) {
    call.respondText(readGlobResult(file, pattern, project).payload, ContentType.Application.Json)
}

// -- Compound response --

private suspend fun compoundResult(
    call: ApplicationCall, file: VirtualFile,
    wantsMeta: Boolean, wantsContent: Boolean, wantsExists: Boolean, wantsStructure: Boolean,
    project: Project?,
    policy: FileAccessPolicy?,
) {
    val effectivePolicy = policy ?: fileMetaPolicyFallback(file)
    val meta = if (wantsMeta) decodeFromJson<FileMeta>(readMetaResult(file, "", effectivePolicy).payload) else null
    val content = if (wantsContent) readContentResult(file) else null
    val structure = if (wantsStructure && project != null) decodeFromJson(readStructureResult(project, file)) else null
    call.respond(
        FileContentResponse(
            content = content?.payload,
            contentFormat = if (content?.isBinary == true) "base64" else null,
            meta = meta,
            exists = if (wantsExists) true else null,
            structure = structure,
        ),
    )
}

private inline fun <reified T> decodeFromJson(json: String): T =
    kotlinx.serialization.json.Json.decodeFromString(json)
