/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server

import dev.ghostflyby.mcp.server.route.Keys
import dev.ghostflyby.mcp.server.route.AncestorContext
import dev.ghostflyby.mcp.server.route.McpCallContext
import dev.ghostflyby.mcp.server.route.WorkspaceMcpCall
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.Request

internal interface McpCallFactory {
    fun <R : Request> create(
        connection: ClientConnection,
        request: R,
        routeParams: Map<String, String> = emptyMap(),
    ): McpCallContext<R>
}

internal fun mcpCallFactory(): McpCallFactory {
    return object : McpCallFactory {
        override fun <R : Request> create(
            connection: ClientConnection,
            request: R,
            routeParams: Map<String, String>,
        ): McpCallContext<R> {
            val wcall = WorkspaceMcpCall(
                connection = connection,
                request = request,
            )
            if (routeParams.isNotEmpty()) {
                wcall.attributes[Keys.RouteParameters] = AncestorContext(routeParams)
            }
            return McpCallContext(wcall)
        }
    }
}

internal fun McpCallFactory.withAttributes(
    block: WorkspaceMcpCall<*>.() -> Unit,
): McpCallFactory {
    val delegate = this
    return object : McpCallFactory {
        override fun <R : Request> create(
            connection: ClientConnection,
            request: R,
            routeParams: Map<String, String>,
        ): McpCallContext<R> {
            val call = delegate.create(connection, request, routeParams)
            @Suppress("UNCHECKED_CAST")
            (call.call as WorkspaceMcpCall<*>).block()
            return call
        }
    }
}
