/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.vfs.resources

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mcp.core.CoreResourceFeature
import dev.ghostflyby.mcp.resource.*
import dev.ghostflyby.mcp.sdk.*
import dev.ghostflyby.mcp.vfs.tools.*

/**
 * VFS resource feature: provides project-scoped file and VFS resource templates
 * and per-project listable resources via the segment-based URI tree.
 */
internal class VfsResourceFeature : WorkspaceMcpFeature {
    override val featureName: String = "vfs-resources"

    override suspend fun computeListableResources(
        context: WorkspaceMcpFeatureContext,
    ): List<WorkspaceListableResource> {
        val projects = readAction { context.projectResolver.openProjects() }
        return buildList {
            projects.forEach { project ->
                val instanceKey = workspaceInstanceKey()
                val projectKey = workspaceProjectKey(project)

                project.basePath?.let { basePath ->
                    LocalFileSystem.getInstance().findFileByPath(basePath)
                }?.let { baseDir ->
                    add(baseDir.toProjectFileResource(projectKey, instanceKey, project,
                        "Project base directory: ${project.name}",
                        "Workspace project base directory.",
                    ))
                }

                ProjectRootManager.getInstance(project).contentRoots
                    .sortedBy { it.url }
                    .forEach { root ->
                        add(root.toProjectFileResource(projectKey, instanceKey, project,
                            "Content root: ${root.presentableName}",
                            "Workspace content root.",
                        ))
                    }

                ProjectRootManager.getInstance(project).contentSourceRoots
                    .sortedBy { it.url }
                    .forEach { root ->
                        add(root.toProjectFileResource(projectKey, instanceKey, project,
                            "Source root: ${root.presentableName}",
                            "Workspace source root.",
                        ))
                    }

                FileEditorManager.getInstance(project).openFiles
                    .sortedBy { it.url }
                    .forEach { file ->
                        add(file.toProjectFileResource(projectKey, instanceKey, project,
                            "Open file: ${file.presentableName}",
                            "Open editor file.",
                        ))
                    }
            }
        }
    }

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        // VFS resource templates via segment DSL
        val projectAnchor = CoreResourceFeature.PROJECT_SEGMENT

        segments {
            under(projectAnchor) {
                segment("files") {
                    template("relativePath") { params, anc ->
                        val projectKey = anc[projectAnchor]
                        val relativePath = params["relativePath"] ?: ""
                        // Delegate to the existing reader for now
                        val instanceKey = workspaceInstanceKey()
                        val uri = workspaceFileUri(instanceKey, projectKey ?: "", relativePath)
                        readResource(uri, null)
                    }
                }
                segment("vfs") {
                    template("rawVfsUrl") { params, anc ->
                        val projectKey = anc[projectAnchor]
                        val rawVfsUrl = params["rawVfsUrl"] ?: ""
                        val instanceKey = workspaceInstanceKey()
                        val uri = workspaceVfsUri(instanceKey, projectKey ?: "", rawVfsUrl)
                        readResource(uri, null)
                    }
                }
            }
        }

        // Register SDK tools (inline handler)
        registerTool<VfsExistsArgs>(
            name = "vfs_exists",
            description = "Check whether a VFS URL or project-relative path currently resolves to an existing file or directory.",
            handler = { args, sid -> vfsExistsHandler(args, sid, requestRunner) },
        )
        registerTool<VfsRefreshArgs>(
            name = "vfs_refresh",
            description = "Refresh a VFS file or directory. Supports project-scoped URL resolution via optional projectKey/projectPath.",
            handler = { args, sid -> vfsRefreshHandler(args, sid, requestRunner) },
        )
        registerTool<VfsGetUrlArgs>(
            name = "vfs_get_url_from_local_path",
            description = "Resolve a project-relative local path to a VFS URL. " +
                "This is a convenience helper: for local files, you can directly pass " +
                "a file:///absolute/path URL to tools that accept VFS URLs.",
            handler = { args, sid -> vfsGetUrlHandler(args, sid, requestRunner) },
        )
        registerTool<VfsGetUrlsArgs>(
            name = "vfs_get_url_from_local_paths",
            description = "Resolve multiple project-relative local paths to VFS URLs. " +
                "This is a convenience helper: for local files, you can directly pass " +
                "file:///absolute/path URLs to tools that accept VFS URLs.",
            handler = { args, sid -> vfsGetUrlsHandler(args, sid, requestRunner) },
        )
        registerTool<VfsGetLocalPathArgs>(
            name = "vfs_get_local_path_from_url",
            description = "Resolve a VFS URL to a local file-system path.",
            handler = { args, sid -> vfsGetLocalPathHandler(args, sid, requestRunner) },
        )
        registerTool<VfsGetLocalPathsArgs>(
            name = "vfs_get_local_paths_from_urls",
            description = "Resolve multiple VFS URLs to local file-system paths.",
            handler = { args, sid -> vfsGetLocalPathsHandler(args, sid, requestRunner) },
        )
        registerTool<VfsExistsManyArgs>(
            name = "vfs_exists_many",
            description = "Check whether multiple VFS URLs currently resolve to existing files or directories.",
            handler = { args, sid -> vfsExistsManyHandler(args, sid, requestRunner) },
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
