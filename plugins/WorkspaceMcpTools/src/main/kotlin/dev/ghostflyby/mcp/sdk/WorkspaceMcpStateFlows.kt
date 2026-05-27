/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.Service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Service(Service.Level.APP)
internal class WorkspaceMcpStateFlows {
    private val _resourceUpdates = MutableStateFlow(ResourceUpdateState())
    val resourceUpdates: StateFlow<ResourceUpdateState> = _resourceUpdates.asStateFlow()

    private val _globalResourceListChanges = MutableStateFlow(0L)
    val globalResourceListChanges: StateFlow<Long> = _globalResourceListChanges.asStateFlow()

    private val _perSessionResourceListChanges = MutableStateFlow(PerSessionListChange())
    val perSessionResourceListChanges: StateFlow<PerSessionListChange> = _perSessionResourceListChanges.asStateFlow()

    fun resourceContentChanged(uris: Set<String>) {
        if (uris.isEmpty()) return
        _resourceUpdates.update { it.next(uris) }
    }

    fun globalResourcesChanged() {
        _globalResourceListChanges.update { it + 1 }
    }

    fun sessionResourcesChanged(sessionId: String) {
        if (sessionId.isBlank()) return
        _perSessionResourceListChanges.update { it.next(sessionId) }
    }
}

internal data class ResourceUpdateState(
    val uriGenerations: Map<String, Long> = emptyMap(),
) {
    val uris: Set<String> get() = uriGenerations.keys

    fun next(uris: Set<String>): ResourceUpdateState {
        return copy(
            uriGenerations = uriGenerations.toMutableMap().apply {
                uris.forEach { uri ->
                    this[uri] = (this[uri] ?: 0L) + 1
                }
            },
        )
    }
}

internal data class PerSessionListChange(
    val sessionGenerations: Map<String, Long> = emptyMap(),
) {
    val sessionIds: Set<String> get() = sessionGenerations.keys

    fun next(sessionId: String): PerSessionListChange {
        return copy(
            sessionGenerations = sessionGenerations + (sessionId to ((sessionGenerations[sessionId] ?: 0L) + 1)),
        )
    }
}
