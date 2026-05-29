/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import io.modelcontextprotocol.kotlin.sdk.types.Root
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class WorkspaceMcpSessionRoots {
    private val _state = MutableStateFlow(WorkspaceMcpSessionRootsState())
    val state: StateFlow<WorkspaceMcpSessionRootsState> = _state.asStateFlow()

    fun recordSessionConnected(sessionId: String) {
        if (sessionId.isBlank()) return
        _state.update { current ->
            if (sessionId in current.rootsBySession) current
            else current.copy(rootsBySession = current.rootsBySession + (sessionId to emptyList()))
        }
    }

    fun recordSessionClosed(sessionId: String) {
        _state.update { current ->
            current.copy(rootsBySession = current.rootsBySession - sessionId)
        }
    }

    fun recordRootsListChanged(sessionId: String) {
        if (sessionId.isBlank()) return
        _state.update { it.nextChange(sessionId) }
    }
}

internal data class WorkspaceMcpSessionRootsState(
    val rootsBySession: Map<String, List<Root>> = emptyMap(),
    val changedGenerationsBySession: Map<String, Long> = emptyMap(),
) {
    val sessionIds: Set<String> get() = rootsBySession.keys

    fun nextChange(sessionId: String): WorkspaceMcpSessionRootsState {
        return copy(
            rootsBySession = if (sessionId in rootsBySession) rootsBySession else rootsBySession + (sessionId to emptyList()),
            changedGenerationsBySession = changedGenerationsBySession +
                    (sessionId to ((changedGenerationsBySession[sessionId] ?: 0L) + 1)),
        )
    }
}
