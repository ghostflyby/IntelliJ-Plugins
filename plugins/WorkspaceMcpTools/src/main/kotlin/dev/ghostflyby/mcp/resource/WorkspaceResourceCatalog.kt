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

internal data class WorkspaceListableResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
)

internal class WorkspaceResourceCatalog(
    private val project: Project,
) {
    internal suspend fun listResources(): List<WorkspaceListableResource> {
        return readAction {
            buildList {
                project.basePath?.let { basePath -> LocalFileSystem.getInstance().findFileByPath(basePath) }?.let { baseDir ->
                    add(baseDir.toVfsResource("Project base directory", "Workspace project base directory."))
                }

                ProjectRootManager.getInstance(project).contentRoots
                    .sortedBy { it.url }
                    .forEach { root ->
                        add(root.toVfsResource("Content root: ${root.presentableName}", "Workspace content root."))
                    }

                ProjectRootManager.getInstance(project).contentSourceRoots
                    .sortedBy { it.url }
                    .forEach { root ->
                        add(root.toVfsResource("Source root: ${root.presentableName}", "Workspace source root."))
                    }

                val documentManager = FileDocumentManager.getInstance()
                FileEditorManager.getInstance(project).openFiles
                    .sortedBy { it.url }
                    .forEach { file ->
                        add(file.toVfsResource("Open file: ${file.presentableName}", "Open editor file."))
                        if (!file.isDirectory && documentManager.getDocument(file) != null) {
                            add(
                                WorkspaceListableResource(
                                    uri = documentResourceUri(file.url),
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

    private fun VirtualFile.toVfsResource(
        name: String,
        description: String,
    ): WorkspaceListableResource {
        return WorkspaceListableResource(
            uri = vfsResourceUri(url),
            name = name,
            description = description,
            mimeType = if (isDirectory) APPLICATION_JSON_MIME_TYPE else fileType.name.toTextMimeType(),
        )
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
