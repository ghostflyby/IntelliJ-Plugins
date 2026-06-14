/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.route

import io.ktor.util.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.Request

public class WorkspaceMcpCall<out R : Request>(
    public val connection: ClientConnection,
    public val request: R,
    public val attributes: Attributes = Attributes(),
    public val resourceHolder: ResourceHolder = ResourceHolder(),
) {
    public val sessionId: String get() = connection.sessionId

    /**
     * Lists client roots. Returns empty list if the client does not support this capability.
     */
    public suspend fun roots(): List<String> {
        return runCatching {
            connection.listRoots().roots.map { it.uri.removePrefix("file://") }
        }.getOrDefault(emptyList())
    }
}
