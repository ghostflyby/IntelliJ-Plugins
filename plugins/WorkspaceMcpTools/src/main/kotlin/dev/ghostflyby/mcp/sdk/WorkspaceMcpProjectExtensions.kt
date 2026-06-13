/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.server.route.Keys
import dev.ghostflyby.mcp.server.route.WorkspaceMcpCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import dev.ghostflyby.mcp.rest.Api.Project as RouteProject

internal suspend fun WorkspaceMcpCall<*>.project(): Project {
    // 1) Try typed resource from the holder first
    val typedKey = resourceHolder.get<RouteProject>()?.projectKey
    if (typedKey != null) {
        val resolver = attributes[SdkKeys.ProjectProvider]
        return when (val r = resolver.resolve(typedKey)) {
            is WorkspaceProjectResolution.Resolved -> r.project
            is WorkspaceProjectResolution.Unresolved -> error(r.message)
        }
    }

    // 2) Fallback: RouteParameters (list providers without typed resource)
    val resolver = attributes[SdkKeys.ProjectProvider]
    val routeParams = attributes[Keys.RouteParameters]
    val project = when (val r = resolver.resolve(projectKey = routeParams["projectKey"])) {
        is WorkspaceProjectResolution.Resolved -> r.project
        is WorkspaceProjectResolution.Unresolved -> error(r.message)
    }
    coroutineScope {
        val projectJob = project.scope.coroutineContext[Job]
        projectJob?.invokeOnCompletion { cause ->
            if (cause != null) cancel(CancellationException("Project closed", cause))
        }
    }
    return project
}

