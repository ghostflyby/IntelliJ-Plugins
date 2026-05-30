/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server

import kotlinx.coroutines.flow.StateFlow

/**
 * Pure data types for resource and session change tracking.
 * Moved here from dev.ghostflyby.mcp.sdk to break the circular dependency.
 */

public data class ResourceUpdateState(
    public val uriGenerations: Map<String, Long> = emptyMap(),
) {
    public val uris: Set<String> get() = uriGenerations.keys

    public fun next(uris: Set<String>): ResourceUpdateState {
        return copy(
            uriGenerations = uriGenerations.toMutableMap().apply {
                uris.forEach { uri ->
                    this[uri] = (this[uri] ?: 0L) + 1
                }
            },
        )
    }
}

public data class PerSessionListChange(
    public val sessionGenerations: Map<String, Long> = emptyMap(),
) {
    public val sessionIds: Set<String> get() = sessionGenerations.keys

    public fun next(sessionId: String): PerSessionListChange {
        return copy(
            sessionGenerations = sessionGenerations + (sessionId to ((sessionGenerations[sessionId] ?: 0L) + 1)),
        )
    }
}

/**
 * Resource change notifier interface.
 * The IntelliJ @Service WorkspaceMcpStateFlows implements this interface
 * so the server module does not need an IntelliJ dependency.
 */
public interface ResourceChangeNotifier {
    public val resourceUpdates: StateFlow<ResourceUpdateState>
    public val globalResourceListChanges: StateFlow<Long>
    public val perSessionResourceListChanges: StateFlow<PerSessionListChange>
    public fun sessionResourcesChanged(sessionId: String)
}
