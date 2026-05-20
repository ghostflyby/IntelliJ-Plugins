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
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mcp.resource.validateProjectRelativePath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

internal class ContentReadException(message: String) : RuntimeException(message)

internal data class ContentResult(
    val payload: String,
    val mimeType: String,
    val isBinary: Boolean,
)

// -- resolve VirtualFile --

internal suspend fun resolveFileByRelativePath(
    project: Project,
    relativePath: String,
): VirtualFile {
    validateProjectRelativePath(relativePath)
    val basePath = project.basePath ?: contentFail("Project ${project.name} has no base path.")
    val fullPath = "$basePath/$relativePath"
    return readAction { LocalFileSystem.getInstance().findFileByPath(fullPath) }
        ?: contentFail("File not found at '$relativePath' in project '${project.name}': $fullPath")
}

internal suspend fun resolveFileByRawUrl(rawVfsUrl: String): VirtualFile {
    val vfsManager = service<VirtualFileManager>()
    return readAction { vfsManager.findFileByUrl(rawVfsUrl) }
        ?: contentFail("File not found for URL: $rawVfsUrl")
}

// -- unified read --

private sealed interface ReadData

private data class DirReadData(val listing: VfsDirectoryListing) : ReadData
private data class BinaryReadData(val bytes: ByteArray) : ReadData {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BinaryReadData) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }
}

private data class TextReadData(val text: String) : ReadData

internal suspend fun readContentResult(file: VirtualFile): ContentResult {
    val data = readAction {
        if (file.isDirectory) {
            DirReadData(file.readDirectoryListing())
        } else if (file.fileType.isBinary) {
            BinaryReadData(file.contentsToByteArray())
        } else {
            val document = FileDocumentManager.getInstance().getDocument(file)
            TextReadData(document?.text ?: String(file.contentsToByteArray(), file.charset))
        }
    }
    return buildContentResult(file, data)
}

private fun buildContentResult(file: VirtualFile, data: ReadData): ContentResult = when (data) {
    is DirReadData -> ContentResult(
        payload = JSON.encodeToString(VfsDirectoryListing.serializer(), data.listing),
        mimeType = "application/json",
        isBinary = false,
    )

    is BinaryReadData -> ContentResult(
        payload = Base64.encode(data.bytes),
        mimeType = file.inferMimeType(),
        isBinary = true,
    )

    is TextReadData -> ContentResult(
        payload = data.text,
        mimeType = file.inferMimeType(),
        isBinary = false,
    )
}

// -- metadata read --

@Serializable
internal data class FileMeta(
    val name: String,
    val url: String,
    val path: String,
    val isDirectory: Boolean,
    val length: Long,
    val lastModified: Long,
    val isWritable: Boolean,
    val fileType: String,
    val isBinary: Boolean,
    val charset: String = "",
    val textLength: Int? = null,
    val lineCount: Int? = null,
    val modificationStamp: Long? = null,
    val dirty: Boolean? = null,
)

internal suspend fun readMetaResult(file: VirtualFile, fields: String /* ""=all; "a,b"=subset */): ContentResult {
    val meta = readAction {
        val doc = FileDocumentManager.getInstance().getDocument(file)
        FileMeta(
            name = file.name,
            url = file.url,
            path = file.path,
            isDirectory = file.isDirectory,
            length = file.length,
            lastModified = file.timeStamp,
            isWritable = file.isWritable,
            fileType = file.fileType.name,
            isBinary = file.fileType.isBinary,
            charset = file.charset.name(),
            textLength = doc?.textLength,
            lineCount = doc?.lineCount,
            modificationStamp = doc?.modificationStamp,
            dirty = doc?.let { FileDocumentManager.getInstance().isDocumentUnsaved(it) },
        )
    }
    val json = filterAndSerialize(meta, fields)
    return ContentResult(payload = json, mimeType = "application/json", isBinary = false)
}

private fun filterAndSerialize(meta: FileMeta, fields: String): String {
    if (fields.isBlank()) return JSON.encodeToString(FileMeta.serializer(), meta)
    val set = fields.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val map = linkedMapOf<String, JsonElement>()
    val jsonObj = JSON.encodeToJsonElement(FileMeta.serializer(), meta).jsonObject
    jsonObj.forEach { (k, v) ->
        if (k in set) map[k] = v
    }
    return JSON.encodeToString(JsonObject.serializer(), buildJsonObject { map.forEach { (k, v) -> put(k, v) } })
}

// -- write --

internal suspend fun writeContent(file: VirtualFile, text: String) {
    val document = readAction {
        FileDocumentManager.getInstance().getDocument(file) ?: contentFail("No editable document for: ${file.url}")
    }
    runWriteAction {
        document.setText(text)
    }
}

// -- private helpers --

private fun VirtualFile.inferMimeType(): String {
    val ft = this.fileType
    // Tier 1: Language-based MIME (most accurate)
    if (ft is LanguageFileType) {
        ft.language.mimeTypes.firstOrNull()?.let { return it }
    }
    // Tier 2: Special-case mappings
    return when (ft.name.uppercase()) {
        "ARCHIVE" -> "application/zip"
        "CLASS" -> "application/java-vm"
        "NATIVE", "UNKNOWN" -> "application/octet-stream"
        else -> {
            if (ft.name.startsWith("Image", ignoreCase = true)) {
                this.extension?.let { ext -> "image/$ext" } ?: "application/octet-stream"
            } else if (ft.isBinary) {
                "application/octet-stream"
            } else {
                // Tier 3: text fallback
                "text/plain"
            }
        }
    }
}

private fun VirtualFile.readDirectoryListing(): VfsDirectoryListing {
    return VfsDirectoryListing(
        url = this.url,
        path = this.path,
        children = this.children.map { child ->
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
