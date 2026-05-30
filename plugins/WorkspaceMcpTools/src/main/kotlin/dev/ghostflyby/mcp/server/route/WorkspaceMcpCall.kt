/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.route

import io.ktor.util.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.Request

internal class WorkspaceMcpCall<out R : Request>(
    val connection: ClientConnection,
    val request: R,
    val attributes: Attributes = Attributes(),
    val resourceHolder: ResourceHolder = ResourceHolder(),
) {
    val sessionId: String get() = connection.sessionId

    /**
     * Lists client roots. Returns empty list if the client does not support this capability.
     */
    suspend fun roots(): List<String> {
        return runCatching {
            connection.listRoots().roots.map { it.uri.removePrefix("file://") }
        }.getOrDefault(emptyList())
    }
}
