package dev.ghostflyby.mcp.rest

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.filecontent.ExposedRoot
import dev.ghostflyby.mcp.filecontent.findExposedRoot

internal data class RootRouteTarget(
    val root: ExposedRoot,
    val relativePath: String,
)

internal suspend fun rootRouteTarget(
    project: Project,
    rootId: String,
    relativePath: String = "",
): RootRouteTarget? {
    val root = findExposedRoot(project, rootId) ?: return null
    return RootRouteTarget(root = root, relativePath = relativePath)
}

