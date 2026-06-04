/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

internal fun validateProjectRelativePath(relativePath: String) {
    require(relativePath.isNotBlank()) { "relativePath must not be blank." }
    require(!relativePath.startsWith('/')) { "relativePath must not be absolute: $relativePath" }
    require(relativePath.split('/').none { it == ".." }) {
        "relativePath must not contain '..' segments: $relativePath"
    }
}

internal class ContentReadException(message: String) : RuntimeException(message)

internal data class ContentResult(
    val payload: String,
    val mimeType: String,
    val isBinary: Boolean,
)

// -- resolve VirtualFile --

internal suspend fun resolveFileByRawUrlOrNull(rawVfsUrl: String): VirtualFile? {
    val vfsManager = service<VirtualFileManager>()
    return readAction { vfsManager.findFileByUrl(rawVfsUrl) }
}

// -- unified read --

private sealed interface ReadData

private data class DirReadData(val listing: DirectoryListing) : ReadData
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

// -- Document LRU cache --

private val documentCache = object : LinkedHashMap<String, Document>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Document>): Boolean = size > 64
}

private fun getOrCreateDocument(file: VirtualFile): Document? {
    val url = file.url
    synchronized(documentCache) { documentCache[url] }?.let { return it }
    val doc = FileDocumentManager.getInstance().getDocument(file) ?: return null
    synchronized(documentCache) { documentCache[url] = doc }
    return doc
}

internal suspend fun readContentResult(file: VirtualFile): ContentResult {
    val data = readAction {
        if (file.isDirectory) {
            DirReadData(file.readDirectoryListing())
        } else if (file.fileType.isBinary) {
            BinaryReadData(file.contentsToByteArray())
        } else {
            val document = getOrCreateDocument(file)
            TextReadData(document?.text ?: String(file.contentsToByteArray(), file.charset))
        }
    }
    return buildContentResult(file, data)
}

private fun buildContentResult(file: VirtualFile, data: ReadData): ContentResult = when (data) {
    is DirReadData -> ContentResult(
        payload = JSON.encodeToString(data.listing),
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
    val classification: String = FileContentClassification.DEPENDENCY_OR_SDK.name,
    val readableKinds: List<String> = emptyList(),
    val writableKinds: List<String> = emptyList(),
    val requiresForceForWrite: Boolean = false,
    val reason: String = "",
)

internal suspend fun readMetaResult(
    file: VirtualFile,
    fields: String, /* ""=all; "a,b"=subset */
    policy: FileAccessPolicy = fileMetaPolicyFallback(file),
): ContentResult {
    val meta = readAction {
        val doc = getOrCreateDocument(file)
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
            classification = policy.classification.name,
            readableKinds = policy.readableKindNames(),
            writableKinds = policy.writableKindNames(),
            requiresForceForWrite = policy.requiresForceForWrite,
            reason = policy.reason,
        )
    }
    val json = filterAndSerialize(meta, fields)
    return ContentResult(payload = json, mimeType = "application/json", isBinary = false)
}

// -- structure read --

@Serializable
internal data class FileStructure(val elements: List<StructureElement>)

@Serializable
internal data class StructureElement(
    val name: String,
    val type: String,
    val children: List<StructureElement> = emptyList(),
)

internal suspend fun readStructureResult(
    project: Project,
    file: VirtualFile,
): String {
    val document = readAction { FileDocumentManager.getInstance().getDocument(file) }
        ?: return """{"elements":[]}"""
    val elements = readAction {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@readAction emptyList<StructureElement>()
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile)
                as? TreeBasedStructureViewBuilder
            ?: return@readAction emptyList<StructureElement>()
        val editor = ImaginaryEditor(project, document)
        val model = builder.createStructureViewModel(editor)
        collectElements(model.root)
    }
    return JSON.encodeToString(FileStructure.serializer(), FileStructure(elements))
}

private fun collectElements(element: Any?): List<StructureElement> {
    if (element !is TreeElement) return emptyList()
    val name = element.presentation.presentableText ?: ""
    val type = element.presentation.locationString ?: ""
    val children = element.children.toList().flatMap { collectElements(it) }
    return listOf(StructureElement(name, type, children))
}

// -- helpers --

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

private fun VirtualFile.readDirectoryListing(): DirectoryListing {
    return DirectoryListing(
        children = this.children.map { child ->
            if (child.isDirectory) "${child.name}/" else child.name
        },
    )
}

