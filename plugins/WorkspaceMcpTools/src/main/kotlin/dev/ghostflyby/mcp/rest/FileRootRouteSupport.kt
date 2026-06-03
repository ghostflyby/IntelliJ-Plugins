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

internal suspend fun ApplicationCall.rootRouteTarget(project: Project): RootRouteTarget? {
    val rootId = parameters["rootId"] ?: return null
    val root = findExposedRoot(project, rootId) ?: return null
    val relativePath = parameters.getAll("relativePath")?.joinToString("/") ?: ""
    return RootRouteTarget(root = root, relativePath = relativePath)
}

internal suspend fun ApplicationCall.rootRouteTargetOrNotFound(project: Project): RootRouteTarget? {
    val target = rootRouteTarget(project)
    if (target == null) {
        respond(HttpStatusCode.NotFound, mapOf("error" to "Root not found"))
    }
    return target
}
