package dev.ghostflyby.mcp.rest

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.filecontent.ExposedRoot
import dev.ghostflyby.mcp.filecontent.findExposedRoot
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

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

internal suspend fun ApplicationCall.rootRouteTargetOrNotFound(
    project: Project,
    rootId: String,
    relativePath: String = "",
): RootRouteTarget? {
    val target = rootRouteTarget(project, rootId, relativePath)
    if (target == null) {
        respond(HttpStatusCode.NotFound, mapOf("error" to "Root not found"))
    }
    return target
}
