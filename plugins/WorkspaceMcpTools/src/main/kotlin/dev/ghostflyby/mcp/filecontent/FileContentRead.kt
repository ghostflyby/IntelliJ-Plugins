/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.*
import dev.ghostflyby.mcp.resource.validateProjectRelativePath
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class ContentReadException(message: String) : RuntimeException(message)

// -- resolve VirtualFile --

internal suspend fun resolveFileByRelativePath(
    projectKey: String,
    relativePath: String,
    projectResolver: WorkspaceProjectResolver,
): VirtualFile {
    validateProjectRelativePath(relativePath)
    val project = when (val resolved = projectResolver.resolve(projectKey = projectKey)) {
        is WorkspaceProjectResolution.Resolved -> resolved.project
        is WorkspaceProjectResolution.Unresolved -> contentFail(resolved.message)
    }
    val basePath = project.basePath
        ?: contentFail("Project $projectKey has no base path.")
    val fullPath = "$basePath/$relativePath"
    return readAction { LocalFileSystem.getInstance().findFileByPath(fullPath) }
        ?: contentFail("File not found at '$relativePath' in project '$projectKey': $fullPath")
}

internal suspend fun resolveFileByRawUrl(rawVfsUrl: String): VirtualFile {
    val vfsManager = service<VirtualFileManager>()
    return readAction { vfsManager.findFileByUrl(rawVfsUrl) }
        ?: contentFail("File not found for URL: $rawVfsUrl")
}

// -- unified read --

internal suspend fun readContent(file: VirtualFile): String = readAction {
    if (file.isDirectory) return@readAction directoryListingContent(file)
    val document = FileDocumentManager.getInstance().getDocument(file)
    document?.text ?: readVfsTextOrFail(file)
}

// -- write --

internal suspend fun writeContent(file: VirtualFile, text: String) {
    val document = readAction {
        FileDocumentManager.getInstance().getDocument(file)
            ?: contentFail("No editable document for: ${file.url}")
    }
    runWriteAction {
        document.setText(text)
    }
}

// -- private --

private fun readVfsTextOrFail(file: VirtualFile): String {
    if (file.isTooLargeForIntellijSense()) {
        contentFail("File is too large and not indexed by IntelliJ: ${file.url}")
    }
    if (file.fileType.isBinary || isBinaryTypeName(file.fileType.name)) {
        contentFail("Binary file cannot be read as text: ${file.url} (type=${file.fileType.name})")
    }
    return VfsUtil.loadText(file)
}

private fun isBinaryTypeName(name: String): Boolean {
    val upper = name.uppercase()
    return upper in setOf("CLASS", "ARCHIVE", "NATIVE", "UNKNOWN")
}

private fun directoryListingContent(file: VirtualFile): String {
    val listing = VfsDirectoryListing(
        url = file.url,
        path = file.path,
        children = file.children.map { child ->
            VfsFileStat(
                name = child.name,
                url = child.url,
                path = child.path,
                isDirectory = child.isDirectory,
                length = child.length,
                lastModified = child.timeStamp,
                fileType = child.fileType.name,
            )
        },
    )
    return JSON.encodeToString(VfsDirectoryListing.serializer(), listing)
}

private fun contentFail(message: String): Nothing {
    throw ContentReadException(message)
}

// -- DTOs --

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
    val fileType: String,
)

private val JSON = Json {
    prettyPrint = true
    encodeDefaults = true
}
