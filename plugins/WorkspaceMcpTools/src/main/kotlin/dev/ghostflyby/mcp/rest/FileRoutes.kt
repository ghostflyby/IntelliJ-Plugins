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
import dev.ghostflyby.mcp.rest.markdown.BlockKind
import dev.ghostflyby.mcp.rest.markdown.MarkdownBlock
import dev.ghostflyby.mcp.rest.markdown.MarkdownExclude
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
    @MarkdownExclude
    val language: String = "",
    @MarkdownExclude
    val contentFormat: String? = null,
)

private data class LineRangeQuery(
    val startLine: Int? = null,
    val endLine: Int? = null,
    val maxLines: Int? = null,
    val aroundLine: Int? = null,
    val radius: Int? = null,
) {
    val isPresent: Boolean =
        startLine != null || endLine != null || maxLines != null || aroundLine != null || radius != null
}

// -- Route registrations --
internal fun Route.fileRoutes() {
    val resolver: WorkspaceProjectResolver = service()
    get<Api.Vfs> { resource ->
        val rawVfsUrl = resource.rawVfsUrl.toRoutePath()
        val file = resolveFileByRawUrlOrNull(rawVfsUrl)
        val project = if (file != null && resource.structure)
            projectForRawVfsUrl(rawVfsUrl, resolver) else null
        respondFileContent(
            call,
            file,
            resource.meta,
            resource.content,
            resource.exists,
            resource.structure,
            project,
            rangeQuery = resource.lineRangeQuery(),
        )
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
            rangeQuery = resource.lineRangeQuery(),
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
            rangeQuery = resource.lineRangeQuery(),
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
    rangeQuery: LineRangeQuery = LineRangeQuery(),
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
            respondFileContent(
                call,
                access.file,
                meta,
                content,
                exists,
                structure,
                resolved.project,
                access.policy,
                rangeQuery,
            )
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
    rangeQuery: LineRangeQuery = LineRangeQuery(),
) {
    val wantsContent = content || rangeQuery.isPresent || (!meta && !exists && !structure)
    val wantsMeta = meta
    val wantsExists = exists
    val wantsStructure = structure
    val lineRange = if (wantsContent) {
        try {
            rangeQuery.toFileLineRange()
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, RestError(e.message.orEmpty()))
            return
        }
    } else {
        null
    }
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
    if (wantsContent && lineRange != null && (file!!.isDirectory || file.fileType.isBinary)) {
        call.respond(HttpStatusCode.BadRequest, RestError("Range reads are supported only for text files"))
        return
    }
    // Single-responsibility paths
    if (wantsStructure && !wantsMeta && !wantsContent && !wantsExists)
        return structureOnly(call, file!!, project)
    if (wantsMeta && !wantsContent && !wantsExists && !wantsStructure)
        return metaOnly(call, file!!, effectivePolicy)
    if (!wantsMeta && !wantsExists && !wantsStructure)
        return contentOnly(call, file!!, lineRange)
    // Compound: at least two of meta/content/exists/structure
    compoundResult(call, file!!, wantsMeta, wantsContent, wantsExists, wantsStructure, project, effectivePolicy, lineRange)
}

// -- Single response modes --
private suspend fun contentOnly(call: ApplicationCall, file: VirtualFile, range: FileLineRange? = null) {
    if (range != null) {
        val content = try {
            readTextRangeResult(file, range)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, RestError(e.message.orEmpty()))
            return
        }
        call.respondText(content.text, ContentType.parse(content.mimeType))
        return
    }
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
    range: FileLineRange? = null,
) {
    val effectivePolicy = policy ?: fileMetaPolicyFallback(file)
    val meta = if (wantsMeta) readMetaResult(file, effectivePolicy) else null
    val content = if (wantsContent) {
        if (range != null) readTextRangeResult(file, range) else readContentResult(file)
    } else {
        null
    }
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

private fun Api.Vfs.lineRangeQuery(): LineRangeQuery =
    LineRangeQuery(startLine, endLine, maxLines, aroundLine, radius)

private fun Api.Project.Root.lineRangeQuery(): LineRangeQuery =
    LineRangeQuery(startLine, endLine, maxLines, aroundLine, radius)

private fun Api.Project.Root.File.lineRangeQuery(): LineRangeQuery =
    LineRangeQuery(startLine, endLine, maxLines, aroundLine, radius)

private fun LineRangeQuery.toFileLineRange(): FileLineRange? {
    if (!isPresent) return null
    val hasStartEnd = startLine != null && endLine != null && maxLines == null && aroundLine == null && radius == null
    val hasStartMax = startLine != null && maxLines != null && endLine == null && aroundLine == null && radius == null
    val hasAround = aroundLine != null && radius != null && startLine == null && endLine == null && maxLines == null
    return when {
        hasStartEnd -> FileLineRange.Lines(startLine, endLine)
        hasStartMax -> FileLineRange.MaxLines(startLine, maxLines)
        hasAround -> FileLineRange.Around(aroundLine, radius)
        else -> throw IllegalArgumentException(
            "Range query must use exactly one of startLine+endLine, startLine+maxLines, or aroundLine+radius",
        )
    }
}