// -- DTOs --

@Serializable
internal data class DirectoryListing(
    val children: List<String>,
)

internal suspend fun readGlobResult(
    file: VirtualFile,
    pattern: String,
    project: Project?,
): ContentResult = readGlobResult(file, listOf(pattern), project)

internal suspend fun readGlobResult(
    file: VirtualFile,
    patterns: List<String>,
    project: Project?,
    candidateLookup: GlobCandidateLookup = IntelliJGlobCandidateLookup,
): ContentResult {
    if (!file.isDirectory) throw ContentReadException("glob requires a directory, but the target is a file: ${file.path}")

    val paths = readAction {
        readGlobPaths(file, patterns, project, candidateLookup)
    }
    return ContentResult(payload = JSON.encodeToString(paths), mimeType = "application/json", isBinary = false)
}

internal fun readGlobPaths(
    dir: VirtualFile,
    patterns: List<String>,
    project: Project?,
    candidateLookup: GlobCandidateLookup = IntelliJGlobCandidateLookup,
): List<String> {
    val matchers = patterns.map { pattern ->
        runCatching { WorkspaceGlobPattern.compile(pattern) }
            .getOrElse { error -> throw ContentReadException("Invalid glob pattern: ${error.message}") }
    }
    if (matchers.isEmpty()) throw ContentReadException("glob requires at least one pattern")
    val result = linkedSetOf<String>()
    matchers.forEach { matcher ->
        readGlobCandidates(dir, matcher, project, candidateLookup).forEach { child ->
            val relative = matcher.relativePath(child, dir) ?: return@forEach
            if (!child.isDirectory && relative.isNotBlank() && matcher.matches(relative) && isVisibleGlobResult(
                    project,
                    child,
                )
            ) {
                result += relative
            }
        }
    }
    return result.sorted()
}

private fun readGlobCandidates(
    dir: VirtualFile,
    matcher: WorkspaceGlobPattern,
    project: Project?,
    candidateLookup: GlobCandidateLookup,
): Collection<VirtualFile> {
    if (project == null) return candidateLookup.walkFiles(dir)
    val scope = GlobalSearchScopesCore.directoryScope(project, dir, true)
    matcher.literalFileName?.let { return candidateLookup.filesByName(project, it, scope) }
    matcher.extension?.let { extension ->
        val fileType = FileTypeRegistry.getInstance().getFileTypeByExtension(extension)
        if (fileType.name != "UNKNOWN") return candidateLookup.filesByType(fileType, scope)
    }
    return candidateLookup.walkFiles(dir)
}

private fun readGlobViaVfsWalk(dir: VirtualFile): List<VirtualFile> {
    val result = mutableListOf<VirtualFile>()
    VfsUtilCore.iterateChildrenRecursively(dir, null) { child ->
        if (!child.isDirectory) {
            result += child
        }
        true
    }
    return result
}

private fun isVisibleGlobResult(project: Project?, file: VirtualFile): Boolean {
    if (project == null) return true
    val fileIndex = ProjectFileIndex.getInstance(project)
    if (fileIndex.isExcluded(file)) return false
    if (FileTypeRegistry.getInstance().isFileIgnored(file)) return false
    return fileIndex.isInContent(file) || fileIndex.isInLibrary(file)
}

internal interface GlobCandidateLookup {
    fun filesByName(project: Project, fileName: String, scope: GlobalSearchScope): Collection<VirtualFile>
    fun filesByType(fileType: FileType, scope: GlobalSearchScope): Collection<VirtualFile>
    fun walkFiles(root: VirtualFile): Collection<VirtualFile>
}

private object IntelliJGlobCandidateLookup : GlobCandidateLookup {
    override fun filesByName(project: Project, fileName: String, scope: GlobalSearchScope): Collection<VirtualFile> =
        FilenameIndex.getVirtualFilesByName(fileName, scope)

    override fun filesByType(fileType: FileType, scope: GlobalSearchScope): Collection<VirtualFile> =
        FileTypeIndex.getFiles(fileType, scope)

    override fun walkFiles(root: VirtualFile): Collection<VirtualFile> =
        readGlobViaVfsWalk(root)
}

private val JSON = Json {
    prettyPrint = true
    encodeDefaults = true
}
