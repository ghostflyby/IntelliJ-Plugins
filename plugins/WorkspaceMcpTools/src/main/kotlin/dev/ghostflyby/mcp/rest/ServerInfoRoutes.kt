/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class ServerInfoResponse(
    val instanceKey: String,
    val version: String,
)

internal fun Route.serverInfoRoutes() {
    get<Api.ServerInfo> {
        val context = call.application.workspaceRestContext()
        call.respond(
            ServerInfoResponse(
                instanceKey = context.instanceKey,
                version = context.version,
            ),
        )
    }
}
