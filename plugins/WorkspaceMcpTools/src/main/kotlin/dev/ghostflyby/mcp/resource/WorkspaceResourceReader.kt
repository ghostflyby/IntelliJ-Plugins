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

package dev.ghostflyby.mcp.resource

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.isTooLargeForIntellijSense
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class WorkspaceResourceException(message: String) : RuntimeException(message)

internal enum class WorkspaceVfsResourceReadMode {
    AUTO,
    FULL_TEXT,
    CHAR_RANGE,
    LINE_RANGE,
    DIRECTORY_LISTING,
    FILE_STAT,
    API_SIGNATURE,
}

internal enum class WorkspaceDocumentResourceReadMode {
    FULL_TEXT,
    TEXT_RANGE,
    METADATA,
    LINE_METADATA,
}

internal data class WorkspaceVfsResourceReadOptions(
    val mode: WorkspaceVfsResourceReadMode = WorkspaceVfsResourceReadMode.AUTO,
    val startChar: Int = 0,
    val endCharExclusive: Int? = null,
    val startLine: Int = 1,
    val endLineInclusive: Int? = null,
    val clampOutOfBounds: Boolean = false,
    val symbolName: String? = null,
    val maxLines: Int = 120,
)

internal data class WorkspaceDocumentResourceReadOptions(
    val mode: WorkspaceDocumentResourceReadMode = WorkspaceDocumentResourceReadMode.FULL_TEXT,
    val startOffset: Int = 0,
    val endOffset: Int? = null,
    val line: Int = 0,
)

internal data class WorkspaceResourceTextContent(
    val uri: String,
    val mimeType: String,
    val text: String,
)

internal class WorkspaceResourceReader {
    private val vfsManager: VirtualFileManager
        get() = service<VirtualFileManager>()

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    internal suspend fun readWorkspaceResource(
        uri: String,
    ): WorkspaceResourceTextContent {
        val decoded = tryDecodeWorkspaceResourceUri(uri)
            ?: resourceFail("Unsupported Workspace MCP resource URI: $uri")
        return when (decoded.kind) {
            WorkspaceResourceKind.VFS -> readVfsResource(uri, decoded.rawVfsUrl)
            WorkspaceResourceKind.DOCUMENT -> readDocumentResource(uri, decoded.rawVfsUrl)
        }
    }

    internal suspend fun readVfsResource(
        resourceUri: String,
        rawVfsUrl: String = rawVfsUrlFromVfsResourceUri(resourceUri),
        options: WorkspaceVfsResourceReadOptions = WorkspaceVfsResourceReadOptions(),
    ): WorkspaceResourceTextContent {
        val file = resolveVfsFile(rawVfsUrl)
        return when (options.mode) {
            WorkspaceVfsResourceReadMode.AUTO -> {
                if (readAction { file.isDirectory }) {
                    directoryListingContent(resourceUri, file)
                } else {
                    vfsTextContent(resourceUri, rawVfsUrl, file, options.copy(mode = WorkspaceVfsResourceReadMode.FULL_TEXT))
                }
            }

            WorkspaceVfsResourceReadMode.FULL_TEXT,
            WorkspaceVfsResourceReadMode.CHAR_RANGE,
            WorkspaceVfsResourceReadMode.LINE_RANGE,
                -> vfsTextContent(resourceUri, rawVfsUrl, file, options)

            WorkspaceVfsResourceReadMode.DIRECTORY_LISTING -> directoryListingContent(resourceUri, file)
            WorkspaceVfsResourceReadMode.FILE_STAT -> fileStatContent(resourceUri, file)
            WorkspaceVfsResourceReadMode.API_SIGNATURE -> apiSignatureContent(resourceUri, rawVfsUrl, file, options)
        }
    }

