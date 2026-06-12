/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import com.intellij.ide.structureView.StructureViewTreeElement
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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
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

internal suspend fun readTextRangeResult(
    file: VirtualFile,
    range: FileLineRange,
): FileContent.Text = readAction {
    require(!file.isDirectory) { "Range reads are not supported for directories" }
    require(!file.fileType.isBinary) { "Range reads are not supported for binary files" }
    val document = getOrCreateDocument(file)
    val text = document?.let { range.readFrom(it) } ?: range.readFrom(String(file.contentsToByteArray(), file.charset))
    FileContent.Text(text, file.inferMimeType())
}

internal sealed interface FileLineRange {
    fun readFrom(document: Document): String = readFrom(document.text)
    fun readFrom(text: String): String {
        val lines = text.lineSequence().toList()
        if (lines.isEmpty()) return ""
        val lineCount = lines.size
        val start = startLine(lineCount).coerceIn(1, lineCount + 1)
        val end = endLine(lineCount).coerceIn(0, lineCount)
        if (start > end) return ""
        return lines.subList(start - 1, end).joinToString("\n")
    }

    fun startLine(lineCount: Int): Int
    fun endLine(lineCount: Int): Int

    data class Lines(val startLine: Int, val endLine: Int) : FileLineRange {
        init {
            require(startLine > 0) { "startLine must be positive" }
            require(endLine > 0) { "endLine must be positive" }
            require(endLine >= startLine) { "endLine must be greater than or equal to startLine" }
        }

        override fun startLine(lineCount: Int): Int = startLine
        override fun endLine(lineCount: Int): Int = endLine
    }

    data class MaxLines(val startLine: Int, val maxLines: Int) : FileLineRange {
        init {
            require(startLine > 0) { "startLine must be positive" }
            require(maxLines > 0) { "maxLines must be positive" }
        }

        override fun startLine(lineCount: Int): Int = startLine
        override fun endLine(lineCount: Int): Int = startLine + maxLines - 1
    }

    data class Around(val aroundLine: Int, val radius: Int) : FileLineRange {
        init {
            require(aroundLine > 0) { "aroundLine must be positive" }
            require(radius >= 0) { "radius must be non-negative" }
        }

        override fun startLine(lineCount: Int): Int = aroundLine - radius
        override fun endLine(lineCount: Int): Int = aroundLine + radius
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
    val startLine: Int? = null,
    val endLine: Int? = null,
    val children: List<StructureElement> = emptyList(),
)

internal fun renderStructureText(structure: FileStructure): String = buildString {
    structure.elements.forEach { appendStructureElement(it, 0) }
}

private fun StringBuilder.appendStructureElement(element: StructureElement, depth: Int) {
    repeat(depth) { append('\t') }
    append(element.name)
    if (element.type.isNotBlank()) append(" (").append(element.type).append(")")
    if (element.startLine != null && element.endLine != null) {
        append(" [").append(element.startLine).append('-').append(element.endLine).append(']')
    }
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
            ?: return@readAction fallbackTextStructure(document)
        val editor = ImaginaryEditor(project, document)
        val model = builder.createStructureViewModel(editor)
        collectElements(model.root, document, psiFile.namedElementLineRanges(document))
            .ifEmpty { fallbackTextStructure(document) }
    }
    return FileStructure(elements)
}

private fun collectElements(
    element: Any?,
    document: Document,
    namedElementRanges: Map<String, Pair<Int, Int>>,
): List<StructureElement> {
    if (element !is TreeElement) return emptyList()
    val name = element.presentation.presentableText ?: ""
    val type = element.presentation.locationString ?: ""
    val children = element.children.toList().flatMap { collectElements(it, document, namedElementRanges) }
    val range = element.structureSourceElement().lineRange(document)
        ?: children.lineRange()
        ?: namedElementRanges[name]
        ?: name.findLineRange(document)
    return listOf(
        StructureElement(
            name = name,
            type = type,
            startLine = range?.first,
            endLine = range?.second,
            children = children,
        ),
    )
}

private fun Any?.lineRange(document: Document): Pair<Int, Int>? {
    val textRange = when (this) {
        is PsiElement -> textRange
        else -> null
    } ?: return null
    val start = textRange.startOffset.coerceIn(0, document.textLength)
    val rawEnd = textRange.endOffset.coerceIn(0, document.textLength)
    val end = rawEnd.coerceAtLeast(start)
    val endForLine = (end - 1).coerceAtLeast(start).coerceIn(0, document.textLength)
    return document.getLineNumber(start) + 1 to document.getLineNumber(endForLine) + 1
}

private fun List<StructureElement>.lineRange(): Pair<Int, Int>? {
    val starts = mapNotNull { it.startLine }
    val ends = mapNotNull { it.endLine }
    if (starts.isEmpty() || ends.isEmpty()) return null
    return starts.min() to ends.max()
}

private fun String.findLineRange(document: Document): Pair<Int, Int>? {
    if (isBlank()) return null
    val offset = document.text.indexOf(this)
    if (offset < 0) return null
    val line = document.getLineNumber(offset) + 1
    return line to line
}

private fun PsiElement.namedElementLineRanges(document: Document): Map<String, Pair<Int, Int>> {
    val ranges = linkedMapOf<String, Pair<Int, Int>>()
    collectNamedElementLineRanges(document, ranges)
    return ranges
}

private fun PsiElement.collectNamedElementLineRanges(
    document: Document,
    ranges: MutableMap<String, Pair<Int, Int>>,
) {
    if (this is PsiNamedElement) {
        val elementName = name
        if (!elementName.isNullOrBlank()) {
            lineRange(document)?.let { ranges.putIfAbsent(elementName, it) }
        }
    }
    children.forEach { it.collectNamedElementLineRanges(document, ranges) }
}

private fun TreeElement.structureSourceElement(): Any? = when (this) {
    is StructureViewTreeElement -> value
    else -> this
}

private fun fallbackTextStructure(document: Document): List<StructureElement> {
    val declaration = Regex("""^\s*(class|interface|object|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    return document.text.lines().mapIndexedNotNull { index, line ->
        val match = declaration.find(line) ?: return@mapIndexedNotNull null
        val lineNumber = index + 1
        StructureElement(
            name = match.groupValues[2],
            type = match.groupValues[1],
            startLine = lineNumber,
            endLine = lineNumber,
        )
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
