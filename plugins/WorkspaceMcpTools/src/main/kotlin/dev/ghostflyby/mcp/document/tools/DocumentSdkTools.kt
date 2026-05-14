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
import dev.ghostflyby.mcp.resource.WorkspaceResourceException
import dev.ghostflyby.mcp.sdk.tools.SdkToolDescriptor
import dev.ghostflyby.mcp.sdk.tools.SdkToolHandlerContext
import dev.ghostflyby.mcp.sdk.tools.WorkspaceMcpProjectToolArguments
import dev.ghostflyby.mcp.sdk.tools.sdkBooleanProperty
import dev.ghostflyby.mcp.sdk.tools.sdkIntegerProperty
import dev.ghostflyby.mcp.sdk.tools.sdkStringProperty
import dev.ghostflyby.mcp.sdk.tools.sdkToolDescriptor
import dev.ghostflyby.mcp.sdk.tools.toolSchema
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Typed result for document write operations.
 */
@Serializable
internal data class DocumentSdkWriteResult(
    val textLength: Int,
    val lineCount: Int,
    val modificationStamp: Long,
)

/**
 * Typed result for document_is_writable.
 */
@Serializable
internal data class DocumentSdkWritableResult(
    val writable: Boolean,
)

/**
 * Typed result for document_get_modification_stamp.
 */
@Serializable
internal data class DocumentSdkModificationStampResult(
    val modificationStamp: Long,
)

// -- single-URL argument DTOs --

