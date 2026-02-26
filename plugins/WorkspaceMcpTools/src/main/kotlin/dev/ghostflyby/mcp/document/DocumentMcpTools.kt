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

package dev.ghostflyby.mcp.document

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.VFS_URL_PARAM_DESCRIPTION
import dev.ghostflyby.mcp.batchTry
import dev.ghostflyby.mcp.reportActivity
import kotlinx.serialization.Serializable

@Suppress("FunctionName")
internal class DocumentMcpTools : McpToolset {
    private val vfsManager: VirtualFileManager
        get() = service<VirtualFileManager>()

    @Serializable
    data class DocumentTextResult(
        val text: String,
    )

    @Serializable
    data class DocumentWriteResult(
        val textLength: Int,
        val lineCount: Int,
        val modificationStamp: Long,
    )

    @Serializable
    data class DocumentTextRangeRequest(
        val url: String,
        val startOffset: Int,
        val endOffset: Int,
    )

    @Serializable
    data class DocumentBatchTextResultItem(
        val input: String,
        val text: String? = null,
        val error: String? = null,
    )

    @Serializable
    data class DocumentBatchTextRangeResultItem(
        val request: DocumentTextRangeRequest,
        val text: String? = null,
        val error: String? = null,
    )

    @Serializable
    data class DocumentBatchTextResult(
        val items: List<DocumentBatchTextResultItem>,
        val successCount: Int,
        val failureCount: Int,
    )

    @Serializable
    data class DocumentBatchTextRangeResult(
        val items: List<DocumentBatchTextRangeResultItem>,
        val successCount: Int,
        val failureCount: Int,
    )

    @McpTool
    @McpDescription("Document.getText(): return a copy of whole document text.")
    suspend fun document_get_text(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): DocumentTextResult {
        reportActivity(Bundle.message("tool.activity.document.get.text", url))
        return DocumentTextResult(text = readDocumentText(url))
    }

    @McpTool
    @McpDescription("Batch Document.getText(): return copies of whole document text.")
    suspend fun document_get_texts(
        @McpDescription("Text document URLs to read.")
        urls: List<String>,
        @McpDescription("Whether to continue collecting results after a single URL fails.")
        continueOnError: Boolean = true,
    ): DocumentBatchTextResult {
        reportActivity(Bundle.message("tool.activity.document.get.texts", urls.size, continueOnError))
        val items = mutableListOf<DocumentBatchTextResultItem>()
        var successCount = 0
        var failureCount = 0
        for (url in urls) {
            val output = batchTry(continueOnError) {
                readDocumentText(url)
            }
            if (output.error == null) {
                successCount++
            } else {
                failureCount++
            }
            items += DocumentBatchTextResultItem(
                input = url,
                text = output.value,
                error = output.error,
            )
        }
        return DocumentBatchTextResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
    }

    @McpTool
    @McpDescription("Document.getCharsSequence(): return current document chars sequence snapshot as text.")
    suspend fun document_get_chars_sequence(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): DocumentTextResult {
        reportActivity(Bundle.message("tool.activity.document.get.chars.sequence", url))
        val (_, document) = resolveTextDocument(url)
        val chars = readAction { document.charsSequence }
        return DocumentTextResult(text = chars.toString())
    }

    @McpTool
    @McpDescription("Document.getImmutableCharSequence(): return immutable chars sequence as text.")
    suspend fun document_get_immutable_char_sequence(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): DocumentTextResult {
        reportActivity(Bundle.message("tool.activity.document.get.immutable.chars.sequence", url))
        val (_, document) = resolveTextDocument(url)
        val chars = readAction { document.immutableCharSequence }
        return DocumentTextResult(text = chars.toString())
    }

    @McpTool
    @McpDescription("Document.getText(TextRange): return text in [startOffset, endOffset).")
    suspend fun document_get_text_range(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Range start offset (inclusive).")
        startOffset: Int,
        @McpDescription("Range end offset (exclusive).")
        endOffset: Int,
    ): DocumentTextResult {
        reportActivity(Bundle.message("tool.activity.document.get.text.range", startOffset, endOffset, url))
        return DocumentTextResult(text = readDocumentTextRange(url, startOffset, endOffset))
    }

