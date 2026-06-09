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
import dev.ghostflyby.mcp.rest.markdown.TextBody
import kotlinx.serialization.Serializable

internal fun validateProjectRelativePath(relativePath: String) {
    require(relativePath.isNotBlank()) { "relativePath must not be blank." }
    require(!relativePath.startsWith('/')) { "relativePath must not be absolute: $relativePath" }
    require(relativePath.split('/').none { it == ".." }) {
        "relativePath must not contain '..' segments: $relativePath"
    }
}

internal class ContentReadException(message: String) : RuntimeException(message)

/** Raw file content served directly (text/bytes) or a directory listing. */
internal sealed interface FileContent {
    data class Text(val text: String, val mimeType: String) : FileContent
    data class Binary(val bytes: ByteArray, val mimeType: String) : FileContent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false
            return bytes.contentEquals(other.bytes) && mimeType == other.mimeType
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
    }

    data class Directory(val listing: DirectoryListing) : FileContent
}

// -- resolve VirtualFile --

internal suspend fun resolveFileByRawUrlOrNull(rawVfsUrl: String): VirtualFile? {
    val vfsManager = service<VirtualFileManager>()
    return readAction { vfsManager.findFileByUrl(rawVfsUrl) }
}

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

internal suspend fun readContentResult(file: VirtualFile): FileContent = readAction {
    when {
        file.isDirectory -> FileContent.Directory(file.readDirectoryListing())
        file.fileType.isBinary -> FileContent.Binary(file.contentsToByteArray(), file.inferMimeType())
        else -> {
            val document = getOrCreateDocument(file)
            FileContent.Text(document?.text ?: String(file.contentsToByteArray(), file.charset), file.inferMimeType())
        }
    }
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
    policy: FileAccessPolicy = fileMetaPolicyFallback(file),
): FileMeta {
    return readAction {
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
}

// -- structure read --

@Serializable
internal data class FileStructure(val elements: List<StructureElement>) : TextBody {
    override fun renderTextBody(): String = buildString {
        appendLine("## Structure")
        append(renderStructureText(this@FileStructure))
    }
}

@Serializable
internal data class StructureElement(
    val name: String,
    val type: String,
    val children: List<StructureElement> = emptyList(),
)

internal fun renderStructureText(structure: FileStructure): String = buildString {
    structure.elements.forEach { appendStructureElement(it, 0) }
}

private fun StringBuilder.appendStructureElement(element: StructureElement, depth: Int) {
    repeat(depth) { append('\t') }
    append(element.name)
    if (element.type.isNotBlank()) append(" (").append(element.type).append(")")
    appendLine()
    element.children.forEach { appendStructureElement(it, depth + 1) }
}

internal suspend fun readStructureResult(
    project: Project,
    file: VirtualFile,
): FileStructure {
    val document = readAction { FileDocumentManager.getInstance().getDocument(file) }
        ?: return FileStructure(emptyList())
    val elements = readAction {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@readAction emptyList<StructureElement>()
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile)
                as? TreeBasedStructureViewBuilder
            ?: return@readAction emptyList<StructureElement>()
        val editor = ImaginaryEditor(project, document)
        val model = builder.createStructureViewModel(editor)
        collectElements(model.root)
    }
    return FileStructure(elements)
}

private fun collectElements(element: Any?): List<StructureElement> {
    if (element !is TreeElement) return emptyList()
    val name = element.presentation.presentableText ?: ""
    val type = element.presentation.locationString ?: ""
    val children = element.children.toList().flatMap { collectElements(it) }
    return listOf(StructureElement(name, type, children))
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
) : TextBody {
    override fun renderTextBody(): String =
        children.joinToString(separator = "\n", postfix = if (children.isEmpty()) "" else "\n")
}

internal suspend fun readGlobResult(
    file: VirtualFile,
    patterns: List<String>,
    project: Project?,
    candidateLookup: GlobCandidateLookup = IntelliJGlobCandidateLookup,
): List<String> {
    if (!file.isDirectory) throw ContentReadException("glob requires a directory, but the target is a file: ${file.path}")

    return readAction {
        readGlobPaths(file, patterns, project, candidateLookup)
    }
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
