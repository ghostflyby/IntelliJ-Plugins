package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

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
    val targetIsBinary: Boolean,
    val root: ExposedRoot,
    val parent: VirtualFile? = null,
    val targetName: String? = null,
)

internal suspend fun resolveProjectFileAccess(
    project: Project,
    root: ExposedRoot,
    relativePath: String,
): ProjectFileAccess {
    val file = resolveExposedRootFile(root, relativePath)
    val parentTarget = if (file == null) resolveExposedRootParent(root, relativePath) else null
    if (file == null && parentTarget == null) {
        return missingAccess(relativePath, root, FileContentClassification.MISSING)
    }
    return readAction {
        val classification = classify(project, file, root, parentTarget?.first)
        val targetName = parentTarget?.second
        val writableKinds = writableKindsFor(root, file, classification)
        ProjectFileAccess(
            file = file,
            policy = policyFor(classification, writableKinds),
            relativePath = relativePath,
            targetIsBinary = file?.let { !it.isDirectory && it.fileType.isBinary }
                ?: targetName?.let(::isKnownBinaryFileName)
                ?: false,
            root = root,
            parent = parentTarget?.first,
            targetName = targetName,
        )
    }
}

internal suspend fun classifyExistingProjectFile(
    project: Project,
    file: VirtualFile,
): FileAccessPolicy {
    val root = findContainingExposedRoot(project, file) ?: return policyFor(FileContentClassification.OUTSIDE_PROJECT)
    return readAction { policyFor(classify(project, file, root, null)) }
}

internal fun fileMetaPolicyFallback(file: VirtualFile): FileAccessPolicy {
    return policyFor(
        when {
            file.fileType.isBinary -> FileContentClassification.DEPENDENCY_OR_SDK
            else -> FileContentClassification.DEPENDENCY_OR_SDK
        },
    )
}

private fun classify(
    project: Project,
    exactFile: VirtualFile?,
    root: ExposedRoot,
    parent: VirtualFile?,
): FileContentClassification {
    val fileIndex = ProjectFileIndex.getInstance(project)
    val file = exactFile ?: parent ?: return FileContentClassification.MISSING
    if (!root.readable || !isUnderRoot(root, file)) return FileContentClassification.OUTSIDE_PROJECT
    if (fileIndex.isExcluded(file)) return FileContentClassification.EXCLUDED
    if (fileIndex.isInLibraryClasses(file) || fileIndex.isInLibrarySource(file)) {
        return FileContentClassification.DEPENDENCY_OR_SDK
    }
    if (!fileIndex.isInContent(file)) return FileContentClassification.OUTSIDE_PROJECT
    if (exactFile?.isDirectory == true) return FileContentClassification.WORKSPACE_TEXT
    val ignored = exactFile?.let { FileTypeRegistry.getInstance().isFileIgnored(it) } ?: false
    val binary = exactFile?.fileType?.isBinary ?: false
    return when {
        ignored && binary -> FileContentClassification.IGNORED_BINARY
        ignored -> FileContentClassification.IGNORED_TEXT
        binary -> FileContentClassification.WORKSPACE_BINARY
        else -> FileContentClassification.WORKSPACE_TEXT
    }
}

private fun writableKindsFor(
    root: ExposedRoot,
    file: VirtualFile?,
    classification: FileContentClassification,
): Set<FileContentKind>? {
    if (!root.writable) return emptySet()
    if (classification !in setOf(FileContentClassification.WORKSPACE_TEXT, FileContentClassification.IGNORED_TEXT)) {
        return null
    }
    if (file == null) return setOf(FileContentKind.PUT, FileContentKind.PATCH)
    if (!file.isWritable || file.`is`(VFileProperty.SPECIAL)) return emptySet()
    return setOf(FileContentKind.PUT, FileContentKind.PATCH, FileContentKind.DELETE)
}

private fun isKnownBinaryFileName(fileName: String): Boolean {
    val fileType = FileTypeRegistry.getInstance().getFileTypeByFileName(fileName)
    return fileType.isBinary && fileType.name != "UNKNOWN"
}

private fun isUnderRoot(root: ExposedRoot, file: VirtualFile): Boolean =
    file == root.base || VfsUtilCore.isAncestor(root.base, file, false)

private fun missingAccess(
    relativePath: String,
    root: ExposedRoot,
    classification: FileContentClassification,
): ProjectFileAccess = ProjectFileAccess(
    file = null,
    policy = policyFor(
        classification,
        writableKindsOverride = if (classification == FileContentClassification.MISSING && !root.writable) emptySet() else null,
    ),
    relativePath = relativePath,
    targetIsBinary = relativePath.substringAfterLast('/', relativePath).let(::isKnownBinaryFileName),
    root = root,
)

private fun policyFor(
    classification: FileContentClassification,
    writableKindsOverride: Set<FileContentKind>? = null,
): FileAccessPolicy {
    val policy = when (classification) {
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
    return writableKindsOverride?.let { policy.copy(writableKinds = it) } ?: policy
}

internal fun FileAccessPolicy.readableKindNames(): List<String> =
    readableKinds.map { it.name.lowercase() }.sorted()

internal fun FileAccessPolicy.writableKindNames(): List<String> =
    writableKinds.map { it.name.lowercase() }.sorted()