    @McpTool
    @McpDescription("Batch Document.getText(TextRange): return texts in [startOffset, endOffset).")
    suspend fun document_get_text_ranges(
        @McpDescription("Text range requests.")
        requests: List<DocumentTextRangeRequest>,
        @McpDescription("Whether to continue collecting results after a single request fails.")
        continueOnError: Boolean = true,
    ): DocumentBatchTextRangeResult {
        reportActivity(Bundle.message("tool.activity.document.get.text.ranges", requests.size, continueOnError))
        val items = mutableListOf<DocumentBatchTextRangeResultItem>()
        var successCount = 0
        var failureCount = 0
        for (request in requests) {
            val output = batchTry(continueOnError) {
                readDocumentTextRange(
                    url = request.url,
                    startOffset = request.startOffset,
                    endOffset = request.endOffset,
                )
            }
            if (output.error == null) {
                successCount++
            } else {
                failureCount++
            }
            items += DocumentBatchTextRangeResultItem(
                request = request,
                text = output.value,
                error = output.error,
            )
        }
        return DocumentBatchTextRangeResult(
            items = items,
            successCount = successCount,
            failureCount = failureCount,
        )
    }

    @McpTool
    @McpDescription("Document.getTextLength(): return document text length.")
    suspend fun document_get_text_length(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): Int {
        reportActivity(Bundle.message("tool.activity.document.get.text.length", url))
        val (_, document) = resolveTextDocument(url)
        return readAction { document.textLength }
    }

    @McpTool
    @McpDescription("Document.getLineCount(): return line count.")
    suspend fun document_get_line_count(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): Int {
        reportActivity(Bundle.message("tool.activity.document.get.line.count", url))
        val (_, document) = resolveTextDocument(url)
        return readAction { document.lineCount }
    }

    @McpTool
    @McpDescription("Document.getLineNumber(offset): return line number (0-based) for an offset.")
    suspend fun document_get_line_number(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Offset in [0, textLength].")
        offset: Int,
    ): Int {
        reportActivity(Bundle.message("tool.activity.document.get.line.number", offset, url))
        val (_, document) = resolveTextDocument(url)
        val textLength = readAction { document.textLength }
        validateOffset(offset, textLength, "offset")
        return readAction { document.getLineNumber(offset) }
    }

    @McpTool
    @McpDescription("Document.getLineStartOffset(line): return start offset of a line.")
    suspend fun document_get_line_start_offset(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Line number in [0, lineCount-1].")
        line: Int,
    ): Int {
        reportActivity(Bundle.message("tool.activity.document.get.line.start.offset", line, url))
        val (_, document) = resolveTextDocument(url)
        validateLine(document, line)
        return readAction { document.getLineStartOffset(line) }
    }

    @McpTool
    @McpDescription("Document.getLineEndOffset(line): return end offset of a line.")
    suspend fun document_get_line_end_offset(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Line number in [0, lineCount-1].")
        line: Int,
    ): Int {
        reportActivity(Bundle.message("tool.activity.document.get.line.end.offset", line, url))
        val (_, document) = resolveTextDocument(url)
        validateLine(document, line)
        return readAction { document.getLineEndOffset(line) }
    }

    @McpTool
    @McpDescription("Document.getLineSeparatorLength(line): return line separator length.")
    suspend fun document_get_line_separator_length(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Line number in [0, lineCount-1].")
        line: Int,
    ): Int {
        reportActivity(Bundle.message("tool.activity.document.get.line.separator.length", line, url))
        val (_, document) = resolveTextDocument(url)
        validateLine(document, line)
        return readAction { document.getLineSeparatorLength(line) }
    }

    @McpTool
    @McpDescription("Document.isLineModified(line): return whether a line has unsaved modifications.")
    suspend fun document_is_line_modified(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Line number in [0, lineCount-1].")
        line: Int,
    ): Boolean {
        reportActivity(Bundle.message("tool.activity.document.is.line.modified", line, url))
        val (_, document) = resolveTextDocument(url)
        validateLine(document, line)
        return readAction { document.isLineModified(line) }
    }

    @McpTool
    @McpDescription("Document.isWritable(): return whether document text is writable.")
    suspend fun document_is_writable(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): Boolean {
        reportActivity(Bundle.message("tool.activity.document.is.writable", url))
        val (_, document) = resolveTextDocument(url)
        return readAction { document.isWritable }
    }

    @McpTool
    @McpDescription("Document.getModificationStamp(): return current modification stamp.")
    suspend fun document_get_modification_stamp(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): Long {
        reportActivity(Bundle.message("tool.activity.document.get.modification.stamp", url))
        val (_, document) = resolveTextDocument(url)
        return readAction { document.modificationStamp }
    }

    @McpTool
    @McpDescription("Document.insertString(offset, text).")
    suspend fun document_insert_string(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Insert offset in [0, textLength].")
        offset: Int,
        @McpDescription("Text to insert.")
        text: String,
        @McpDescription("Save document after write.")
        saveAfterWrite: Boolean = false,
    ): DocumentWriteResult {
        reportActivity(Bundle.message("tool.activity.document.insert.string", text.length, offset, url))
        val (file, document) = resolveTextDocument(url)
        val textLength = readAction { document.textLength }
        validateOffset(offset, textLength, "offset")
        ensureWritable(file, document, url)
        backgroundWriteAction {
            document.insertString(offset, text)
            if (saveAfterWrite) {
                FileDocumentManager.getInstance().saveDocument(document)
            }
        }
        return snapshotWriteResult(document)
    }

