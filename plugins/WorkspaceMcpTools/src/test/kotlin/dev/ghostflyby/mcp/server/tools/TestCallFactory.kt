/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.tools

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.server.route.AncestorContext
import dev.ghostflyby.mcp.server.route.WorkspaceMcpCall
import dev.ghostflyby.mcp.server.WorkspaceMcpCallFactory
import dev.ghostflyby.mcp.server.WorkspaceProjectProvider
import dev.ghostflyby.mcp.server.WorkspaceProjectResolution
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.Request

internal object TestCallFactory : WorkspaceMcpCallFactory {
    override fun <R : Request> create(
        connection: ClientConnection,
        request: R,
        parameters: AncestorContext,
    ): WorkspaceMcpCall<R> {
        return WorkspaceMcpCall(
            connection = connection,
            request = request,
            parameters = parameters,
            projectResolver = ForbiddenProjectProvider,
        )
    }
}

private object ForbiddenProjectProvider : WorkspaceProjectProvider {
    override fun openProjects(): List<Project> = error("Project provider should not be queried.")

    override suspend fun resolve(
        projectKey: String?,
        projectPath: String?,
        rawVfsUrl: String?,
        relativePath: String?,
        rootsCandidates: List<String>?,
    ): WorkspaceProjectResolution = error("Project provider should not be queried.")
}
