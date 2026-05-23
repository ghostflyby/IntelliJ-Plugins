/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mcp.route.project
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
 */
internal class FileContentFeature : WorkspaceMcpFeature {
    override val featureName: String = "file-content"

    override fun WorkspaceMcpFeatureRegistrationContext.register() {
        FileContentInvalidationListener(
            projectResolver = projectResolver,
            invalidationSink = invalidationSink,
            scope = featureScope,
        ).install()

        read<VfsResource> { resource ->
            readContentOrMeta(
                uri = call.request.params.uri,
                file = resolveFileByRawUrl(resource.rawVfsUrl),
                ancestors = call.parameters,
            )
        }

        read<ProjectFileResource> { resource ->
            val file = resolveFileByRelativePath(
                project = call.project(),
                relativePath = resource.relativePath,
            )
            readContentOrMeta(
                uri = call.request.params.uri,
                file = file,
                ancestors = call.parameters,
            )
        }

        // -- document write tools --
        registerToolClass<FileContentWriteTools>()


    }

    private suspend fun readContentOrMeta(uri: String, file: VirtualFile, ancestors: Map<String, String>): ReadResourceResult {
        val metaFields = ancestors["meta"]           // null: no meta; "": all; "a,b": subset
        val wantsContent = "content" in ancestors    // no key: fallback to content-only

        val items = mutableListOf<ResourceContents>()

        // Content
        if (wantsContent || metaFields == null) {
            val result = readContentResult(file)
            items += if (result.isBinary) {
                BlobResourceContents(uri = uri, mimeType = result.mimeType, blob = result.payload)
            } else {
                TextResourceContents(uri = uri, mimeType = result.mimeType, text = result.payload)
            }
        }

        // Metadata
        if (metaFields != null) {
            val metaResult = readMetaResult(file, metaFields)
            items += TextResourceContents(uri = uri, mimeType = metaResult.mimeType, text = metaResult.payload)
        }

        if (items.isEmpty()) {
            items += TextResourceContents(uri = uri, mimeType = "text/plain", text = "Empty result")
        }
        return ReadResourceResult(contents = items)
    }
}
