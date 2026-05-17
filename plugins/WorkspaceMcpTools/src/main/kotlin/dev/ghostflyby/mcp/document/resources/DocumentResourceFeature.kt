/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.document.resources

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import dev.ghostflyby.mcp.core.CoreResourceFeature
import dev.ghostflyby.mcp.document.tools.*
import dev.ghostflyby.mcp.resource.TEXT_PLAIN_MIME_TYPE
import dev.ghostflyby.mcp.resource.workspaceDocumentUri
import dev.ghostflyby.mcp.resource.workspaceDocumentVfsUri
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext
import dev.ghostflyby.mcp.sdk.workspaceInstanceKey
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

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
        val projectAnchor = CoreResourceFeature.PROJECT_SEGMENT

        // Document resource templates via segment DSL
        segments {
            under(projectAnchor) {
                segment("documents") {
                    template("relativePath") { params, anc, request ->
                        val projectKey = anc[projectAnchor] ?: ""
                        val relativePath = params["relativePath"] ?: ""
                        val instanceKey = workspaceInstanceKey()
                        val uri = workspaceDocumentUri(instanceKey, projectKey, relativePath)
                        val text = readDocumentByRelativePath(relativePath, projectKey, projectResolver)
                        ReadResourceResult(
                            contents = listOf(
                                TextResourceContents(
                                    uri = uri,
                                    mimeType = TEXT_PLAIN_MIME_TYPE,
                                    text = text,
                                ),
                            ),
                        )
                    }
                }
                segment("document-vfs") {
                    template("rawVfsUrl") { params, anc, request ->
                        val projectKey = anc[projectAnchor] ?: ""
                        val rawVfsUrl = params["rawVfsUrl"] ?: ""
                        val instanceKey = workspaceInstanceKey()
                        val uri = workspaceDocumentVfsUri(instanceKey, projectKey, rawVfsUrl)
                        val text = readDocumentByVfsUrl(rawVfsUrl)
                        ReadResourceResult(
                            contents = listOf(
                                TextResourceContents(
                                    uri = uri,
                                    mimeType = TEXT_PLAIN_MIME_TYPE,
                                    text = text,
                                ),
                            ),
                        )
                    }
                }
            }
        }

        registerTool<DocumentSdkUrlArgs>(
            name = "document_is_writable",
            description = "Document.isWritable(): return whether document text is writable.",
            schema = DocumentSdkUrlArgs::class.jsonSchema,
            handler = { args, request -> documentIsWritableHandler(args, request) },
        )
        registerTool<DocumentSdkUrlArgs>(
            name = "document_get_modification_stamp",
            description = "Document.getModificationStamp(): return current modification stamp.",
            schema = DocumentSdkUrlArgs::class.jsonSchema,
            handler = { args, request -> documentGetModificationStampHandler(args, request) },
        )
        registerTool<DocumentSdkInsertArgs>(
            name = "document_insert_string",
            description = "Document.insertString(offset, text).",
            schema = DocumentSdkInsertArgs::class.jsonSchema,
            handler = { args, request -> documentInsertStringHandler(args, request) },
        )
        registerTool<DocumentSdkDeleteArgs>(
            name = "document_delete_string",
            description = "Document.deleteString(startOffset, endOffset).",
            schema = DocumentSdkDeleteArgs::class.jsonSchema,
            handler = { args, request -> documentDeleteStringHandler(args, request) },
        )
        registerTool<DocumentSdkReplaceArgs>(
            name = "document_replace_string",
            description = "Document.replaceString(startOffset, endOffset, text).",
            schema = DocumentSdkReplaceArgs::class.jsonSchema,
            handler = { args, request -> documentReplaceStringHandler(args, request) },
        )
        registerTool<DocumentSdkSetTextArgs>(
            name = "document_set_text",
            description = "Document.setText(text): replace whole document with new text.",
            schema = DocumentSdkSetTextArgs::class.jsonSchema,
            handler = { args, request -> documentSetTextHandler(args, request) },
        )

        return buildRegistration()
    }
}