    internal suspend fun readDocumentResource(
        resourceUri: String,
        rawVfsUrl: String = rawVfsUrlFromDocumentResourceUri(resourceUri),
        options: WorkspaceDocumentResourceReadOptions = WorkspaceDocumentResourceReadOptions(),
    ): WorkspaceResourceTextContent {
        val (_, document) = resolveTextDocument(rawVfsUrl)
        return when (options.mode) {
            WorkspaceDocumentResourceReadMode.FULL_TEXT -> WorkspaceResourceTextContent(
                uri = resourceUri,
                mimeType = TEXT_PLAIN_MIME_TYPE,
                text = readAction { document.text },
            )

            WorkspaceDocumentResourceReadMode.TEXT_RANGE -> {
                val endOffset = options.endOffset ?: readAction { document.textLength }
                validateDocumentRange(document, options.startOffset, endOffset)
                WorkspaceResourceTextContent(
                    uri = resourceUri,
                    mimeType = TEXT_PLAIN_MIME_TYPE,
                    text = readAction { document.getText(TextRange(options.startOffset, endOffset)) },
                )
            }

            WorkspaceDocumentResourceReadMode.METADATA -> documentMetadataContent(resourceUri, document)
            WorkspaceDocumentResourceReadMode.LINE_METADATA -> documentLineMetadataContent(resourceUri, document, options.line)
        }
    }

    private suspend fun vfsTextContent(
        resourceUri: String,
        rawVfsUrl: String,
        file: VirtualFile,
        options: WorkspaceVfsResourceReadOptions,
    ): WorkspaceResourceTextContent {
        ensureRegularFile(file, rawVfsUrl)
        val text = readAction { VfsUtil.loadText(file) }
        val lineStarts = computeLineStarts(text)
        val content = when (options.mode) {
            WorkspaceVfsResourceReadMode.FULL_TEXT,
            WorkspaceVfsResourceReadMode.AUTO,
                -> text

            WorkspaceVfsResourceReadMode.CHAR_RANGE -> charRange(text, options)
            WorkspaceVfsResourceReadMode.LINE_RANGE -> lineRange(text, lineStarts, options)
            WorkspaceVfsResourceReadMode.DIRECTORY_LISTING,
            WorkspaceVfsResourceReadMode.FILE_STAT,
            WorkspaceVfsResourceReadMode.API_SIGNATURE,
                -> resourceFail("VFS text content cannot be read with mode ${options.mode}.")
        }
        return WorkspaceResourceTextContent(
            uri = resourceUri,
            mimeType = readAction { file.fileType.name }.toTextMimeType(),
            text = content,
        )
    }

    private suspend fun directoryListingContent(
        resourceUri: String,
        file: VirtualFile,
    ): WorkspaceResourceTextContent {
        if (!readAction { file.isDirectory }) {
            resourceFail("Resource is not a directory: ${file.url}")
        }
        val listing = readAction {
            WorkspaceDirectoryListing(
                url = file.url,
                path = file.path,
                children = file.children.map(::toFileStat),
            )
        }
        return WorkspaceResourceTextContent(
            uri = resourceUri,
            mimeType = APPLICATION_JSON_MIME_TYPE,
            text = json.encodeToString(WorkspaceDirectoryListing.serializer(), listing),
        )
    }

    private suspend fun fileStatContent(
        resourceUri: String,
        file: VirtualFile,
    ): WorkspaceResourceTextContent {
        val stat = readAction { toFileStat(file) }
        return WorkspaceResourceTextContent(
            uri = resourceUri,
            mimeType = APPLICATION_JSON_MIME_TYPE,
            text = json.encodeToString(WorkspaceFileStat.serializer(), stat),
        )
    }

