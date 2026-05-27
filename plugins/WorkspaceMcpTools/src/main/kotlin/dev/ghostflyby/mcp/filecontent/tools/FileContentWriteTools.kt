/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent.tools

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import dev.ghostflyby.mcp.route.McpCallContext
import dev.ghostflyby.mcp.route.project
import kotlinx.schema.Description
import kotlinx.schema.Schema

internal class FileContentWriteTools {
    @Schema
    internal suspend fun McpCallContext<*>.documentInsertString(
        @Description("VFS URL to target file")
        url: String,
        @Description("Insert offset in [0, textLength]")
        offset: Int,
        @Description("Text to insert")
        text: String,
    ): DocumentSdkWriteResult {
        val project = call.project()
        val (file, document) = resolveTextDocumentForTool(url)
        readAction { document.textLength }
        validateToolRange(document, offset, offset)
        ensureToolWritable(file, document, url)
        backgroundWriteAction {
            document.insertString(offset, text)
            commitDocument(project, document)
        }
        return snapshotToolWriteResult(document)
    }

    @Schema
    internal suspend fun McpCallContext<*>.documentDeleteString(
        @Description("VFS URL to target file")
        url: String,
        @Description("Delete range start (inclusive)")
        startOffset: Int,
        @Description("Delete range end (exclusive)")
        endOffset: Int,
    ): DocumentSdkWriteResult {
        val project = call.project()
        val (file, document) = resolveTextDocumentForTool(url)
        validateToolRange(document, startOffset, endOffset)
        ensureToolWritable(file, document, url)
        backgroundWriteAction {
            document.deleteString(startOffset, endOffset)
            commitDocument(project, document)
        }
        return snapshotToolWriteResult(document)
    }

    @Schema
    internal suspend fun McpCallContext<*>.documentReplaceString(
        @Description("VFS URL to target file")
        url: String,
        @Description("Replace range start (inclusive)")
        startOffset: Int,
        @Description("Replace range end (exclusive)")
        endOffset: Int,
        @Description("Text to replace with")
        text: String,
    ): DocumentSdkWriteResult {
        val project = call.project()
        val (file, document) = resolveTextDocumentForTool(url)
        validateToolRange(document, startOffset, endOffset)
        ensureToolWritable(file, document, url)
        backgroundWriteAction {
            document.replaceString(startOffset, endOffset, text)
            commitDocument(project, document)
        }
        return snapshotToolWriteResult(document)
    }

    @Schema
    internal suspend fun McpCallContext<*>.documentSetText(
        @Description("VFS URL to target file")
        url: String,
        @Description("New whole text")
        text: String,
    ): DocumentSdkWriteResult {
        val project = call.project()
        val (file, document) = resolveTextDocumentForTool(url)
        ensureToolWritable(file, document, url)
        backgroundWriteAction {
            document.setText(text)
            commitDocument(project, document)
        }
        return snapshotToolWriteResult(document)
    }
}