@Serializable
internal data class DocumentSdkUrlArgs(
    val url: String,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Serializable
internal data class DocumentSdkInsertArgs(
    val url: String,
    val offset: Int,
    val text: String,
    val saveAfterWrite: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Serializable
internal data class DocumentSdkDeleteArgs(
    val url: String,
    val startOffset: Int,
    val endOffset: Int,
    val saveAfterWrite: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Serializable
internal data class DocumentSdkReplaceArgs(
    val url: String,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val saveAfterWrite: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

@Serializable
internal data class DocumentSdkSetTextArgs(
    val url: String,
    val text: String,
    val saveAfterWrite: Boolean = false,
    override val projectKey: String? = null,
    override val projectPath: String? = null,
) : WorkspaceMcpProjectToolArguments

// -- tool factory functions --

internal fun documentIsWritableSdkTool(): SdkToolDescriptor<DocumentSdkUrlArgs> {
    return sdkToolDescriptor<DocumentSdkUrlArgs>(
        name = "document_is_writable",
        description = "Document.isWritable(): return whether document text is writable.",
        inputSchema = toolSchema(
            properties = mapOf(
                "url" to sdkStringProperty("Target VFS URL. $VFS_URL_PARAM_DESCRIPTION"),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("url"),
        ),
        handler = { args -> documentIsWritableHandler(this, args) },
    )
}

internal fun documentGetModificationStampSdkTool(): SdkToolDescriptor<DocumentSdkUrlArgs> {
    return sdkToolDescriptor<DocumentSdkUrlArgs>(
        name = "document_get_modification_stamp",
        description = "Document.getModificationStamp(): return current modification stamp.",
        inputSchema = toolSchema(
            properties = mapOf(
                "url" to sdkStringProperty("Target VFS URL. $VFS_URL_PARAM_DESCRIPTION"),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("url"),
        ),
        handler = { args -> documentGetModificationStampHandler(this, args) },
    )
}

internal fun documentInsertStringSdkTool(): SdkToolDescriptor<DocumentSdkInsertArgs> {
    return sdkToolDescriptor<DocumentSdkInsertArgs>(
        name = "document_insert_string",
        description = "Document.insertString(offset, text).",
        inputSchema = toolSchema(
            properties = mapOf(
                "url" to sdkStringProperty("Target VFS URL. $VFS_URL_PARAM_DESCRIPTION"),
                "offset" to sdkIntegerProperty("Insert offset in [0, textLength]."),
                "text" to sdkStringProperty("Text to insert."),
                "saveAfterWrite" to sdkBooleanProperty("Save document after write (default false)."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("url", "offset", "text"),
        ),
        handler = { args -> documentInsertStringHandler(this, args) },
    )
}

internal fun documentDeleteStringSdkTool(): SdkToolDescriptor<DocumentSdkDeleteArgs> {
    return sdkToolDescriptor<DocumentSdkDeleteArgs>(
        name = "document_delete_string",
        description = "Document.deleteString(startOffset, endOffset).",
        inputSchema = toolSchema(
            properties = mapOf(
                "url" to sdkStringProperty("Target VFS URL. $VFS_URL_PARAM_DESCRIPTION"),
                "startOffset" to sdkIntegerProperty("Delete range start (inclusive)."),
                "endOffset" to sdkIntegerProperty("Delete range end (exclusive)."),
                "saveAfterWrite" to sdkBooleanProperty("Save document after write (default false)."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("url", "startOffset", "endOffset"),
        ),
        handler = { args -> documentDeleteStringHandler(this, args) },
    )
}

internal fun documentReplaceStringSdkTool(): SdkToolDescriptor<DocumentSdkReplaceArgs> {
    return sdkToolDescriptor<DocumentSdkReplaceArgs>(
        name = "document_replace_string",
        description = "Document.replaceString(startOffset, endOffset, text).",
        inputSchema = toolSchema(
            properties = mapOf(
                "url" to sdkStringProperty("Target VFS URL. $VFS_URL_PARAM_DESCRIPTION"),
                "startOffset" to sdkIntegerProperty("Replace range start (inclusive)."),
                "endOffset" to sdkIntegerProperty("Replace range end (exclusive)."),
                "text" to sdkStringProperty("Text to replace with."),
                "saveAfterWrite" to sdkBooleanProperty("Save document after write (default false)."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("url", "startOffset", "endOffset", "text"),
        ),
        handler = { args -> documentReplaceStringHandler(this, args) },
    )
}

internal fun documentSetTextSdkTool(): SdkToolDescriptor<DocumentSdkSetTextArgs> {
    return sdkToolDescriptor<DocumentSdkSetTextArgs>(
        name = "document_set_text",
        description = "Document.setText(text): replace whole document with new text.",
        inputSchema = toolSchema(
            properties = mapOf(
                "url" to sdkStringProperty("Target VFS URL. $VFS_URL_PARAM_DESCRIPTION"),
                "text" to sdkStringProperty("New whole text."),
                "saveAfterWrite" to sdkBooleanProperty("Save document after write (default false)."),
                "projectKey" to sdkStringProperty("Stable project key for project-scoped resolution (optional)."),
                "projectPath" to sdkStringProperty("Absolute project base path for project-scoped resolution (optional)."),
            ),
            required = listOf("url", "text"),
        ),
        handler = { args -> documentSetTextHandler(this, args) },
    )
}

// -- handlers --

private suspend fun documentIsWritableHandler(ctx: SdkToolHandlerContext, args: DocumentSdkUrlArgs): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.url,
    ) { project ->
        val (_, document) = resolveTextDocument(args.url)
        val writable = readAction { document.isWritable }
        CallToolResult(
            content = listOf(TextContent(text = toolArgsJson.encodeToString(DocumentSdkWritableResult(writable = writable)))),
        )
    }
}

private suspend fun documentGetModificationStampHandler(ctx: SdkToolHandlerContext, args: DocumentSdkUrlArgs): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.url,
    ) { project ->
        val (_, document) = resolveTextDocument(args.url)
        val stamp = readAction { document.modificationStamp }
        CallToolResult(
            content = listOf(TextContent(text = toolArgsJson.encodeToString(DocumentSdkModificationStampResult(modificationStamp = stamp)))),
        )
    }
}

private suspend fun documentInsertStringHandler(ctx: SdkToolHandlerContext, args: DocumentSdkInsertArgs): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.url,
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

private suspend fun documentDeleteStringHandler(ctx: SdkToolHandlerContext, args: DocumentSdkDeleteArgs): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.url,
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

private suspend fun documentReplaceStringHandler(ctx: SdkToolHandlerContext, args: DocumentSdkReplaceArgs): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.url,
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

private suspend fun documentSetTextHandler(ctx: SdkToolHandlerContext, args: DocumentSdkSetTextArgs): CallToolResult {
    return ctx.runner.callToolWithProject(
        projectArgs = args,
        sessionId = ctx.sessionId,
        rawVfsUrl = args.url,
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

// -- helpers --

private suspend fun resolveTextDocument(url: String): Pair<VirtualFile, Document> = readAction {
    val vfsManager = service<VirtualFileManager>()
    val file = vfsManager.findFileByUrl(url) ?: mcpFail("File not found for URL: $url")
    if (file.isDirectory) {
        mcpFail("URL points to a directory, not a file: $url")
    }
    val document = FileDocumentManager.getInstance().getDocument(file)
        ?: mcpFail("File at URL '$url' is binary or has no text document.")
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