    private suspend fun apiSignatureContent(
        resourceUri: String,
        rawVfsUrl: String,
        file: VirtualFile,
        options: WorkspaceVfsResourceReadOptions,
    ): WorkspaceResourceTextContent {
        if (options.maxLines < 1) {
            resourceFail("maxLines must be >= 1.")
        }
        ensureRegularFile(file, rawVfsUrl)
        val fileType = readAction { file.fileType.name }
        val text = readAction { VfsUtil.loadText(file) }
        val signature = buildApiSignature(rawVfsUrl, fileType, text, options.symbolName, options.maxLines)
        return WorkspaceResourceTextContent(
            uri = resourceUri,
            mimeType = APPLICATION_JSON_MIME_TYPE,
            text = json.encodeToString(WorkspaceApiSignature.serializer(), signature),
        )
    }

    private suspend fun documentMetadataContent(
        resourceUri: String,
        document: Document,
    ): WorkspaceResourceTextContent {
        val metadata = readAction {
            WorkspaceDocumentMetadata(
                textLength = document.textLength,
                lineCount = document.lineCount,
                modificationStamp = document.modificationStamp,
                isWritable = document.isWritable,
            )
        }
        return WorkspaceResourceTextContent(
            uri = resourceUri,
            mimeType = APPLICATION_JSON_MIME_TYPE,
            text = json.encodeToString(WorkspaceDocumentMetadata.serializer(), metadata),
        )
    }

    private suspend fun documentLineMetadataContent(
        resourceUri: String,
        document: Document,
        line: Int,
    ): WorkspaceResourceTextContent {
        validateDocumentLine(document, line)
        val metadata = readAction {
            WorkspaceDocumentLineMetadata(
                line = line,
                startOffset = document.getLineStartOffset(line),
                endOffset = document.getLineEndOffset(line),
                separatorLength = document.getLineSeparatorLength(line),
                isModified = document.isLineModified(line),
            )
        }
        return WorkspaceResourceTextContent(
            uri = resourceUri,
            mimeType = APPLICATION_JSON_MIME_TYPE,
            text = json.encodeToString(WorkspaceDocumentLineMetadata.serializer(), metadata),
        )
    }

    private suspend fun resolveVfsFile(rawVfsUrl: String): VirtualFile = readAction {
        vfsManager.findFileByUrl(rawVfsUrl) ?: resourceFail("File not found for URL: $rawVfsUrl")
    }

    private suspend fun resolveTextDocument(rawVfsUrl: String): Pair<VirtualFile, Document> = readAction {
        val file = vfsManager.findFileByUrl(rawVfsUrl) ?: resourceFail("File not found for URL: $rawVfsUrl")
        if (file.isDirectory) {
            resourceFail("URL points to a directory, not a file: $rawVfsUrl")
        }
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: resourceFail("File at URL '$rawVfsUrl' is binary or has no text document.")
        file to document
    }

    private fun ensureRegularFile(file: VirtualFile, rawVfsUrl: String) {
        if (file.isDirectory) {
            resourceFail("URL points to a directory, not a file: $rawVfsUrl")
        }
    }

    private fun charRange(text: String, options: WorkspaceVfsResourceReadOptions): String {
        val requestedEnd = options.endCharExclusive ?: text.length
        val clampedStart = if (options.clampOutOfBounds) options.startChar.coerceIn(0, text.length) else options.startChar
        val rawEnd = if (options.clampOutOfBounds) requestedEnd.coerceIn(0, text.length) else requestedEnd
        val clampedEnd = if (options.clampOutOfBounds) maxOf(clampedStart, rawEnd) else rawEnd

        if (!options.clampOutOfBounds) {
            if (options.startChar < 0) resourceFail("startChar must be >= 0.")
            if (requestedEnd < options.startChar) resourceFail("endCharExclusive must be >= startChar.")
            if (options.startChar > text.length || requestedEnd > text.length) {
                resourceFail("Character range [${options.startChar}, $requestedEnd) is out of bounds for file length ${text.length}.")
            }
        }
        return text.substring(clampedStart, clampedEnd)
    }

