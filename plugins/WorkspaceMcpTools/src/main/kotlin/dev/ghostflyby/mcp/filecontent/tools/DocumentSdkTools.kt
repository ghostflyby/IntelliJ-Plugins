/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent.tools

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import kotlinx.schema.Schema
import dev.ghostflyby.mcp.common.WorkspaceResourceException
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

internal suspend fun resolveTextDocumentForTool(url: String): Pair<VirtualFile, Document> = readAction {
    val vfsManager = service<VirtualFileManager>()
    val file = vfsManager.findFileByUrl(url) ?: throwToolError("File not found for URL: $url")
    if (file.isDirectory) {
        throwToolError("URL points to a directory, not a file: $url")
    }
    val document = FileDocumentManager.getInstance().getDocument(file)
        ?: throwToolError("File at URL \u0027$url\u0027 is binary or has no text document.")
    file to document
}

internal suspend fun validateToolRange(document: Document, startOffset: Int, endOffset: Int) {
    if (startOffset > endOffset) {
        throwToolError("startOffset must be <= endOffset.")
    }
    val textLength = readAction { document.textLength }
    validateToolOffset(startOffset, textLength, "startOffset")
    validateToolOffset(endOffset, textLength, "endOffset")
}

internal fun validateToolOffset(value: Int, max: Int, name: String) {
    if (value !in 0..max) {
        throwToolError("$name must be in [0, $max], but was $value.")
    }
}

internal suspend fun ensureToolWritable(file: VirtualFile, document: Document, url: String) {
    val writable = readAction { file.isWritable && document.isWritable }
    if (!writable) {
        throwToolError("Document is not writable: $url")
    }
}

internal suspend fun snapshotToolWriteResult(document: Document): DocumentSdkWriteResult {
    val snapshot = readAction {
        Triple(document.textLength, document.lineCount, document.modificationStamp)
    }
    return DocumentSdkWriteResult(
        textLength = snapshot.first,
        lineCount = snapshot.second,
        modificationStamp = snapshot.third,
    )
}

internal fun commitDocument(
    project: Project,
    document: Document,
) {
    val psiDocumentManager = PsiDocumentManager.getInstance(project)
    psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)
    psiDocumentManager.commitDocument(document)
}

internal fun throwToolError(message: String): Nothing {
    throw WorkspaceResourceException(message)
}
