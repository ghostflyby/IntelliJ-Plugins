/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.document.tools

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import dev.ghostflyby.mcp.common.VFS_URL_PARAM_DESCRIPTION
import dev.ghostflyby.mcp.common.WorkspaceResourceException
import dev.ghostflyby.mcp.sdk.callToolWithProject
import dev.ghostflyby.mcp.sdk.tools.WorkspaceMcpProjectToolArguments
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable

/**
 * Typed result for document write operations.
 */
@Schema
@Serializable
internal data class DocumentSdkWriteResult(
    val textLength: Int,
    val lineCount: Int,
    val modificationStamp: Long,
)

/**
 * Typed result for document_is_writable.
 */
@Schema
@Serializable
internal data class DocumentSdkWritableResult(
    val writable: Boolean,
)

/**
 * Typed result for document_get_modification_stamp.
 */
@Schema
@Serializable
internal data class DocumentSdkModificationStampResult(
    val modificationStamp: Long,
)

// -- single-URL argument DTOs --

@Schema
@Serializable
internal data class DocumentSdkUrlArgs(
    @Description("Target VFS URL. $VFS_URL_PARAM_DESCRIPTION")
    val url: String,
    @Description("Stable project key for project-scoped resolution (optional).")
    override val projectKey: String? = null,
    @Description("Absolute project base path for project-scoped resolution (optional).")
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Schema
@Serializable
internal data class DocumentSdkInsertArgs(
    @Description("Target VFS URL. $VFS_URL_PARAM_DESCRIPTION")
    val url: String,
    @Description("Insert offset in [0, textLength].")
    val offset: Int,
    @Description("Text to insert.")
    val text: String,
    @Description("Save document after write (default false).")
    val saveAfterWrite: Boolean = false,
    @Description("Stable project key for project-scoped resolution (optional).")
    override val projectKey: String? = null,
    @Description("Absolute project base path for project-scoped resolution (optional).")
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Schema
@Serializable
internal data class DocumentSdkDeleteArgs(
    @Description("Target VFS URL. $VFS_URL_PARAM_DESCRIPTION")
    val url: String,
    @Description("Delete range start (inclusive).")
    val startOffset: Int,
    @Description("Delete range end (exclusive).")
    val endOffset: Int,
    @Description("Save document after write (default false).")
    val saveAfterWrite: Boolean = false,
    @Description("Stable project key for project-scoped resolution (optional).")
    override val projectKey: String? = null,
    @Description("Absolute project base path for project-scoped resolution (optional).")
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Schema
@Serializable
internal data class DocumentSdkReplaceArgs(
    @Description("Target VFS URL. $VFS_URL_PARAM_DESCRIPTION")
    val url: String,
    @Description("Replace range start (inclusive).")
    val startOffset: Int,
    @Description("Replace range end (exclusive).")
    val endOffset: Int,
    @Description("Text to replace with.")
    val text: String,
    @Description("Save document after write (default false).")
    val saveAfterWrite: Boolean = false,
    @Description("Stable project key for project-scoped resolution (optional).")
    override val projectKey: String? = null,
    @Description("Absolute project base path for project-scoped resolution (optional).")
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Schema
@Serializable
internal data class DocumentSdkSetTextArgs(
    @Description("Target VFS URL. $VFS_URL_PARAM_DESCRIPTION")
    val url: String,
    @Description("New whole text.")
    val text: String,
    @Description("Save document after write (default false).")
    val saveAfterWrite: Boolean = false,
    @Description("Stable project key for project-scoped resolution (optional).")
    override val projectKey: String? = null,
    @Description("Absolute project base path for project-scoped resolution (optional).")
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

internal suspend fun ClientConnection.documentIsWritableHandler(args: DocumentSdkUrlArgs, request: CallToolRequest): CallToolResult {
    return callToolWithProject(
        sessionId = this.sessionId,
        projectArgs = args,
        vfsUrl = args.url,
    ) { project ->
        val (_, document) = resolveTextDocument(args.url)
        val writable = readAction { document.isWritable }
        CallToolResult(
            content = listOf(TextContent(text = toolArgsJson.encodeToString(DocumentSdkWritableResult(writable = writable)))),
        )
    }
}

internal suspend fun ClientConnection.documentGetModificationStampHandler(args: DocumentSdkUrlArgs, request: CallToolRequest): CallToolResult {
    return callToolWithProject(
        sessionId = this.sessionId,
        projectArgs = args,
        vfsUrl = args.url,
    ) { project ->
        val (_, document) = resolveTextDocument(args.url)
        val stamp = readAction { document.modificationStamp }
        CallToolResult(
            content = listOf(TextContent(text = toolArgsJson.encodeToString(DocumentSdkModificationStampResult(modificationStamp = stamp)))),
        )
    }
}

internal suspend fun ClientConnection.documentInsertStringHandler(args: DocumentSdkInsertArgs, request: CallToolRequest): CallToolResult {
    return callToolWithProject(
        sessionId = this.sessionId,
        projectArgs = args,
        vfsUrl = args.url,
    ) { project ->
        val (file, document) = resolveTextDocument(args.url)
        val textLength = readAction { document.textLength }
        validateOffset(args.offset, textLength, "offset")
        ensureWritable(file, document, args.url)
        backgroundWriteAction {
            document.insertString(args.offset, args.text)
            commitAndMaybeSave(project, document, args.saveAfterWrite)
        }
        CallToolResult(
            content = listOf(TextContent(text = toolArgsJson.encodeToString(snapshotWriteResult(document)))),
        )
    }
}

internal suspend fun ClientConnection.documentDeleteStringHandler(args: DocumentSdkDeleteArgs, request: CallToolRequest): CallToolResult {
    return callToolWithProject(
        sessionId = this.sessionId,
        projectArgs = args,
        vfsUrl = args.url,
    ) { project ->
        val (file, document) = resolveTextDocument(args.url)
        validateRange(document, args.startOffset, args.endOffset)
        ensureWritable(file, document, args.url)
        backgroundWriteAction {
            document.deleteString(args.startOffset, args.endOffset)
            commitAndMaybeSave(project, document, args.saveAfterWrite)
        }
        CallToolResult(
            content = listOf(TextContent(text = toolArgsJson.encodeToString(snapshotWriteResult(document)))),
        )
    }
}

internal suspend fun ClientConnection.documentReplaceStringHandler(args: DocumentSdkReplaceArgs, request: CallToolRequest): CallToolResult {
    return callToolWithProject(
        sessionId = this.sessionId,
        projectArgs = args,
        vfsUrl = args.url,
    ) { project ->
        val (file, document) = resolveTextDocument(args.url)
        validateRange(document, args.startOffset, args.endOffset)
        ensureWritable(file, document, args.url)
        backgroundWriteAction {
            document.replaceString(args.startOffset, args.endOffset, args.text)
            commitAndMaybeSave(project, document, args.saveAfterWrite)
        }
        CallToolResult(
            content = listOf(TextContent(text = toolArgsJson.encodeToString(snapshotWriteResult(document)))),
        )
    }
}

internal suspend fun ClientConnection.documentSetTextHandler(args: DocumentSdkSetTextArgs, request: CallToolRequest): CallToolResult {
    return callToolWithProject(
        sessionId = this.sessionId,
        projectArgs = args,
        vfsUrl = args.url,
    ) { project ->
        val (file, document) = resolveTextDocument(args.url)
        ensureWritable(file, document, args.url)
        backgroundWriteAction {
            document.setText(args.text)
            commitAndMaybeSave(project, document, args.saveAfterWrite)
        }
        CallToolResult(
            content = listOf(TextContent(text = toolArgsJson.encodeToString(snapshotWriteResult(document)))),
        )
    }
}

private suspend fun resolveTextDocument(url: String): Pair<VirtualFile, Document> = readAction {
    val vfsManager = service<VirtualFileManager>()
    val file = vfsManager.findFileByUrl(url) ?: mcpFail("File not found for URL: $url")
    if (file.isDirectory) {
        mcpFail("URL points to a directory, not a file: $url")
    }
    val document = FileDocumentManager.getInstance().getDocument(file)
        ?: mcpFail("File at URL \u0027$url\u0027 is binary or has no text document.")
    file to document
}

private suspend fun validateRange(document: Document, startOffset: Int, endOffset: Int) {
    if (startOffset > endOffset) {
        mcpFail("startOffset must be <= endOffset.")
    }
    val textLength = readAction { document.textLength }
    validateOffset(startOffset, textLength, "startOffset")
    validateOffset(endOffset, textLength, "endOffset")
}

private fun validateOffset(value: Int, max: Int, name: String) {
    if (value !in 0..max) {
        mcpFail("$name must be in [0, $max], but was $value.")
    }
}

private suspend fun ensureWritable(file: VirtualFile, document: Document, url: String) {
    val writable = readAction { file.isWritable && document.isWritable }
    if (!writable) {
        mcpFail("Document is not writable: $url")
    }
}

private suspend fun snapshotWriteResult(document: Document): DocumentSdkWriteResult {
    val snapshot = readAction {
        Triple(document.textLength, document.lineCount, document.modificationStamp)
    }
    return DocumentSdkWriteResult(
        textLength = snapshot.first,
        lineCount = snapshot.second,
        modificationStamp = snapshot.third,
    )
}

private fun commitAndMaybeSave(
    project: Project,
    document: Document,
    saveAfterWrite: Boolean,
) {
    val psiDocumentManager = PsiDocumentManager.getInstance(project)
    psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)
    psiDocumentManager.commitDocument(document)
    if (saveAfterWrite) {
        FileDocumentManager.getInstance().saveDocument(document)
    }
}

private fun mcpFail(message: String): Nothing {
    throw WorkspaceResourceException(message)
}
