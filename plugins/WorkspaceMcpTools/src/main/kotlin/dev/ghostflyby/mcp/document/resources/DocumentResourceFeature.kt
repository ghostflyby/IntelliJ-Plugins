/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.document.resources

import dev.ghostflyby.mcp.document.tools.*
import dev.ghostflyby.mcp.sdk.*

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

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        registerResourceTemplate(
            uriTemplate = NEW_WORKSPACE_DOCUMENTS_TEMPLATE,
            name = "Project document resource",
            description = "Reads the current editor document snapshot by project-relative path, including unsaved text.",
            mimeType = "text/plain",
        )

        registerResourceTemplate(
            uriTemplate = NEW_WORKSPACE_DOCUMENT_VFS_TEMPLATE,
            name = "Project document VFS resource",
            description = "Reads the current editor document snapshot by raw VFS URL within a project scope.",
            mimeType = "text/plain",
        )

        registerTool<DocumentSdkUrlArgs>(
            name = "document_is_writable",
            description = "Document.isWritable(): return whether document text is writable.",
            handler = { args, sid -> documentIsWritableHandler(args, sid, requestRunner) },
        )
        registerTool<DocumentSdkUrlArgs>(
            name = "document_get_modification_stamp",
            description = "Document.getModificationStamp(): return current modification stamp.",
            handler = { args, sid -> documentGetModificationStampHandler(args, sid, requestRunner) },
        )
        registerTool<DocumentSdkInsertArgs>(
            name = "document_insert_string",
            description = "Document.insertString(offset, text).",
            handler = { args, sid -> documentInsertStringHandler(args, sid, requestRunner) },
        )
        registerTool<DocumentSdkDeleteArgs>(
            name = "document_delete_string",
            description = "Document.deleteString(startOffset, endOffset).",
            handler = { args, sid -> documentDeleteStringHandler(args, sid, requestRunner) },
        )
        registerTool<DocumentSdkReplaceArgs>(
            name = "document_replace_string",
            description = "Document.replaceString(startOffset, endOffset, text).",
            handler = { args, sid -> documentReplaceStringHandler(args, sid, requestRunner) },
        )
        registerTool<DocumentSdkSetTextArgs>(
            name = "document_set_text",
            description = "Document.setText(text): replace whole document with new text.",
            handler = { args, sid -> documentSetTextHandler(args, sid, requestRunner) },
        )

        return buildRegistration()
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
