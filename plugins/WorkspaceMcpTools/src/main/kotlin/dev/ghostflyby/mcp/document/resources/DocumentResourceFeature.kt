/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.document.resources

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import dev.ghostflyby.mcp.document.tools.documentDeleteStringHandler
import dev.ghostflyby.mcp.document.tools.documentGetModificationStampHandler
import dev.ghostflyby.mcp.document.tools.documentInsertStringHandler
import dev.ghostflyby.mcp.document.tools.documentIsWritableHandler
import dev.ghostflyby.mcp.document.tools.documentReplaceStringHandler
import dev.ghostflyby.mcp.document.tools.documentSetTextHandler
import dev.ghostflyby.mcp.document.tools.DocumentSdkUrlArgs
import dev.ghostflyby.mcp.document.tools.DocumentSdkDeleteArgs
import dev.ghostflyby.mcp.document.tools.DocumentSdkInsertArgs
import dev.ghostflyby.mcp.document.tools.DocumentSdkReplaceArgs
import dev.ghostflyby.mcp.document.tools.DocumentSdkSetTextArgs
import dev.ghostflyby.mcp.resource.TEXT_PLAIN_MIME_TYPE
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.resource.workspaceDocumentUri
import dev.ghostflyby.mcp.resource.workspaceDocumentVfsUri
import dev.ghostflyby.mcp.sdk.NEW_WORKSPACE_DOCUMENTS_TEMPLATE
import dev.ghostflyby.mcp.sdk.NEW_WORKSPACE_DOCUMENT_VFS_TEMPLATE
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureContext
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext
import dev.ghostflyby.mcp.sdk.workspaceInstanceKey
import dev.ghostflyby.mcp.sdk.workspaceProjectKey

/**
 * Document resource feature: provides project-scoped document resource templates
 * and per-project listable resources for open document snapshots.
 *
 * This feature owns the `documents/{relativePath}` and `document-vfs/{rawVfsUrl}` templates
 * and the SDK document tools (document_is_writable, document_get_modification_stamp,
 * document_insert_string, document_delete_string, document_replace_string, document_set_text).
 */
internal class DocumentResourceFeature : WorkspaceMcpFeature {
    override val featureName: String = "document-resources"

    override suspend fun computeListableResources(context: WorkspaceMcpFeatureContext): List<WorkspaceListableResource> {
        val projects = readAction { context.projectResolver.openProjects() }
        return buildList {
            projects.forEach { project ->
                val instanceKey = workspaceInstanceKey()
                val projectKey = workspaceProjectKey(project)
                val documentManager = FileDocumentManager.getInstance()

                FileEditorManager.getInstance(project).openFiles
                    .sortedBy { it.url }
                    .filter { !it.isDirectory && documentManager.getDocument(it) != null }
                    .forEach { file ->
                        val relativePath = file.relativePathFor(project)
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

    override fun register(context: WorkspaceMcpFeatureRegistrationContext): WorkspaceMcpFeatureRegistration {
        context.registerResourceTemplate(
            uriTemplate = NEW_WORKSPACE_DOCUMENTS_TEMPLATE,
            name = "Project document resource",
            description = "Reads the current editor document snapshot by project-relative path, including unsaved text.",
            mimeType = "text/plain",
        )

        context.registerResourceTemplate(
            uriTemplate = NEW_WORKSPACE_DOCUMENT_VFS_TEMPLATE,
            name = "Project document VFS resource",
            description = "Reads the current editor document snapshot by raw VFS URL within a project scope.",
            mimeType = "text/plain",
        )

        context.registerTool<DocumentSdkUrlArgs>(
            name = "document_is_writable",
            description = "Document.isWritable(): return whether document text is writable.",
            handler = { args, sid -> documentIsWritableHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<DocumentSdkUrlArgs>(
            name = "document_get_modification_stamp",
            description = "Document.getModificationStamp(): return current modification stamp.",
            handler = { args, sid -> documentGetModificationStampHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<DocumentSdkInsertArgs>(
            name = "document_insert_string",
            description = "Document.insertString(offset, text).",
            handler = { args, sid -> documentInsertStringHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<DocumentSdkDeleteArgs>(
            name = "document_delete_string",
            description = "Document.deleteString(startOffset, endOffset).",
            handler = { args, sid -> documentDeleteStringHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<DocumentSdkReplaceArgs>(
            name = "document_replace_string",
            description = "Document.replaceString(startOffset, endOffset, text).",
            handler = { args, sid -> documentReplaceStringHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<DocumentSdkSetTextArgs>(
            name = "document_set_text",
            description = "Document.setText(text): replace whole document with new text.",
            handler = { args, sid -> documentSetTextHandler(args, sid, context.requestRunner) },
        )

        return context.buildRegistration()
    }

    private fun com.intellij.openapi.vfs.VirtualFile.relativePathFor(project: com.intellij.openapi.project.Project): String? {
        val bp = project.basePath ?: return null
        val filePath = this.path
        if (filePath.startsWith(bp)) {
            return filePath.removePrefix(bp).trimStart('/')
        }
        return null
    }
}
