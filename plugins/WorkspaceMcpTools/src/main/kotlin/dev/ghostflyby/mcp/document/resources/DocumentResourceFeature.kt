/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.document.resources

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.resource.TEXT_PLAIN_MIME_TYPE
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.resource.workspaceDocumentUri
import dev.ghostflyby.mcp.resource.workspaceDocumentVfsUri
import dev.ghostflyby.mcp.sdk.NEW_WORKSPACE_DOCUMENTS_TEMPLATE
import dev.ghostflyby.mcp.sdk.NEW_WORKSPACE_DOCUMENT_VFS_TEMPLATE
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import dev.ghostflyby.mcp.sdk.workspaceInstanceKey
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult

/**
 * Document resource feature: provides project-scoped document resource templates
 * and per-project listable resources for open document snapshots.
 *
 * This feature owns the `documents/{relativePath}` and `document-vfs/{rawVfsUrl}` templates.
 */
internal class DocumentResourceFeature(
    private val projectResolver: WorkspaceProjectResolver,
) : WorkspaceMcpFeature {
    override val featureName: String = "document-resources"

    override suspend fun computeListableResources(): List<WorkspaceListableResource> {
        val projects = readAction { projectResolver.openProjects() }
        return buildList {
            projects.forEach { project ->
                val instanceKey = workspaceInstanceKey()
                val projectKey = workspaceProjectKey(project)
                val documentManager = FileDocumentManager.getInstance()

                FileEditorManager.getInstance(project).openFiles
                    .sortedBy { it.url }
                    .filter { !it.isDirectory && documentManager.getDocument(it) != null }
                    .forEach { file ->
                        val relativePath = project.relativePathFor(file)
                        add(
                            WorkspaceListableResource(
                                uri = if (relativePath != null) workspaceDocumentUri(instanceKey, projectKey, relativePath)
                                      else workspaceDocumentVfsUri(instanceKey, projectKey, file.url),
                                name = "Open document: ${file.presentableName}",
                                description = "Current editor document snapshot for an open file.",
                                mimeType = TEXT_PLAIN_MIME_TYPE,
                            ),
                        )
                    }
            }
        }
    }

    override fun registerOnServer(
        server: Server,
        readResource: suspend (resourceUri: String, sessionId: String?) -> ReadResourceResult,
    ) {
        server.addResourceTemplate(
            uriTemplate = NEW_WORKSPACE_DOCUMENTS_TEMPLATE,
            name = "Project document resource",
            description = "Reads the current editor document snapshot by project-relative path, including unsaved text.",
            mimeType = "text/plain",
        ) { request, _ ->
            readResource(request.uri, this.sessionId)
        }

        server.addResourceTemplate(
            uriTemplate = NEW_WORKSPACE_DOCUMENT_VFS_TEMPLATE,
            name = "Project document VFS resource",
            description = "Reads the current editor document snapshot by raw VFS URL within a project scope.",
            mimeType = "text/plain",
        ) { request, _ ->
            readResource(request.uri, this.sessionId)
        }
    }

    private fun Project.relativePathFor(file: com.intellij.openapi.vfs.VirtualFile): String? {
        val bp = basePath ?: return null
        val filePath = file.path
        if (filePath.startsWith(bp)) {
            return filePath.removePrefix(bp).trimStart('/')
        }
        return null
    }
}
