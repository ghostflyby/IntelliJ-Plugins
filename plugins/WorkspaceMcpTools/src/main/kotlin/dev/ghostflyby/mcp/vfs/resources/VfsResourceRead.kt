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

package dev.ghostflyby.mcp.vfs.resources

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*
import dev.ghostflyby.mcp.resource.APPLICATION_JSON_MIME_TYPE
import dev.ghostflyby.mcp.resource.TEXT_PLAIN_MIME_TYPE
import dev.ghostflyby.mcp.resource.validateProjectRelativePath
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val JSON = Json {
    prettyPrint = true
    encodeDefaults = true
}

internal class VfsResourceReadException(message: String) : RuntimeException(message)

/**
 * Read a file by project-relative path, returning its text content.
 * For directories, returns a JSON directory listing.
 */
internal suspend fun readFileByRelativePath(
    resourceUri: String,
    projectKey: String,
    relativePath: String,
    projectResolver: WorkspaceProjectResolver,
): String {
    validateProjectRelativePath(relativePath)
    val project = resolveProjectForRead(projectKey, projectResolver)
    val basePath = project.basePath
        ?: resourceFail("Project $projectKey has no base path.")
    val fullPath = "$basePath/$relativePath"
    val file = readAction { LocalFileSystem.getInstance().findFileByPath(fullPath) }
        ?: resourceFail("File not found at relative path '$relativePath' in project '$projectKey': $fullPath")
    return if (readAction { file.isDirectory }) {
        directoryListingContent(file)
    } else {
        vfsTextContent(file)
    }
}

/**
 * Read a file by raw VFS URL, returning its text content.
 * For directories, returns a JSON directory listing.
 */
internal suspend fun readFileByVfsUrl(
    resourceUri: String,
    rawVfsUrl: String,
    projectResolver: WorkspaceProjectResolver,
): String {
    val file = resolveVfsFile(rawVfsUrl)
    return if (readAction { file.isDirectory }) {
        directoryListingContent(file)
    } else {
        vfsTextContent(file)
    }
}

/**
 * Infer the MIME type for a VirtualFile.
 * - Directories always return [APPLICATION_JSON_MIME_TYPE].
 * - Files use [VfsUtil.getFileType], mapped via [fileTypeToTextMimeType].
 */
internal fun inferVfsMimeType(file: VirtualFile): String {
    if (file.isDirectory) return APPLICATION_JSON_MIME_TYPE
    val typeName = file.fileType.name
    return fileTypeToTextMimeType(typeName)
}

// ---- private helpers ----

private suspend fun resolveProjectForRead(
    projectKey: String,
    projectResolver: WorkspaceProjectResolver,
): Project {
    val resolved = projectResolver.resolve(projectKey = projectKey)
    return when (resolved) {
        is dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution.Resolved -> resolved.project
        is dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution.Unresolved -> resourceFail(resolved.message)
    }
}

private suspend fun resolveVfsFile(rawVfsUrl: String): VirtualFile = readAction {
    val vfsManager = service<VirtualFileManager>()
    vfsManager.findFileByUrl(rawVfsUrl)
        ?: resourceFail("File not found for URL: $rawVfsUrl")
}

private suspend fun directoryListingContent(file: VirtualFile): String {
    if (!readAction { file.isDirectory }) {
        resourceFail("Resource is not a directory: ${file.url}")
    }
    val listing = readAction {
        VfsDirectoryListing(
            url = file.url,
            path = file.path,
            children = file.children.map(::toFileStat),
        )
    }
    return JSON.encodeToString(VfsDirectoryListing.serializer(), listing)
}

private suspend fun vfsTextContent(file: VirtualFile): String {
    if (readAction { file.isDirectory }) {
        resourceFail("URL points to a directory, not a file: ${file.url}")
    }
    return readAction { VfsUtil.loadText(file) }
}

private fun toFileStat(file: VirtualFile): VfsFileStat {
    return VfsFileStat(
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

private fun fileTypeToTextMimeType(typeName: String): String {
    return when (typeName.lowercase()) {
        "kotlin" -> "text/x-kotlin"
        "java" -> "text/x-java-source"
        "xml" -> "application/xml"
        "json" -> APPLICATION_JSON_MIME_TYPE
        "markdown" -> "text/markdown"
        else -> TEXT_PLAIN_MIME_TYPE
    }
}

private fun resourceFail(message: String): Nothing {
    throw VfsResourceReadException(message)
}

// ---- local DTOs ----

@Serializable
internal data class VfsDirectoryListing(
    val url: String,
    val path: String,
    val children: List<VfsFileStat>,
)

@Serializable
internal data class VfsFileStat(
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

