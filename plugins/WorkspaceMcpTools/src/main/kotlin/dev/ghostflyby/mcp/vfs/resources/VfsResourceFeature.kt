/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mcp.vfs.resources

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mcp.core.CoreResourceFeature
import dev.ghostflyby.mcp.resource.APPLICATION_JSON_MIME_TYPE
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.resource.toTextMimeType
import dev.ghostflyby.mcp.resource.workspaceFileUri
import dev.ghostflyby.mcp.resource.workspaceVfsUri
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureContext
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext
import dev.ghostflyby.mcp.sdk.workspaceInstanceKey
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import dev.ghostflyby.mcp.vfs.tools.vfsExistsSdkTool
import dev.ghostflyby.mcp.vfs.tools.vfsRefreshSdkTool
import dev.ghostflyby.mcp.vfs.tools.vfsGetUrlFromLocalPathTool
import dev.ghostflyby.mcp.vfs.tools.vfsGetUrlsFromLocalPathsTool
import dev.ghostflyby.mcp.vfs.tools.vfsGetLocalPathFromUrlTool
import dev.ghostflyby.mcp.vfs.tools.vfsGetLocalPathsFromUrlsTool
import dev.ghostflyby.mcp.vfs.tools.vfsExistsManySdkTool
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

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

    override fun register(context: WorkspaceMcpFeatureRegistrationContext): WorkspaceMcpFeatureRegistration {
        // VFS resource templates via segment DSL
        val projectAnchor = CoreResourceFeature.PROJECT_SEGMENT

        context.segments {
            under(projectAnchor) {
                segment("files") {
                    template("relativePath") { params, anc ->
                        val projectKey = anc[projectAnchor]
                        val relativePath = params["relativePath"] ?: ""
                        // Delegate to the existing reader for now
                        val instanceKey = workspaceInstanceKey()
                        val uri = workspaceFileUri(instanceKey, projectKey ?: "", relativePath)
                        context.readResource(uri, null)
                    }
                }
                segment("vfs") {
                    template("rawVfsUrl") { params, anc ->
                        val projectKey = anc[projectAnchor]
                        val rawVfsUrl = params["rawVfsUrl"] ?: ""
                        val instanceKey = workspaceInstanceKey()
                        val uri = workspaceVfsUri(instanceKey, projectKey ?: "", rawVfsUrl)
                        context.readResource(uri, null)
                    }
                }
            }
        }

        // Register SDK tools (unchanged)
        context.registerTool(vfsExistsSdkTool())
        context.registerTool(vfsRefreshSdkTool())
        context.registerTool(vfsGetUrlFromLocalPathTool())
        context.registerTool(vfsGetUrlsFromLocalPathsTool())
        context.registerTool(vfsGetLocalPathFromUrlTool())
        context.registerTool(vfsGetLocalPathsFromUrlsTool())
        context.registerTool(vfsExistsManySdkTool())

        return context.buildRegistration()
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
