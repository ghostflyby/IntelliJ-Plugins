/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

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

/**
 * Resolve the route path to a [RestSessionRouteTarget] that supports file writes.
 * VFS-only URLs (read-only target) are rejected with a 403 response.
 */
internal suspend fun ApplicationCall.resolveWritableSessionRouteTarget(
    path: String,
): RestSessionRouteTarget? {
    return when (val target = resolveFileRouteTarget(path)) {
        is RestFileRouteTarget.ProjectFile -> target.target
        is RestFileRouteTarget.VirtualFileReadOnly -> {
            respond(
                HttpStatusCode.Forbidden,
                RestError("VFS URL writes are allowed only for files in the session project workspace"),
            )
            null
        }

        null -> null
    }
}