    private fun lineRange(
        text: String,
        lineStarts: List<Int>,
        options: WorkspaceVfsResourceReadOptions,
    ): String {
        val totalLines = lineStarts.size
        val requestedEndLine = options.endLineInclusive ?: totalLines
        val clampedStartLine = if (options.clampOutOfBounds) options.startLine.coerceIn(1, totalLines) else options.startLine
        val rawEndLine = if (options.clampOutOfBounds) requestedEndLine.coerceIn(1, totalLines) else requestedEndLine
        val clampedEndLine = if (options.clampOutOfBounds) maxOf(clampedStartLine, rawEndLine) else rawEndLine

        if (!options.clampOutOfBounds) {
            if (options.startLine < 1) resourceFail("startLine must be >= 1.")
            if (requestedEndLine < options.startLine) resourceFail("endLineInclusive must be >= startLine.")
            if (options.startLine > totalLines || requestedEndLine > totalLines) {
                resourceFail("Line range [${options.startLine}, $requestedEndLine] is out of bounds for total lines $totalLines.")
            }
        }

        val startOffset = lineStarts[clampedStartLine - 1]
        val endOffset = if (clampedEndLine == totalLines) text.length else lineStarts[clampedEndLine]
        return text.substring(startOffset, endOffset)
    }

    private suspend fun validateDocumentRange(
        document: Document,
        startOffset: Int,
        endOffset: Int,
    ) {
        val textLength = readAction { document.textLength }
        if (startOffset > endOffset) {
            resourceFail("startOffset must be <= endOffset.")
        }
        validateOffset(startOffset, textLength, "startOffset")
        validateOffset(endOffset, textLength, "endOffset")
    }

    private suspend fun validateDocumentLine(document: Document, line: Int) {
        val lineCount = readAction { document.lineCount }
        if (lineCount <= 0) {
            resourceFail("Document has no lines.")
        }
        validateOffset(line, lineCount - 1, "line")
    }

    private fun validateOffset(value: Int, max: Int, name: String) {
        if (value !in 0..max) {
            resourceFail("$name must be in [0, $max], but was $value.")
        }
    }

    private fun toFileStat(file: VirtualFile): WorkspaceFileStat {
        return WorkspaceFileStat(
            name = file.name,
            url = file.url,
            path = file.path,
            isDirectory = file.isDirectory,
            length = file.length,
            lastModified = file.timeStamp,
            isTooLargeForIntellijSense = file.isTooLargeForIntellijSense(),
            isValid = file.isValid,
            isWritable = file.isWritable,
            fileType = file.fileType.name,
        )
    }

    private fun buildApiSignature(
        rawVfsUrl: String,
        fileType: String,
        text: String,
        symbolName: String?,
        maxLines: Int,
    ): WorkspaceApiSignature {
        val lines = text.split('\n')
        val importRegex = Regex("^\\s*import\\s+.+")
        val typeRegex = Regex("\\b(class|interface|enum|record|object|trait|struct)\\b")
        val memberRegex = Regex(
            "^\\s*(public|private|protected|internal|static|final|abstract|suspend|fun|def|func|val|var)\\b.*",
        )
        val normalizedSymbol = symbolName?.trim()?.takeIf { it.isNotEmpty() }
        val symbolRegex = normalizedSymbol?.let { Regex("\\b${Regex.escape(it)}\\b") }

        val imports = mutableListOf<String>()
        var typeDeclaration: String? = null
        val memberMatches = mutableListOf<WorkspaceApiSignatureMatch>()
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd()
            if (imports.size < MAX_IMPORTS_IN_SIGNATURE && importRegex.containsMatchIn(line)) {
                imports += line
            }
            if (typeDeclaration == null && typeRegex.containsMatchIn(line)) {
                typeDeclaration = line
            }
            if (normalizedSymbol != null) {
                if (symbolRegex?.containsMatchIn(line) == true && memberRegex.containsMatchIn(line)) {
                    memberMatches += WorkspaceApiSignatureMatch(line = index + 1, text = line)
                }
            } else if (memberMatches.size < MAX_MEMBERS_IN_SIGNATURE && memberRegex.containsMatchIn(line)) {
                memberMatches += WorkspaceApiSignatureMatch(line = index + 1, text = line)
            }
        }

