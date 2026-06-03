package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.Serializable

internal enum class ExposedRootKind {
    WORKSPACE,
    LIBRARY,
    SDK,
    GENERATED,
    SCRATCH,
    EXTERNAL,
}

@Serializable
internal data class ExposedRootDto(
    val id: String,
    val displayName: String,
    val url: String,
    val kind: String,
    val readable: Boolean,
    val writable: Boolean,
)

internal data class ExposedRoot(
    val id: String,
    val displayName: String,
    val base: VirtualFile,
    val kind: ExposedRootKind,
    val readable: Boolean,
    val writable: Boolean,
) {
    fun toDto(): ExposedRootDto = ExposedRootDto(
        id = id,
        displayName = displayName,
        url = base.url,
        kind = kind.name.lowercase(),
        readable = readable,
        writable = writable,
    )
}

internal suspend fun exposedWorkspaceRoots(project: Project): List<ExposedRoot> = readAction {
    val contentRoots = ProjectRootManager.getInstance(project).contentRoots.toList()
    val roots = contentRoots.ifEmpty {
        project.getBaseDirectories()
    }
    roots
        .distinctBy { it.url }
        .sortedBy { it.path }
        .mapIndexed { index, root ->
            ExposedRoot(
                id = rootId(index, root),
                displayName = root.presentableUrl,
                base = root,
                kind = ExposedRootKind.WORKSPACE,
                readable = true,
                writable = true,
            )
        }
}

internal suspend fun findExposedRoot(project: Project, rootId: String): ExposedRoot? =
    exposedWorkspaceRoots(project).firstOrNull { it.id == rootId }

internal suspend fun findContainingExposedRoot(project: Project, file: VirtualFile): ExposedRoot? {
    val roots = exposedWorkspaceRoots(project)
    return readAction {
        roots.firstOrNull { root ->
            file == root.base || VfsUtilCore.isAncestor(root.base, file, false)
        }
    }
}

internal suspend fun resolveExposedRootFile(root: ExposedRoot, relativePath: String): VirtualFile? = readAction {
    if (relativePath.isBlank()) return@readAction root.base
    val file = findChildBySegments(root.base, relativePath.split('/').filter { it.isNotEmpty() })
        ?: return@readAction null
    if (VfsUtilCore.isAncestor(root.base, file, false)) file else null
}

internal suspend fun resolveExposedRootParent(root: ExposedRoot, relativePath: String): Pair<VirtualFile, String>? =
    readAction {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull() ?: return@readAction null
        val parent = if (segments.size == 1) {
            root.base
        } else {
            findChildBySegments(root.base, segments.dropLast(1)) ?: return@readAction null
        }
        if (!parent.isDirectory || !VfsUtilCore.isAncestor(root.base, parent, false)) return@readAction null
        parent to name
    }

private fun findChildBySegments(base: VirtualFile, segments: List<String>): VirtualFile? {
    var current = base
    for (segment in segments) {
        current = current.findChild(segment) ?: return null
    }
    return current
}

private fun rootId(index: Int, root: VirtualFile): String = buildString {
    append("workspace")
    if (index > 0) append("-").append(index + 1)
    append("-")
    append(root.name.ifBlank { "root" }.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "root" })
}
