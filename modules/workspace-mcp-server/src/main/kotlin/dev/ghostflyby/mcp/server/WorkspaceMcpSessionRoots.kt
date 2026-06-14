/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.Root
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

public class WorkspaceMcpSessionRoots {
    private val _state = MutableStateFlow(WorkspaceMcpSessionRootsState())
    public val state: StateFlow<WorkspaceMcpSessionRootsState> = _state.asStateFlow()

    public fun recordSessionConnected(sessionId: String) {
        if (sessionId.isBlank()) return
        _state.update { current ->
            if (sessionId in current.rootsBySession) current
            else current.copy(rootsBySession = current.rootsBySession + (sessionId to emptyList()))
        }
    }

    public fun recordSessionClosed(sessionId: String) {
        _state.update { current ->
            current.copy(rootsBySession = current.rootsBySession - sessionId)
        }
    }

    public fun recordRootsListChanged(sessionId: String) {
        if (sessionId.isBlank()) return
        _state.update { it.nextChange(sessionId) }
    }
}

public data class WorkspaceMcpSessionRootsState(
    public val rootsBySession: Map<String, List<Root>> = emptyMap(),
    public val changedGenerationsBySession: Map<String, Long> = emptyMap(),
) {
    public val sessionIds: Set<String> get() = rootsBySession.keys

    public fun nextChange(sessionId: String): WorkspaceMcpSessionRootsState {
        return copy(
            rootsBySession = if (sessionId in rootsBySession) rootsBySession else rootsBySession + (sessionId to emptyList()),
            changedGenerationsBySession = changedGenerationsBySession +
                    (sessionId to ((changedGenerationsBySession[sessionId] ?: 0L) + 1)),
        )
    }
}
