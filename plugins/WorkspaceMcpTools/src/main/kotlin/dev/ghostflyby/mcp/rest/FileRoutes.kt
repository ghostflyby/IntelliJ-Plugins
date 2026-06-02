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
import dev.ghostflyby.mcp.server.route.resources.ProjectFileResource
import dev.ghostflyby.mcp.server.route.resources.VfsResource
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * Compound file response. Query parameters (meta, content, exists, structure, glob)
 * are declared in the @Resource classes [VfsResource] / [ProjectFileResource]
 * via the [FileContentQuery] interface.
 */
@Serializable
private data class FileContentResponse(
    val content: String? = null,
    val contentFormat: String? = null,
    val meta: FileMeta? = null,
    val exists: Boolean? = null,
    val structure: FileStructure? = null,
)

// -- Route registrations --

internal fun Route.fileRoutes() {
    val resolver: WorkspaceProjectResolver = service()

    get<VfsResource> { vfs ->
        val file = resolveFileByRawUrlOrNull(vfs.rawVfsUrl)
        val project = projectForVfs(vfs, file, resolver)
        respondFileContent(call, file, vfs, project)
    }

    get<ProjectFileResource> { file ->
        when (val resolved = resolver.resolve(projectKey = file.parent.projectKey)) {
            is WorkspaceProjectResolution.Resolved -> {
                val vf = resolveFileByRelativePathOrNull(resolved.project, file.relativePath)
                respondFileContent(call, vf, file, resolved.project)
            }

            is WorkspaceProjectResolution.Unresolved -> {
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "error" to resolved.message, "projectKey" to file.parent.projectKey,
                ))
            }
        }
    }
}

private suspend fun projectForVfs(
    vfs: VfsResource, file: VirtualFile?, resolver: WorkspaceProjectResolver,
): Project? {
    if (file == null) return null
    val needsProject = vfs.structure || vfs.glob != null
    if (!needsProject) return null
    return when (val r = resolver.resolve(rawVfsUrl = vfs.rawVfsUrl)) {
        is WorkspaceProjectResolution.Resolved -> r.project
        is WorkspaceProjectResolution.Unresolved -> null
    }
}

// -- Response dispatch --

private suspend fun respondFileContent(
    call: ApplicationCall, file: VirtualFile?, query: FileContentQuery, project: Project?,
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

    // Single-responsibility paths
    if (hasGlob && !wantsMeta && !wantsContent && !wantsExists && !wantsStructure)
        return globOnly(call, file!!, query.glob!!, project)
    if (wantsStructure && !wantsMeta && !wantsContent && !wantsExists)
        return structureOnly(call, file!!, project)
    if (wantsMeta && !wantsContent && !wantsExists)
        return metaOnly(call, file!!)
    if (!wantsMeta && !wantsExists && !wantsStructure)
        return contentOnly(call, file!!)

    // Compound: at least two of meta/content/exists/structure/glob
    compoundResult(call, file!!, wantsMeta, wantsContent, wantsExists, wantsStructure, project)
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

private suspend fun metaOnly(call: ApplicationCall, file: VirtualFile) {
    call.respondText(readMetaResult(file, "").payload, ContentType.Application.Json)
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
) {
    val meta = if (wantsMeta) decodeFromJson<FileMeta>(readMetaResult(file, "").payload) else null
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
