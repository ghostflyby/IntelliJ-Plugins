/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.ghostflyby.mcp.rest

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.fasterxml.jackson.annotation.JsonIgnore
import dev.ghostflyby.mcp.filecontent.*
import dev.ghostflyby.mcp.rest.markdown.BlockKind
import dev.ghostflyby.mcp.rest.markdown.MarkdownBlock
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.io.encoding.Base64

@Serializable
private data class FileContentResponse(
    val meta: FileMeta? = null,
    val exists: Boolean? = null,
    @MarkdownBlock(
        BlockKind.CODE_FENCE,
        languageProperty = "language",
        skipWhenProperty = "contentFormat",
        skipWhenEquals = "base64",
    )
    val content: String? = null,
    @MarkdownBlock(BlockKind.STRUCTURE_TREE, heading = "## Structure")
    val structure: FileStructure? = null,
    @Transient @JsonIgnore
    val language: String = "",
    @JsonIgnore
    val contentFormat: String? = null,
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
                call.respond(HttpStatusCode.NotFound, RestError("Root not found"))
                return
            }
            val access = resolveProjectFileAccess(resolved.project, target.root, target.relativePath)
            respondFileContent(call, access.file, meta, content, exists, structure, resolved.project, access.policy)
        }

        is WorkspaceProjectResolution.Unresolved -> {
            call.respond(HttpStatusCode.NotFound, RestError(resolved.message, projectKey))
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
        call.respond(HttpStatusCode.NotFound, RestError("File not found"))
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
        call.respond(HttpStatusCode.NotFound, RestError("File not found"))
        return
    }
    if (wantsStructure && !effectivePolicy.canRead(FileContentKind.STRUCTURE)) {
        call.respond(HttpStatusCode.Forbidden, RestError(effectivePolicy.reason))
        return
    }
    if (wantsContent && !effectivePolicy.canRead(FileContentKind.CONTENT) && !effectivePolicy.canRead(FileContentKind.BYTES)
    ) {
        call.respond(HttpStatusCode.Forbidden, RestError(effectivePolicy.reason))
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
    when (val c = readContentResult(file)) {
        is FileContent.Binary -> call.respondBytes(c.bytes, ContentType.parse(c.mimeType))
        is FileContent.Directory -> call.respond(c.listing)
        is FileContent.Text -> call.respondText(c.text, ContentType.parse(c.mimeType))
    }
}

private suspend fun metaOnly(call: ApplicationCall, file: VirtualFile, policy: FileAccessPolicy) {
    call.respond(readMetaResult(file, policy))
}

private suspend fun structureOnly(call: ApplicationCall, file: VirtualFile, project: Project?) {
    call.respond(if (project != null) readStructureResult(project, file) else FileStructure(emptyList()))
}

// -- Compound response --
private suspend fun compoundResult(
    call: ApplicationCall, file: VirtualFile,
    wantsMeta: Boolean, wantsContent: Boolean, wantsExists: Boolean, wantsStructure: Boolean,
    project: Project?,
    policy: FileAccessPolicy?,
) {
    val effectivePolicy = policy ?: fileMetaPolicyFallback(file)
    val meta = if (wantsMeta) readMetaResult(file, effectivePolicy) else null
    val content = if (wantsContent) readContentResult(file) else null
    val structure = if (wantsStructure && project != null) readStructureResult(project, file) else null
    val response = FileContentResponse(
        meta = meta,
        exists = if (wantsExists) true else null,
        content = content?.let {
            when (it) {
                is FileContent.Text -> it.text
                is FileContent.Binary -> Base64.encode(it.bytes)
                is FileContent.Directory -> it.listing.renderTextBody()
            }
        },
        structure = structure,
        language = file.markdownLanguageTag(),
        contentFormat = if (content is FileContent.Binary) "base64" else null,
    )
    call.respond(response)
}

private fun VirtualFile.markdownLanguageTag(): String {
    return extension?.lowercase() ?: fileType.name.lowercase().takeUnless { it == "unknown" }.orEmpty()
}
