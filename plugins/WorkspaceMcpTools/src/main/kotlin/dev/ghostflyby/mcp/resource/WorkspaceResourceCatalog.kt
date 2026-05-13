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

package dev.ghostflyby.mcp.resource

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mcp.sdk.workspaceInstanceKey
import dev.ghostflyby.mcp.sdk.workspaceProjectKey

internal data class WorkspaceListableResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
)

// Listable resource URI paths (not full templates; these are concrete URIs)
internal fun listableServerInfoUri(instanceKey: String): String =
    "ij-workspace://$instanceKey/server/info"

internal fun listableProjectsUri(instanceKey: String): String =
    "ij-workspace://$instanceKey/projects"

internal fun listableProjectInfoUri(instanceKey: String, projectKey: String): String =
    "ij-workspace://$instanceKey/projects/$projectKey"

internal class WorkspaceResourceCatalog(
    private val project: Project,
) {
    private val instanceKey = workspaceInstanceKey()
    private val projectKey = workspaceProjectKey(project)

    internal suspend fun listResources(): List<WorkspaceListableResource> {
        return readAction {
            buildList {
                // Server info
                add(
                    WorkspaceListableResource(
                        uri = listableServerInfoUri(instanceKey),
                        name = "Workspace MCP server info",
                        description = "IDE runtime and server metadata.",
                        mimeType = APPLICATION_JSON_MIME_TYPE,
                    ),
                )
                // Projects list
                add(
                    WorkspaceListableResource(
                        uri = listableProjectsUri(instanceKey),
                        name = "Open projects",
                        description = "List of open IDE projects.",
                        mimeType = APPLICATION_JSON_MIME_TYPE,
                    ),
                )
                // Single project info
                add(
                    WorkspaceListableResource(
                        uri = listableProjectInfoUri(instanceKey, projectKey),
                        name = "Project info: ${project.name}",
                        description = "Project metadata for key $projectKey.",
                        mimeType = APPLICATION_JSON_MIME_TYPE,
                    ),
                )

                // Project base directory (project-relative files)
                project.basePath?.let { basePath ->
                    LocalFileSystem.getInstance().findFileByPath(basePath)
                }?.let { baseDir ->
                    add(
                        baseDir.toProjectFileResource(
                            "Project base directory: ${project.name}",
                            "Workspace project base directory.",
                        ),
                    )
                }

                ProjectRootManager.getInstance(project).contentRoots
                    .sortedBy { it.url }
                    .forEach { root ->
                        add(
                            root.toProjectFileResource(
                                "Content root: ${root.presentableName}",
                                "Workspace content root.",
                            ),
                        )
                    }

                ProjectRootManager.getInstance(project).contentSourceRoots
                    .sortedBy { it.url }
                    .forEach { root ->
                        add(
                            root.toProjectFileResource(
                                "Source root: ${root.presentableName}",
                                "Workspace source root.",
                            ),
                        )
                    }

                val documentManager = FileDocumentManager.getInstance()
                FileEditorManager.getInstance(project).openFiles
                    .sortedBy { it.url }
                    .forEach { file ->
                        add(file.toProjectFileResource("Open file: ${file.presentableName}", "Open editor file."))
                        if (!file.isDirectory && documentManager.getDocument(file) != null) {
                            add(
                                WorkspaceListableResource(
                                    uri = file.toProjectDocumentUri(),
                                    name = "Open document: ${file.presentableName}",
                                    description = "Current editor document snapshot for an open file.",
                                    mimeType = TEXT_PLAIN_MIME_TYPE,
                                ),
                            )
                        }
                    }
            }.distinctBy { it.uri }
        }
    }

    private fun VirtualFile.toProjectFileResource(
        name: String,
        description: String,
    ): WorkspaceListableResource {
        val relativePath = project.relativePathFor(this)
        return WorkspaceListableResource(
            uri = if (relativePath != null) workspaceFileUri(instanceKey, projectKey, relativePath)
                  else workspaceVfsUri(instanceKey, projectKey, url),
            name = name,
            description = description,
            mimeType = if (isDirectory) APPLICATION_JSON_MIME_TYPE else fileType.name.toTextMimeType(),
        )
    }

    private fun VirtualFile.toProjectDocumentUri(): String {
        val relativePath = project.relativePathFor(this)
        return if (relativePath != null) workspaceDocumentUri(instanceKey, projectKey, relativePath)
               else workspaceDocumentVfsUri(instanceKey, projectKey, url)
    }

    private fun Project.relativePathFor(file: VirtualFile): String? {
        val bp = basePath ?: return null
        val filePath = file.path
        if (filePath.startsWith(bp)) {
            return filePath.removePrefix(bp).trimStart('/')
        }
        return null
    }
}

internal fun String.toTextMimeType(): String {
    return when (lowercase()) {
        "kotlin" -> "text/x-kotlin"
        "java" -> "text/x-java-source"
        "xml" -> "application/xml"
        "json" -> APPLICATION_JSON_MIME_TYPE
        "markdown" -> "text/markdown"
        else -> TEXT_PLAIN_MIME_TYPE
    }
}

internal const val TEXT_PLAIN_MIME_TYPE = "text/plain"
internal const val APPLICATION_JSON_MIME_TYPE = "application/json"
