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
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

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
    get<Api.Vfs> { resource ->
        val rawVfsUrl = resource.rawVfsUrl.toRoutePath()
        val file = resolveFileByRawUrlOrNull(rawVfsUrl)
        val project = if (file != null && resource.structure)
            projectForRawVfsUrl(rawVfsUrl, resolver) else null
        respondFileContent(call, file, resource.meta, resource.content, resource.exists, resource.structure, project)
    }

    get<Api.Project.Root> { resource ->
        respondProjectRootFile(
            call,
            resolver,
            resource,
            meta = resource.meta,
            content = resource.content,
            exists = resource.exists,
            structure = resource.structure,
        )
    }

    get<Api.Project.Root.File> { resource ->
        respondProjectRootFile(
            call,
            resolver,
            resource.parent,
            resource.relativePath.toRoutePath(),
            resource.meta,
            resource.content,
            resource.exists,
            resource.structure,
        )
    }
}

private suspend fun respondProjectRootFile(
    call: ApplicationCall,
    resolver: WorkspaceProjectResolver,
    root: Api.Project.Root,
    relativePath: String = "",
    meta: Boolean = false,
    content: Boolean = false,
    exists: Boolean = false,
    structure: Boolean = false,
) {
    val projectKey = root.parent.projectKey
    when (val resolved = resolver.resolve(projectKey = projectKey)) {
        is WorkspaceProjectResolution.Resolved -> {
            val target = rootRouteTarget(resolved.project, root.rootId, relativePath)
            if (target == null) {
                call.respondNegotiated(
                    negotiatedError(mapOf("error" to "Root not found"), "Root not found"),
                    HttpStatusCode.NotFound,
                )
                return
            }
            val access = resolveProjectFileAccess(resolved.project, target.root, target.relativePath)
            respondFileContent(call, access.file, meta, content, exists, structure, resolved.project, access.policy)
        }

        is WorkspaceProjectResolution.Unresolved -> {
            call.respondNegotiated(
                negotiatedError(
                    mapOf("error" to resolved.message, "projectKey" to projectKey),
                    resolved.message,
                ),
                HttpStatusCode.NotFound,
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
    meta: Boolean,
    content: Boolean,
    exists: Boolean,
    structure: Boolean,
    project: Project?,
    policy: FileAccessPolicy? = null,
) {
    val wantsContent = content || (!meta && !exists && !structure)
    val wantsMeta = meta
    val wantsExists = exists
    val wantsStructure = structure
    val needsFile = wantsContent || wantsMeta || wantsStructure
    // exists-only: no file required
    if (!wantsContent && !wantsMeta && wantsExists && !wantsStructure) {
        call.respondText("${file != null}", ContentType.Text.Plain)
        return
    }
    if (needsFile && file == null) {
        call.respondNegotiated(
            negotiatedError(mapOf("error" to "File not found"), "File not found"),
            HttpStatusCode.NotFound,
        )
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
    if (effectivePolicy.classification in setOf(
            FileContentClassification.EXCLUDED,
            FileContentClassification.OUTSIDE_PROJECT,
        )
    ) {
        call.respondNegotiated(
            negotiatedError(mapOf("error" to "File not found"), "File not found"),
            HttpStatusCode.NotFound,
        )
        return
    }
    if (wantsStructure && !effectivePolicy.canRead(FileContentKind.STRUCTURE)) {
        call.respondNegotiated(
            negotiatedError(mapOf("error" to effectivePolicy.reason), effectivePolicy.reason),
            HttpStatusCode.Forbidden,
        )
        return
    }
    if (wantsContent && !effectivePolicy.canRead(FileContentKind.CONTENT) && !effectivePolicy.canRead(FileContentKind.BYTES)
    ) {
        call.respondNegotiated(
            negotiatedError(mapOf("error" to effectivePolicy.reason), effectivePolicy.reason),
            HttpStatusCode.Forbidden,
        )
        return
    }
    // Single-responsibility paths
    if (wantsStructure && !wantsMeta && !wantsContent && !wantsExists)
        return structureOnly(call, file!!, project)
    if (wantsMeta && !wantsContent && !wantsExists)
        return metaOnly(call, file!!, effectivePolicy)
    if (!wantsMeta && !wantsExists && !wantsStructure)
        return contentOnly(call, file!!)
    // Compound: at least two of meta/content/exists/structure
    compoundResult(call, file!!, wantsMeta, wantsContent, wantsExists, wantsStructure, project, effectivePolicy)
}

// -- Single response modes --
private suspend fun contentOnly(call: ApplicationCall, file: VirtualFile) {
    val r = readContentResult(file)
    if (r.isBinary) {
        call.respondBytes(Base64.decode(r.payload), ContentType.parse(r.mimeType))
    } else if (file.isDirectory) {
        call.respondNegotiated(negotiatedText(r.payload, renderDirectoryListingText(decodeFromJson(r.payload))))
    } else {
        call.respondText(r.payload, ContentType.parse(r.mimeType))
    }
}

private suspend fun metaOnly(call: ApplicationCall, file: VirtualFile, policy: FileAccessPolicy) {
    val json = readMetaResult(file, "", policy).payload
    val meta = decodeFromJson<FileMeta>(json)
    call.respondNegotiated(negotiatedText(jsonText = json, textBody = renderMetaMarkdown(meta), markdown = true))
}

private suspend fun structureOnly(call: ApplicationCall, file: VirtualFile, project: Project?) {
    val json = if (project != null) readStructureResult(project, file) else """{"elements":[]}"""
    val structure = decodeFromJson<FileStructure>(json)
    call.respondNegotiated(
        negotiatedText(
            jsonText = json,
            textBody = renderStructureMarkdown(structure),
            markdown = true,
        ),
    )
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
    val response = FileContentResponse(
        content = content?.payload,
        contentFormat = if (content?.isBinary == true) "base64" else null,
        meta = meta,
        exists = if (wantsExists) true else null,
        structure = structure,
    )
    call.respondNegotiated(
        negotiatedMarkdown(
            response,
            renderCompoundMarkdown(
                meta = meta,
                content = content?.takeUnless { it.isBinary }?.payload,
                language = file.markdownLanguageTag(),
                structure = structure,
                exists = if (wantsExists) true else null,
            ),
        ),
    )
}

private inline fun <reified T> decodeFromJson(json: String): T =
    kotlinx.serialization.json.Json.decodeFromString(json)

private fun VirtualFile.markdownLanguageTag(): String {
    return extension?.lowercase() ?: fileType.name.lowercase().takeUnless { it == "unknown" }.orEmpty()
}
