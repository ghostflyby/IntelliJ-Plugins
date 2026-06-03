package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

internal enum class FileContentClassification {
    WORKSPACE_TEXT,
    IGNORED_TEXT,
    WORKSPACE_BINARY,
    IGNORED_BINARY,
    EXCLUDED,
    DEPENDENCY_OR_SDK,
    MISSING,
    OUTSIDE_PROJECT,
}

internal enum class FileContentKind {
    CONTENT,
    META,
    STRUCTURE,
    BYTES,
    GLOB,
    PUT,
    PATCH,
    DELETE,
}

internal data class FileAccessPolicy(
    val classification: FileContentClassification,
    val readableKinds: Set<FileContentKind>,
    val writableKinds: Set<FileContentKind>,
    val requiresForceForWrite: Boolean,
    val reason: String,
) {
    internal fun canRead(kind: FileContentKind): Boolean = kind in readableKinds

    internal fun canWrite(kind: FileContentKind, force: Boolean): Boolean =
        kind in writableKinds && (!requiresForceForWrite || force)
}

internal data class ProjectFileAccess(
    val file: VirtualFile?,
    val policy: FileAccessPolicy,
    val relativePath: String,
    val targetPath: Path?,
    val targetIsBinary: Boolean,
)

internal suspend fun resolveProjectFileAccess(
    project: Project,
    relativePath: String,
): ProjectFileAccess {
    val normalized = normalizeProjectRelativePath(project, relativePath)
        ?: return ProjectFileAccess(
            file = null,
            policy = policyFor(FileContentClassification.OUTSIDE_PROJECT),
            relativePath = relativePath,
            targetPath = null,
            targetIsBinary = false,
        )

    return readAction {
        val file = LocalFileSystem.getInstance().findFileByNioFile(normalized.targetPath)
        val existingForClassification = file ?: findExistingAncestor(project, normalized.targetPath)
        val classification = classify(project, existingForClassification, file, normalized.targetPath)
        ProjectFileAccess(
            file = file,
            policy = policyFor(classification),
            relativePath = relativePath,
            targetPath = normalized.targetPath,
            targetIsBinary = file?.let { !it.isDirectory && it.fileType.isBinary }
                ?: isKnownBinaryFileName(normalized.targetPath.fileName.toString()),
        )
    }
}

internal suspend fun classifyExistingProjectFile(
    project: Project,
    file: VirtualFile,
): FileAccessPolicy = readAction { policyFor(classify(project, file, file, Path.of(file.path))) }

internal fun fileMetaPolicyFallback(file: VirtualFile): FileAccessPolicy {
    return policyFor(
        when {
            file.fileType.isBinary -> FileContentClassification.DEPENDENCY_OR_SDK
            else -> FileContentClassification.DEPENDENCY_OR_SDK
        },
    )
}

private data class NormalizedProjectPath(val targetPath: Path)

private fun normalizeProjectRelativePath(project: Project, relativePath: String): NormalizedProjectPath? {
    if (relativePath.isBlank() || relativePath.startsWith('/')) return null
    if (relativePath.split('/').any { it == ".." }) return null
    val basePath = project.basePath ?: return null
    val base = Path.of(basePath).normalize()
    val target = base.resolve(relativePath).normalize()
    if (!target.startsWith(base)) return null
    return NormalizedProjectPath(target)
}

private fun findExistingAncestor(project: Project, targetPath: Path): VirtualFile? {
    val basePath = project.basePath ?: return null
    val base = Path.of(basePath).normalize()
    var current: Path? = targetPath
    while (current != null && current.startsWith(base)) {
        if (current.exists()) {
            LocalFileSystem.getInstance().findFileByNioFile(current)?.let { return it }
        }
        current = current.parent
    }
    return null
}

private fun classify(
    project: Project,
    existingForClassification: VirtualFile?,
    exactFile: VirtualFile?,
    targetPath: Path,
): FileContentClassification {
    val fileIndex = ProjectFileIndex.getInstance(project)
    val fileTypeManager = FileTypeManager.getInstance()
    val file = existingForClassification ?: return FileContentClassification.MISSING
    if (fileIndex.isExcluded(file)) return FileContentClassification.EXCLUDED
    if (fileIndex.isInLibraryClasses(file) || fileIndex.isInLibrarySource(file)) {
        return FileContentClassification.DEPENDENCY_OR_SDK
    }
    if (exactFile?.isDirectory == true) return FileContentClassification.WORKSPACE_TEXT
    val ignored = exactFile?.let { fileTypeManager.isFileIgnored(it) }
        ?: fileTypeManager.isFileIgnored(targetPath.fileName.toString()) ||
        isIgnoredByProjectGitignore(project, targetPath)
    val binary = exactFile?.fileType?.isBinary ?: isKnownBinaryFileName(targetPath.fileName.toString())
    return when {
        ignored && binary -> FileContentClassification.IGNORED_BINARY
        ignored -> FileContentClassification.IGNORED_TEXT
        binary -> FileContentClassification.WORKSPACE_BINARY
        else -> FileContentClassification.WORKSPACE_TEXT
    }
}

