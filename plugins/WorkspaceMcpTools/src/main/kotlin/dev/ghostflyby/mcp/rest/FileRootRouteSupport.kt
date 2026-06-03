package dev.ghostflyby.mcp.rest

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.filecontent.ExposedRoot
import dev.ghostflyby.mcp.filecontent.findExposedRoot
import io.ktor.server.application.ApplicationCall

internal data class FileRouteTarget(
    val root: ExposedRoot?,
    val relativePath: String,
)

internal suspend fun ApplicationCall.fileRouteTarget(project: Project): FileRouteTarget? {
    val parts = parameters.getAll("relativePath") ?: return null
    if (parts.isEmpty()) return null
    val first = parts.first()
    val root = findExposedRoot(project, first)
    return if (root != null) {
        FileRouteTarget(root = root, relativePath = parts.drop(1).joinToString("/"))
    } else {
        FileRouteTarget(root = null, relativePath = parts.joinToString("/"))
    }
}
