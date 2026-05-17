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

package dev.ghostflyby.mcp.document.resources

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mcp.resource.validateProjectRelativePath
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver

internal class DocumentResourceReadException(message: String) : RuntimeException(message)

/**
 * Resolve a raw VFS URL to a [VirtualFile] and its [Document] under read action.
 * Validates the URL points to a regular (non-directory) file with an open text document.
 */
internal suspend fun resolveTextDocument(rawVfsUrl: String): Pair<VirtualFile, Document> {
    val vfsManager = service<VirtualFileManager>()
    return readAction {
        val file = vfsManager.findFileByUrl(rawVfsUrl)
            ?: resourceFail("File not found for URL: $rawVfsUrl")
        if (file.isDirectory) resourceFail("URL points to a directory: $rawVfsUrl")
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: resourceFail("No text document for: $rawVfsUrl")
        file to document
    }
}

/**
 * Read a document by raw VFS URL, returning its text content.
 */
internal suspend fun readDocumentByVfsUrl(rawVfsUrl: String): String {
    val (_, document) = resolveTextDocument(rawVfsUrl)
    return readAction { document.text }
}

/**
 * Read a document by project-relative path, resolving it via [projectResolver]
 * and returning its text content.
 */
internal suspend fun readDocumentByRelativePath(
    relativePath: String,
    projectKey: String,
    projectResolver: WorkspaceProjectResolver,
): String {
    validateProjectRelativePath(relativePath)
    val resolved = projectResolver.resolve(projectKey = projectKey)
    val project = when (resolved) {
        is dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution.Resolved -> resolved.project
        is dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution.Unresolved -> resourceFail(resolved.message)
    }
    val basePath = project.basePath ?: resourceFail("Project $projectKey has no base path.")
    val fullPath = "$basePath/$relativePath"
    val file = readAction { LocalFileSystem.getInstance().findFileByPath(fullPath) }
        ?: resourceFail("Document not found at '$relativePath': $fullPath")
    return readDocumentByVfsUrl(file.url)
}

/**
 * Validate that a document range is within bounds.
 */
internal suspend fun validateDocumentRange(
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

/**
 * Validate that a line number is within bounds for the document.
 */
internal suspend fun validateDocumentLine(document: Document, line: Int) {
    val lineCount = readAction { document.lineCount }
    if (lineCount <= 0) {
        resourceFail("Document has no lines.")
    }
    validateOffset(line, lineCount - 1, "line")
}

// ---- private helpers ----

private fun validateOffset(value: Int, max: Int, name: String) {
    if (value !in 0..max) {
        resourceFail("$name must be in [0, $max], but was $value.")
    }
}

private fun resourceFail(message: String): Nothing {
    throw DocumentResourceReadException(message)
}

