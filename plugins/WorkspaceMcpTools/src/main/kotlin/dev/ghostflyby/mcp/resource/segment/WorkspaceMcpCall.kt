/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.resource.segment

import dev.ghostflyby.mcp.sdk.WorkspaceMcpSessionState
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.Request

internal class WorkspaceMcpCall<out R : Request>(
    val connection: ClientConnection,
    val request: R,
    val ancestors: AncestorContext,
    private val sessionState: WorkspaceMcpSessionState,
) {
    val sessionId: String get() = connection.sessionId

    suspend fun roots(): List<String> {
        return sessionState.getRoots(sessionId)
    }
}

