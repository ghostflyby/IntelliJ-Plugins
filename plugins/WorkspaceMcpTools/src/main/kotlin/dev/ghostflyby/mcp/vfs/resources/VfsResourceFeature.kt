/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.vfs.resources

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mcp.core.CoreResourceFeature
import dev.ghostflyby.mcp.resource.*
import dev.ghostflyby.mcp.sdk.*
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
                    template("relativePath") { params, anc, request ->
                        val projectKey = anc[projectAnchor] ?: ""
                        val relativePath = params["relativePath"] ?: ""
                        val instanceKey = workspaceInstanceKey()
                        val uri = workspaceFileUri(instanceKey, projectKey, relativePath)
                        resourceReader.readFileByRelativePath(uri, projectKey, relativePath).run {
                            ReadResourceResult(
                                contents = listOf(
                                    TextResourceContents(
                                        uri = uri,
                                        mimeType = mimeType,
                                        text = text
                                    )
                                )
                            )
                        }
                    }
                }
                segment("vfs") {
                    template("rawVfsUrl") { params, anc, request ->
                        val projectKey = anc[projectAnchor] ?: ""
                        val rawVfsUrl = params["rawVfsUrl"] ?: ""
                        val instanceKey = workspaceInstanceKey()
                        val uri = workspaceVfsUri(instanceKey, projectKey, rawVfsUrl)
                        resourceReader.readVfsResource(uri, rawVfsUrl).run {
                            ReadResourceResult(
                                contents = listOf(
                                    TextResourceContents(
                                        uri = uri,
                                        mimeType = mimeType,
                                        text = text
                                    )
                                )
                            )
                        }
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

    private fun relativePathFor(project: Project, file: VirtualFile): String? {
        val bp = project.basePath ?: return null
        val filePath = file.path
        return if (filePath.startsWith(bp)) filePath.removePrefix(bp).trimStart('/') else null
    }

    private fun VirtualFile.toProjectFileResource(
        projectKey: String,
        instanceKey: String,
        project: Project,
        name: String,
        description: String,
    ): WorkspaceListableResource {
        val relativePath = relativePathFor(project, this)
        return WorkspaceListableResource(
            uri = if (relativePath != null) workspaceFileUri(instanceKey, projectKey, relativePath)
                  else workspaceVfsUri(instanceKey, projectKey, url),
            name = name,
            description = description,
            mimeType = if (isDirectory) APPLICATION_JSON_MIME_TYPE else fileType.name.toTextMimeType(),
        )
    }
}