    @McpTool
    @McpDescription("Document.deleteString(startOffset, endOffset).")
    suspend fun document_delete_string(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Delete range start (inclusive).")
        startOffset: Int,
        @McpDescription("Delete range end (exclusive).")
        endOffset: Int,
        @McpDescription("Save document after write.")
        saveAfterWrite: Boolean = false,
    ): DocumentWriteResult {
        reportActivity(Bundle.message("tool.activity.document.delete.string", startOffset, endOffset, url))
        val (file, document) = resolveTextDocument(url)
        validateRange(document, startOffset, endOffset)
        ensureWritable(file, document, url)
        backgroundWriteAction {
            document.deleteString(startOffset, endOffset)
            if (saveAfterWrite) {
                FileDocumentManager.getInstance().saveDocument(document)
            }
        }
        return snapshotWriteResult(document)
    }

    @McpTool
    @McpDescription("Document.replaceString(startOffset, endOffset, text).")
    suspend fun document_replace_string(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("Replace range start (inclusive).")
        startOffset: Int,
        @McpDescription("Replace range end (exclusive).")
        endOffset: Int,
        @McpDescription("Text to replace with.")
        text: String,
        @McpDescription("Save document after write.")
        saveAfterWrite: Boolean = false,
    ): DocumentWriteResult {
        reportActivity(Bundle.message("tool.activity.document.replace.string", startOffset, endOffset, text.length, url))
        val (file, document) = resolveTextDocument(url)
        validateRange(document, startOffset, endOffset)
        ensureWritable(file, document, url)
        backgroundWriteAction {
            document.replaceString(startOffset, endOffset, text)
            if (saveAfterWrite) {
                FileDocumentManager.getInstance().saveDocument(document)
            }
        }
        return snapshotWriteResult(document)
    }

    @McpTool
    @McpDescription("Document.setText(text): replace whole document with new text.")
    suspend fun document_set_text(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
        @McpDescription("New whole text.")
        text: String,
        @McpDescription("Save document after write.")
        saveAfterWrite: Boolean = false,
    ): DocumentWriteResult {
        reportActivity(Bundle.message("tool.activity.document.set.text", text.length, url))
        val (file, document) = resolveTextDocument(url)
        ensureWritable(file, document, url)
        backgroundWriteAction {
            document.setText(text)
            if (saveAfterWrite) {
                FileDocumentManager.getInstance().saveDocument(document)
            }
        }
        return snapshotWriteResult(document)
    }

    private suspend fun readDocumentText(url: String): String {
        val (_, document) = resolveTextDocument(url)
        return readAction { document.text }
    }

    private suspend fun readDocumentTextRange(
        url: String,
        startOffset: Int,
        endOffset: Int,
    ): String {
        val (_, document) = resolveTextDocument(url)
        validateRange(document, startOffset, endOffset)
        return readAction {
            document.getText(TextRange(startOffset, endOffset))
        }
    }

    private suspend fun resolveTextDocument(url: String): Pair<VirtualFile, Document> = readAction {
        val file = vfsManager.findFileByUrl(url) ?: mcpFail("File not found for URL: $url")
        if (file.isDirectory) {
            mcpFail("URL points to a directory, not a file: $url")
        }
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: mcpFail("File at URL '$url' is binary or has no text document.")
        file to document
    }

    private suspend fun validateRange(document: Document, startOffset: Int, endOffset: Int) {
        val textLength = readAction { document.textLength }
        if (startOffset > endOffset) {
            mcpFail("startOffset must be <= endOffset.")
        }
        validateOffset(startOffset, textLength, "startOffset")
        validateOffset(endOffset, textLength, "endOffset")
    }

    private suspend fun validateLine(document: Document, line: Int) {
        val lineCount = readAction { document.lineCount }
        if (lineCount <= 0) {
            mcpFail("Document has no lines.")
        }
        validateOffset(line, lineCount - 1, "line")
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

    private suspend fun snapshotWriteResult(document: Document): DocumentWriteResult {
        val snapshot = readAction {
            Triple(document.textLength, document.lineCount, document.modificationStamp)
        }
        return DocumentWriteResult(
            textLength = snapshot.first,
            lineCount = snapshot.second,
            modificationStamp = snapshot.third,
        )
    }

}
