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
import dev.ghostflyby.mcp.sdk.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.schema.Schema
import kotlinx.serialization.serializer

internal class FileContentWriteTools {
    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.documentIsWritable(a: DocumentSdkUrlArgs): CallToolResult {
        val (_, document) = resolveTextDocumentForTool(a.url)
        val writable = readAction { document.isWritable }
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = toolArgsJson.encodeToString(
                        serializer<DocumentSdkWritableResult>(), DocumentSdkWritableResult(writable = writable),
                    ),
                ),
            ),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.documentGetModificationStamp(a: DocumentSdkUrlArgs): CallToolResult {
        val (_, document) = resolveTextDocumentForTool(a.url)
        val stamp = readAction { document.modificationStamp }
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = toolArgsJson.encodeToString(
                        serializer<DocumentSdkModificationStampResult>(),
                        DocumentSdkModificationStampResult(modificationStamp = stamp),
                    ),
                ),
            ),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.documentInsertString(a: DocumentSdkInsertArgs): CallToolResult {
        val project = call.project()
        val (file, document) = resolveTextDocumentForTool(a.url)
        readAction { document.textLength }
        validateToolRange(document, a.offset, a.offset)
        ensureToolWritable(file, document, a.url)
        backgroundWriteAction {
            document.insertString(a.offset, a.text)
            commitToolAndMaybeSave(project, document, a.saveAfterWrite)
        }
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = toolArgsJson.encodeToString(
                        serializer<DocumentSdkWriteResult>(), snapshotToolWriteResult(document),
                    ),
                ),
            ),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.documentDeleteString(a: DocumentSdkDeleteArgs): CallToolResult {
        val project = call.project()
        val (file, document) = resolveTextDocumentForTool(a.url)
        validateToolRange(document, a.startOffset, a.endOffset)
        ensureToolWritable(file, document, a.url)
        backgroundWriteAction {
            document.deleteString(a.startOffset, a.endOffset)
            commitToolAndMaybeSave(project, document, a.saveAfterWrite)
        }
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = toolArgsJson.encodeToString(
                        serializer<DocumentSdkWriteResult>(), snapshotToolWriteResult(document),
                    ),
                ),
            ),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.documentReplaceString(a: DocumentSdkReplaceArgs): CallToolResult {
        val project = call.project()
        val (file, document) = resolveTextDocumentForTool(a.url)
        validateToolRange(document, a.startOffset, a.endOffset)
        ensureToolWritable(file, document, a.url)
        backgroundWriteAction {
            document.replaceString(a.startOffset, a.endOffset, a.text)
            commitToolAndMaybeSave(project, document, a.saveAfterWrite)
        }
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = toolArgsJson.encodeToString(
                        serializer<DocumentSdkWriteResult>(), snapshotToolWriteResult(document),
                    ),
                ),
            ),
        )
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.documentSetText(a: DocumentSdkSetTextArgs): CallToolResult {
        val project = call.project()
        val (file, document) = resolveTextDocumentForTool(a.url)
        ensureToolWritable(file, document, a.url)
        backgroundWriteAction {
            document.setText(a.text)
            commitToolAndMaybeSave(project, document, a.saveAfterWrite)
        }
        return CallToolResult(
            content = listOf(
                TextContent(
                    text = toolArgsJson.encodeToString(
                        serializer<DocumentSdkWriteResult>(), snapshotToolWriteResult(document),
                    ),
                ),
            ),
        )
    }
}