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
import dev.ghostflyby.mcp.resource.APPLICATION_JSON_MIME_TYPE
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.resource.toTextMimeType
import dev.ghostflyby.mcp.resource.workspaceFileUri
import dev.ghostflyby.mcp.resource.workspaceVfsUri
import dev.ghostflyby.mcp.sdk.NEW_WORKSPACE_FILES_TEMPLATE
import dev.ghostflyby.mcp.sdk.NEW_WORKSPACE_VFS_TEMPLATE
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import dev.ghostflyby.mcp.sdk.workspaceInstanceKey
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult

/**
 * VFS resource feature: provides project-scoped file and VFS resource templates
 * and per-project listable resources for base directories, content roots,
 * source roots, and open files.
 *
 * This feature owns the `files/{relativePath}` and `vfs/{rawVfsUrl}` templates.
 */
internal class VfsResourceFeature(
    private val projectResolver: WorkspaceProjectResolver,
) : WorkspaceMcpFeature {
    override val featureName: String = "vfs-resources"

    override suspend fun computeListableResources(): List<WorkspaceListableResource> {
        val projects = readAction { projectResolver.openProjects() }
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

    override fun registerOnServer(
        server: Server,
        readResource: suspend (resourceUri: String, sessionId: String?) -> ReadResourceResult,
    ) {
        server.addResourceTemplate(
            uriTemplate = NEW_WORKSPACE_FILES_TEMPLATE,
            name = "Project file resource",
            description = "Reads IntelliJ VirtualFile content by project-relative path.",
            mimeType = "text/plain",
        ) { request, _ ->
            readResource(request.uri, this.sessionId)
        }

        server.addResourceTemplate(
            uriTemplate = NEW_WORKSPACE_VFS_TEMPLATE,
            name = "Project VFS resource",
            description = "Reads IntelliJ VirtualFile content by raw VFS URL within a project scope.",
            mimeType = "text/plain",
        ) { request, _ ->
            readResource(request.uri, this.sessionId)
        }
    }

    private fun relativePathFor(project: Project, file: VirtualFile): String? {
        val bp = project.basePath ?: return null
        val filePath = file.path
        if (filePath.startsWith(bp)) {
            return filePath.removePrefix(bp).trimStart('/')
        }
        return null
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