private fun isKnownBinaryFileName(fileName: String): Boolean {
    val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
    return fileType.isBinary && fileType.name != "UNKNOWN"
}

private fun isIgnoredByProjectGitignore(project: Project, targetPath: Path): Boolean {
    val basePath = project.basePath ?: return false
    val base = Path.of(basePath).normalize()
    val gitignore = base.resolve(".gitignore")
    if (!gitignore.isRegularFile()) return false
    val relative = base.relativize(targetPath).joinToString("/")
    val name = targetPath.fileName.toString()
    return runCatching {
        gitignore.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .any { pattern -> gitignorePatternMatches(pattern, relative, name) }
    }.getOrDefault(false)
}

private fun gitignorePatternMatches(pattern: String, relative: String, name: String): Boolean {
    val normalized = pattern.trim('/').replace("\\", "/")
    return when {
        normalized == relative || normalized == name -> true
        normalized.startsWith("*.") -> name.endsWith(normalized.removePrefix("*"))
        normalized.endsWith("/") -> relative.startsWith(normalized.trimEnd('/') + "/")
        "/" in normalized -> relative == normalized
        else -> name == normalized
    }
}

private fun policyFor(classification: FileContentClassification): FileAccessPolicy {
    return when (classification) {
        FileContentClassification.WORKSPACE_TEXT -> FileAccessPolicy(
            classification = classification,
            readableKinds = setOf(
                FileContentKind.CONTENT,
                FileContentKind.META,
                FileContentKind.STRUCTURE,
                FileContentKind.GLOB,
            ),
            writableKinds = setOf(FileContentKind.PUT, FileContentKind.PATCH, FileContentKind.DELETE),
            requiresForceForWrite = false,
            reason = "Workspace text file",
        )

        FileContentClassification.IGNORED_TEXT -> FileAccessPolicy(
            classification = classification,
            readableKinds = setOf(FileContentKind.CONTENT, FileContentKind.META),
            writableKinds = setOf(FileContentKind.PUT, FileContentKind.PATCH, FileContentKind.DELETE),
            requiresForceForWrite = true,
            reason = "Ignored text file requires force=true for writes",
        )

        FileContentClassification.WORKSPACE_BINARY -> FileAccessPolicy(
            classification = classification,
            readableKinds = setOf(FileContentKind.BYTES, FileContentKind.META),
            writableKinds = emptySet(),
            requiresForceForWrite = false,
            reason = "Binary file writes are disabled in this phase",
        )

        FileContentClassification.IGNORED_BINARY -> FileAccessPolicy(
            classification = classification,
            readableKinds = setOf(FileContentKind.BYTES, FileContentKind.META),
            writableKinds = emptySet(),
            requiresForceForWrite = true,
            reason = "Ignored binary files are GET-only in this phase",
        )

        FileContentClassification.EXCLUDED -> FileAccessPolicy(
            classification = classification,
            readableKinds = emptySet(),
            writableKinds = emptySet(),
            requiresForceForWrite = false,
            reason = "Excluded files are outside the workspace REST surface",
        )

        FileContentClassification.DEPENDENCY_OR_SDK -> FileAccessPolicy(
            classification = classification,
            readableKinds = setOf(FileContentKind.CONTENT, FileContentKind.META, FileContentKind.BYTES),
            writableKinds = emptySet(),
            requiresForceForWrite = false,
            reason = "Dependency and SDK files are read-only",
        )

        FileContentClassification.MISSING -> FileAccessPolicy(
            classification = classification,
            readableKinds = emptySet(),
            writableKinds = setOf(FileContentKind.PUT, FileContentKind.PATCH),
            requiresForceForWrite = false,
            reason = "File does not exist",
        )

        FileContentClassification.OUTSIDE_PROJECT -> FileAccessPolicy(
            classification = classification,
            readableKinds = emptySet(),
            writableKinds = emptySet(),
            requiresForceForWrite = false,
            reason = "Path is outside the project",
        )
    }
}

internal fun FileAccessPolicy.readableKindNames(): List<String> =
    readableKinds.map { it.name.lowercase() }.sorted()

internal fun FileAccessPolicy.writableKindNames(): List<String> =
    writableKinds.map { it.name.lowercase() }.sorted()
