/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import dev.ghostflyby.mcp.pluginVersion
import dev.ghostflyby.mcp.sdk.workspaceInstanceKey
import dev.ghostflyby.mcp.server.route.resources.ServerInfoResource
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

@Serializable
private data class ServerInfoResponse(
    val instanceKey: String,
    val version: String,
)

internal fun Route.serverInfoRoutes() {
    get<ServerInfoResource> {
        call.respond(
            ServerInfoResponse(
                instanceKey = workspaceInstanceKey(),
                version = pluginVersion,
            ),
        )
    }
}
