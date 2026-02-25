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
import kotlinx.serialization.Serializable

private const val VFS_URL_PARAM_DESCRIPTION =
    "Target VFS URL. Example: file:///absolute/path/to/file.txt"

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

    @McpTool
    @McpDescription("Document.getText(): return a copy of whole document text.")
    suspend fun document_get_text(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): DocumentTextResult {
        val (_, document) = resolveTextDocument(url)
        val text = readAction { document.text }
        return DocumentTextResult(text = text)
    }

    @McpTool
    @McpDescription("Document.getCharsSequence(): return current document chars sequence snapshot as text.")
    suspend fun document_get_chars_sequence(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): DocumentTextResult {
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
        val (_, document) = resolveTextDocument(url)
        validateRange(document, startOffset, endOffset)
        val text = readAction {
            document.getText(TextRange(startOffset, endOffset))
        }
        return DocumentTextResult(text = text)
    }

    @McpTool
    @McpDescription("Document.getTextLength(): return document text length.")
    suspend fun document_get_text_length(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): Int {
        val (_, document) = resolveTextDocument(url)
        return readAction { document.textLength }
    }

    @McpTool
    @McpDescription("Document.getLineCount(): return line count.")
    suspend fun document_get_line_count(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): Int {
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
        val (_, document) = resolveTextDocument(url)
        val textLength = readAction { document.textLength }
        validateOffset(offset, 0, textLength, "offset")
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
        val (_, document) = resolveTextDocument(url)
        return readAction { document.isWritable }
    }

    @McpTool
    @McpDescription("Document.getModificationStamp(): return current modification stamp.")
    suspend fun document_get_modification_stamp(
        @McpDescription(VFS_URL_PARAM_DESCRIPTION)
        url: String,
    ): Long {
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
        val (file, document) = resolveTextDocument(url)
        val textLength = readAction { document.textLength }
        validateOffset(offset, 0, textLength, "offset")
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
        validateOffset(startOffset, 0, textLength, "startOffset")
        validateOffset(endOffset, 0, textLength, "endOffset")
    }

    private suspend fun validateLine(document: Document, line: Int) {
        val lineCount = readAction { document.lineCount }
        if (lineCount <= 0) {
            mcpFail("Document has no lines.")
        }
        validateOffset(line, 0, lineCount - 1, "line")
    }

    private fun validateOffset(value: Int, min: Int, max: Int, name: String) {
        if (value !in min..max) {
            mcpFail("$name must be in [$min, $max], but was $value.")
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