        val previewStart = when {
            memberMatches.isNotEmpty() -> maxOf(1, memberMatches.first().line - SIGNATURE_PREVIEW_CONTEXT_LINES)
            else -> 1
        }
        val previewEnd = minOf(lines.size, previewStart + maxLines - 1)
        val preview = lines.subList(previewStart - 1, previewEnd).joinToString("\n")
        val diagnostics = buildList {
            if (imports.isEmpty()) add("No import statements found in previewed content.")
            if (typeDeclaration == null) add("No primary type declaration detected by heuristic parser.")
            if (normalizedSymbol != null && memberMatches.isEmpty()) {
                add("No member signature matched symbolName='$normalizedSymbol'.")
            }
        }
        return WorkspaceApiSignature(
            url = rawVfsUrl,
            fileType = fileType,
            totalLines = lines.size,
            imports = imports,
            typeDeclaration = typeDeclaration,
            matchedMembers = memberMatches,
            previewStartLine = previewStart,
            previewEndLineInclusive = previewEnd,
            preview = preview,
            diagnostics = diagnostics,
        )
    }

    private fun computeLineStarts(text: CharSequence): List<Int> {
        val starts = mutableListOf(0)
        text.forEachIndexed { index, c ->
            if (c == '\n') {
                starts += index + 1
            }
        }
        return starts
    }

    private fun String.toTextMimeType(): String {
        return when (this.lowercase()) {
            "kotlin" -> "text/x-kotlin"
            "java" -> "text/x-java-source"
            "xml" -> "application/xml"
            "json" -> APPLICATION_JSON_MIME_TYPE
            "markdown" -> "text/markdown"
            else -> TEXT_PLAIN_MIME_TYPE
        }
    }
}

@Serializable
private data class WorkspaceDirectoryListing(
    val url: String,
    val path: String,
    val children: List<WorkspaceFileStat>,
)

@Serializable
private data class WorkspaceFileStat(
    val name: String,
    val url: String,
    val path: String,
    val isDirectory: Boolean,
    val length: Long,
    val lastModified: Long,
    val isTooLargeForIntellijSense: Boolean,
    val isValid: Boolean,
    val isWritable: Boolean,
    val fileType: String,
)

@Serializable
private data class WorkspaceApiSignatureMatch(
    val line: Int,
    val text: String,
)

@Serializable
private data class WorkspaceApiSignature(
    val url: String,
    val fileType: String,
    val totalLines: Int,
    val imports: List<String>,
    val typeDeclaration: String? = null,
    val matchedMembers: List<WorkspaceApiSignatureMatch> = emptyList(),
    val previewStartLine: Int,
    val previewEndLineInclusive: Int,
    val preview: String,
    val diagnostics: List<String> = emptyList(),
)

@Serializable
private data class WorkspaceDocumentMetadata(
    val textLength: Int,
    val lineCount: Int,
    val modificationStamp: Long,
    val isWritable: Boolean,
)

@Serializable
private data class WorkspaceDocumentLineMetadata(
    val line: Int,
    val startOffset: Int,
    val endOffset: Int,
    val separatorLength: Int,
    val isModified: Boolean,
)

private const val TEXT_PLAIN_MIME_TYPE = "text/plain"
private const val APPLICATION_JSON_MIME_TYPE = "application/json"
private const val MAX_IMPORTS_IN_SIGNATURE = 200
private const val MAX_MEMBERS_IN_SIGNATURE = 20
private const val SIGNATURE_PREVIEW_CONTEXT_LINES = 10

private fun resourceFail(message: String): Nothing {
    throw WorkspaceResourceException(message)
}
