/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.vfs.resources

import dev.ghostflyby.mcp.core.CoreResourceFeature
import dev.ghostflyby.mcp.resource.TEXT_PLAIN_MIME_TYPE
import dev.ghostflyby.mcp.resource.workspaceFileUri
import dev.ghostflyby.mcp.resource.workspaceVfsUri
import dev.ghostflyby.mcp.scope.descriptor.tools.jsonSchema
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext
import dev.ghostflyby.mcp.sdk.workspaceInstanceKey
import dev.ghostflyby.mcp.vfs.tools.*
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

/**
 * VFS resource feature: provides project-scoped file and VFS resource templates
 * and per-project listable resources via the segment-based URI tree.
 */
internal class VfsResourceFeature : WorkspaceMcpFeature {
    override val featureName: String = "vfs-resources"

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        // VFS resource templates via segment DSL
        val projectAnchor = CoreResourceFeature.PROJECT_SEGMENT

        segments {
            under(projectAnchor) {
                segment("files") {
                    template("relativePath") { call ->
                        val anc = call.ancestors
                        val projectKey = anc[projectAnchor] ?: ""
                        val relativePath = anc["relativePath"] ?: ""
                        val instanceKey = workspaceInstanceKey()
                        val uri = workspaceFileUri(instanceKey, projectKey, relativePath)
                        val text = readFileByRelativePath(uri, projectKey, relativePath, projectResolver)
                        ReadResourceResult(
                            contents = listOf(
                                TextResourceContents(
                                    uri = uri,
                                    mimeType = TEXT_PLAIN_MIME_TYPE,
                                    text = text
                                )
                            )
                        )
                    }
                }
                segment("vfs") {
                    template("rawVfsUrl") { call ->
                        val anc = call.ancestors
                        val projectKey = anc[projectAnchor] ?: ""
                        val rawVfsUrl = anc["rawVfsUrl"] ?: ""
                        val instanceKey = workspaceInstanceKey()
                        val uri = workspaceVfsUri(instanceKey, projectKey, rawVfsUrl)
                        val text = readFileByVfsUrl(uri, rawVfsUrl, projectResolver)
                        ReadResourceResult(
                            contents = listOf(
                                TextResourceContents(
                                    uri = uri,
                                    mimeType = TEXT_PLAIN_MIME_TYPE,
                                    text = text
                                )
                            )
                        )
                    }
                }
            }
        }

        // Register SDK tools (inline handler)
        registerTool<VfsExistsArgs>(
            name = "vfs_exists",
            description = "Check whether a VFS URL or project-relative path currently resolves to an existing file or directory.",
            schema = VfsExistsArgs::class.jsonSchema,
            handler = { args, request -> vfsExistsHandler(args, request) },
        )
        registerTool<VfsRefreshArgs>(
            name = "vfs_refresh",
            description = "Refresh a VFS file or directory. Supports project-scoped URL resolution via optional projectKey/projectPath.",
            schema = VfsRefreshArgs::class.jsonSchema,
            handler = { args, request -> vfsRefreshHandler(args, request) },
        )
        registerTool<VfsGetUrlArgs>(
            name = "vfs_get_url_from_local_path",
            description = "Resolve a project-relative local path to a VFS URL. " +
                "This is a convenience helper: for local files, you can directly pass " +
                "a file:///absolute/path URL to tools that accept VFS URLs.",
            schema = VfsGetUrlArgs::class.jsonSchema,
            handler = { args, request -> vfsGetUrlHandler(args, request) },
        )
        registerTool<VfsGetUrlsArgs>(
            name = "vfs_get_url_from_local_paths",
            description = "Resolve multiple project-relative local paths to VFS URLs. " +
                "This is a convenience helper: for local files, you can directly pass " +
                "file:///absolute/path URLs to tools that accept VFS URLs.",
            schema = VfsGetUrlsArgs::class.jsonSchema,
            handler = { args, request -> vfsGetUrlsHandler(args, request) },
        )
        registerTool<VfsGetLocalPathArgs>(
            name = "vfs_get_local_path_from_url",
            description = "Resolve a VFS URL to a local file-system path.",
            schema = VfsGetLocalPathArgs::class.jsonSchema,
            handler = { args, request -> vfsGetLocalPathHandler(args, request) },
        )
        registerTool<VfsGetLocalPathsArgs>(
            name = "vfs_get_local_paths_from_urls",
            description = "Resolve multiple VFS URLs to local file-system paths.",
            schema = VfsGetLocalPathsArgs::class.jsonSchema,
            handler = { args, request -> vfsGetLocalPathsHandler(args, request) },
        )
        registerTool<VfsExistsManyArgs>(
            name = "vfs_exists_many",
            description = "Check whether multiple VFS URLs currently resolve to existing files or directories.",
            schema = VfsExistsManyArgs::class.jsonSchema,
            handler = { args, request -> vfsExistsManyHandler(args, request) },
        )

        return buildRegistration()
    }
}
