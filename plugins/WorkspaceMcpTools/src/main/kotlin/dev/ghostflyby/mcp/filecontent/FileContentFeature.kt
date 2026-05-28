/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mcp.filecontent.tools.FileContentWriteTools
import dev.ghostflyby.mcp.route.project
import dev.ghostflyby.mcp.route.resources.FileContentQuery
import dev.ghostflyby.mcp.route.resources.ProjectFileResource
import dev.ghostflyby.mcp.route.resources.VfsResource
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

/**
 * Unified content access feature.
 *
 * Routes:
 * - `vfs/{rawVfsUrl...}` with optional `meta` and `content` query params — project-independent, read-only raw VFS access
 * - `projects/{projectKey}/files/{relativePath...}` with optional `meta` and `content` query params — project-scoped file access via Document API (read+write)
 *
 * Query params:
 * - no query → content only
 * - `?meta` → metadata JSON (all fields)
 * - `?meta=length,name` → metadata with field filter
 * - `?content` → content only
 * - `?meta&content` → both content + metadata
 * - `?exists` → existence check appended alongside other requested data
 * - `?meta&exists` → metadata + existence
 * - `?structure` → file structure overview (declarations)
 */
internal class FileContentFeature : WorkspaceMcpFeature {
    override val featureName: String = "file-content"

    override fun WorkspaceMcpFeatureRegistrationContext.register() {
        read<VfsResource> { resource ->
            val project = call.project()
            readFileContent(call.request.params.uri, resolveFileByRawUrlOrNull(resource.rawVfsUrl), project, resource)
        }

        read<ProjectFileResource> { resource ->
            val project = call.project()
            readFileContent(
                call.request.params.uri,
                resolveFileByRelativePathOrNull(project, resource.relativePath),
                project,
                resource,
            )
        }

        // -- document write tools --
        registerToolClass<FileContentWriteTools>()


    }

    private suspend fun readFileContent(
        uri: String,
        file: VirtualFile?,
        project: Project,
        query: FileContentQuery,
    ): ReadResourceResult {
        val wantsContent = query.content != null || (query.meta == null && !query.exists && !query.structure)
        val needsFile = wantsContent || query.meta != null || query.structure

        if (needsFile && file == null) throw ContentReadException("File not found")

        val items = mutableListOf<ResourceContents>()

        // Content
        if (wantsContent) {
            val result = readContentResult(file!!)
            items += if (result.isBinary) {
                BlobResourceContents(uri = uri, mimeType = result.mimeType, blob = result.payload)
            } else {
                TextResourceContents(uri = uri, mimeType = result.mimeType, text = result.payload)
            }
        }

        // Metadata
        val meta = query.meta
        if (meta != null) {
            val metaResult = readMetaResult(file!!, meta)
            items += TextResourceContents(uri = uri, mimeType = metaResult.mimeType, text = metaResult.payload)
        }

        // Existence
        if (query.exists) {
            items += TextResourceContents(uri = uri, mimeType = "application/json", text = (file != null).toString())
        }

        // Structure
        if (query.structure) {
            items += TextResourceContents(uri = uri, mimeType = "application/json", text = readStructureResult(project, file!!))
        }

        return ReadResourceResult(contents = items)
    }

}
