/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server

import dev.ghostflyby.mcp.server.route.AncestorContext
import dev.ghostflyby.mcp.server.route.WorkspaceMcpCall
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.Request

internal interface WorkspaceMcpCallFactory {
    fun <R : Request> create(
        connection: ClientConnection,
        request: R,
        parameters: AncestorContext,
    ): WorkspaceMcpCall<R>
}

internal fun workspaceMcpCallFactory(projectResolver: WorkspaceProjectProvider): WorkspaceMcpCallFactory {
    return object : WorkspaceMcpCallFactory {
        override fun <R : Request> create(
            connection: ClientConnection,
            request: R,
            parameters: AncestorContext,
        ): WorkspaceMcpCall<R> {
            return WorkspaceMcpCall(
                connection = connection,
                request = request,
                parameters = parameters,
                projectResolver = projectResolver,
            )
        }
    }
}
